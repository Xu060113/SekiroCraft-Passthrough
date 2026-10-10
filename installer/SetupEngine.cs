using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace SekiroCraftSetup {
    public sealed class BundleInfo {
        public string version, sourceCommit, gameSha256, jarName, apiName, forgeJarName, forgeNativeName;
        public bool gameTested, flickerResolved;
        public Dictionary<string,string> files;
    }
    public sealed class Bundle {
        public BundleInfo Info;
        public Dictionary<string,byte[]> Files=new Dictionary<string,byte[]>(StringComparer.Ordinal);
        public static readonly JavaScriptSerializer Json=new JavaScriptSerializer { MaxJsonLength=4*1024*1024 };
        public const string SupportedExe="637ACA527538C0EC6E1F136C8ED66046E95DFBDBB1F51926E134D9916398B856";
        public static string Hash(byte[] bytes) { using(var hash=SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(bytes)).Replace("-",""); }
        public static string HashFile(string path) { using(var stream=File.OpenRead(path)) using(var hash=SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(stream)).Replace("-",""); }
        public static Bundle Load(Stream source) {
            var bundle=new Bundle(); long total=0;
            using(var zip=new ZipArchive(source,ZipArchiveMode.Read,true)) foreach(var entry in zip.Entries) {
                if(entry.FullName!=Path.GetFileName(entry.FullName) || entry.FullName.Contains("\\") || entry.FullName.Contains(":") || entry.FullName=="." || entry.FullName==".." || bundle.Files.ContainsKey(entry.FullName))
                    throw new InvalidDataException("安装包包含非法或重复路径。");
                total+=entry.Length;
                if(entry.Length>16*1024*1024 || total>32*1024*1024) throw new InvalidDataException("安装包内容大小异常。");
                using(var input=entry.Open()) using(var output=new MemoryStream()) { input.CopyTo(output); bundle.Files.Add(entry.FullName,output.ToArray()); }
            }
            if(!bundle.Files.ContainsKey("bundle.json")) throw new InvalidDataException("缺少安装清单。");
            bundle.Info=Json.Deserialize<BundleInfo>(Encoding.UTF8.GetString(bundle.Files["bundle.json"]));
            if(bundle.Info==null || bundle.Info.gameSha256!=SupportedExe || bundle.Info.files==null || bundle.Info.files.Count!=bundle.Files.Count-1)
                throw new InvalidDataException("安装清单无效。");
            foreach(var file in bundle.Info.files) if(!bundle.Files.ContainsKey(file.Key) || Hash(bundle.Files[file.Key])!=file.Value.ToUpperInvariant())
                throw new InvalidDataException("安装包校验失败："+file.Key);
            foreach(var required in new[]{"dinput8.dll","sekirobridge.ini",bundle.Info.jarName,bundle.Info.apiName,bundle.Info.forgeJarName,bundle.Info.forgeNativeName,"fabric-profile.json","简易教程.txt"})
                if(String.IsNullOrEmpty(required) || !bundle.Files.ContainsKey(required)) throw new InvalidDataException("安装包缺少组件。");
            return bundle;
        }
    }
    public sealed class Settings {
        public string Sekiro, Minecraft, Grapple="M";
        public string Loader="Fabric";
        public bool NewOfficialProfile, AutoBoss=true, ReplaceOtherLoader;
    }
    public sealed class OwnedFile {
        public string root, name, originalHash, installedHash, backup;
    }
    public sealed class InstallRecord {
        public int schema=1;
        public bool active;
        public string sekiro, minecraft, launcher, version, sourceCommit, installedUtc, loader;
        public List<OwnedFile> files=new List<OwnedFile>();
    }
    public sealed class Change {
        public string Root, Name;
        public byte[] Bytes;
    }
    public sealed class InstallPlan {
        public string Game, MC, Launcher, Runtime;
        public InstallRecord Previous;
        public List<Change> Changes=new List<Change>();
        public List<string> Notes=new List<string>();
    }
    public sealed class Engine {
        static Engine() { AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling",false); AppContext.SetSwitch("Switch.System.IO.BlockLongPaths",false); }
        public const string ManagedFolder=".sekirocraft-installer";
        public const string ProfileId="SekiroCraft-1.20.1-Fabric-0.19.5";
        readonly Bundle bundle;
        public Action<string> Log=delegate {};
        // Injectable failure point is used only by the isolated rollback tests.
        internal Action<int> AfterWrite=delegate {};
        internal Action RequireClosed=GamesClosed;
        internal string SaveRoot=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),"Sekiro");
        public Engine(Bundle value) { bundle=value; }
        static bool Equal(string a,string b) { return String.Equals(a,b,StringComparison.OrdinalIgnoreCase); }
        public static string Full(string path) {
            if(String.IsNullOrWhiteSpace(path)) throw new InvalidOperationException("请先选择游戏目录。");
            var full=Path.GetFullPath(path);
            return Equal(full,Path.GetPathRoot(full))?full:full.TrimEnd(Path.DirectorySeparatorChar);
        }
        public static string Under(string root,string name) {
            if(Path.IsPathRooted(name) || name.IndexOf(':')>=0) throw new InvalidDataException("非法安装路径。");
            var prefix=Full(root).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar; var result=Path.GetFullPath(Path.Combine(prefix,name));
            if(!result.StartsWith(prefix,StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("安装路径越界。");
            // Never follow junctions/symlinks into another game or user directory.
            for(var at=result; !Equal(at,Full(root)); at=Path.GetDirectoryName(at)) {
                if((File.Exists(at)||Directory.Exists(at)) && (File.GetAttributes(at)&FileAttributes.ReparsePoint)!=0)
                    throw new IOException("目录包含链接，请选择真实游戏目录："+at);
            }
            if(Directory.Exists(root) && (File.GetAttributes(root)&FileAttributes.ReparsePoint)!=0) throw new IOException("请选择真实目录，不要选择目录链接。");
            return result;
        }
        public static void GamesClosed() {
            foreach(var name in new[]{"sekiro","java","javaw","MinecraftLauncher"}) {
                var processes=Process.GetProcessesByName(name);
                try { if(processes.Length>0) throw new InvalidOperationException("请正常退出只狼、Minecraft 和启动器后重试。检测到："+name+"。安装程序不会强制关闭游戏。"); }
                finally { foreach(var process in processes) process.Dispose(); }
            }
        }
        static void AtomicWrite(string path,byte[] bytes) {
            Directory.CreateDirectory(Path.GetDirectoryName(path)); var temporary=Path.Combine(Path.GetDirectoryName(path),"sc-"+Guid.NewGuid().ToString("N").Substring(0,12)+".tmp");
            try { File.WriteAllBytes(temporary,bytes); if(File.Exists(path)) File.Replace(temporary,path,null); else File.Move(temporary,path); }
            finally { if(File.Exists(temporary)) File.Delete(temporary); }
        }
        static byte[] Text(string value) { return new UTF8Encoding(false).GetBytes(value); }
        static Dictionary<string,object> ObjectJson(string path) { return Bundle.Json.Deserialize<Dictionary<string,object>>(File.ReadAllText(path)); }
        static bool FabricProfile(string path,out string version) {
            version="";
            try {
                var text=File.ReadAllText(path); var obj=ObjectJson(path); object parent;
                bool mc=obj.TryGetValue("inheritsFrom",out parent) && Convert.ToString(parent)=="1.20.1";
                if(!mc) mc=text.Contains("net.minecraft:client:1.20.1\"") || text.Contains("net.fabricmc:intermediary:1.20.1\"");
                var match=Regex.Match(text,"net.fabricmc:fabric-loader:([0-9.]+)");
                if(!mc || !match.Success || text.Contains("net.minecraftforge:") || text.Contains("net.neoforged:")) return false;
                version=match.Groups[1].Value; return new Version(version)>=new Version("0.16.10");
            } catch { return false; }
        }
        public static bool IsFabricDirectory(string root) {
            if(!Directory.Exists(root)) return false;
            string ignored;
            return Directory.GetFiles(root,"*.json").Any(path=>FabricProfile(path,out ignored));
        }
        static bool ForgeProfile(string path) {
            try {
                var text=File.ReadAllText(path);var obj=ObjectJson(path);object parent;
                var mc=obj.TryGetValue("inheritsFrom",out parent) && Convert.ToString(parent)=="1.20.1";
                if(!mc)mc=obj.TryGetValue("clientVersion",out parent) && Convert.ToString(parent)=="1.20.1";
                if(!mc)mc=text.Contains("net.minecraft:client:1.20.1\"");
                if(!mc || text.Contains("net.fabricmc:") || text.Contains("net.neoforged:"))return false;
                var match=Regex.Match(text,"net.minecraftforge:(?:forge|fmlloader):1\\.20\\.1-(47\\.[0-9]+\\.[0-9]+)");
                return match.Success && new Version(match.Groups[1].Value)>=new Version("47.4.10");
            }catch{return false;}
        }
        public static bool IsForgeDirectory(string root) {
            return Directory.Exists(root) && Directory.GetFiles(root,"*.json").Any(ForgeProfile);
        }
        static string ModId(ZipArchive zip) {
            var fabric=zip.GetEntry("fabric.mod.json");
            if(fabric!=null){
                if(fabric.Length>1024*1024)throw new InvalidDataException("模组清单过大。");
                using(var reader=new StreamReader(fabric.Open())) {var info=Bundle.Json.Deserialize<Dictionary<string,object>>(reader.ReadToEnd());if(info.ContainsKey("id"))return Convert.ToString(info["id"]);}
            }
            var forge=zip.GetEntry("META-INF/mods.toml");
            if(forge!=null){
                if(forge.Length>1024*1024)throw new InvalidDataException("模组清单过大。");
                using(var reader=new StreamReader(forge.Open())) {if(Regex.IsMatch(reader.ReadToEnd(),"(?m)^\\s*modId\\s*=\\s*[\"']sekirobridge[\"']"))return "sekirobridge";}
            }
            return "";
        }
        static string SetProperties(string text,Dictionary<string,string> values) {
            foreach(var value in values) { text=Regex.Replace(text,"(?m)^\\s*"+Regex.Escape(value.Key)+"\\s*[=:].*(?:\\r?\\n|$)",""); text=text.TrimEnd()+"\r\n"+value.Key+"="+value.Value+"\r\n"; }
            return text;
        }
        static string Ini(string original,Dictionary<string,string> values) {
            var match=Regex.Match(original,"(?ims)^\\[SekiroBridge\\][^\\r\\n]*(?:\\r?\\n|$)(.*?)(?=^\\[|\\z)");
            if(!match.Success) throw new InvalidDataException("已有配置缺少 SekiroBridge 段，未覆盖。请先备份并移走该配置。");
            return original.Substring(0,match.Index)+"[SekiroBridge]\r\n"+SetProperties(match.Groups[1].Value,values).TrimStart('\r','\n')+original.Substring(match.Index+match.Length);
        }
        InstallRecord ReadRecord(string game) {
            var file=Under(game,ManagedFolder+"/installation.json");
            if(!File.Exists(file)) return null;
            var record=Bundle.Json.Deserialize<InstallRecord>(File.ReadAllText(file));
            if(record==null || record.schema!=1 || !Equal(Full(record.sekiro),game) || record.files==null || record.files.Count>128)
                throw new InvalidDataException("安装记录无效，未修改游戏文件。");
            foreach(var entry in record.files) {
                Resolve(record,entry.root,entry.name);
                if(entry.backup!=null) Under(game,ManagedFolder+"/"+entry.backup);
                if(entry.root=="game" && entry.name!="dinput8.dll" && entry.name!="sekirobridge.ini") throw new InvalidDataException("安装记录包含不受管理的只狼文件。");
                if(entry.root=="mc" && !(entry.name.StartsWith("mods/",StringComparison.Ordinal) && entry.name.EndsWith(".jar",StringComparison.OrdinalIgnoreCase)) && entry.name!="sekirobridge/bridge.properties" && entry.name!="SekiroCraft-简易教程.txt")
                    throw new InvalidDataException("安装记录包含不受管理的 MC 文件。");
                if(entry.root=="mc" && entry.name.StartsWith("mods/",StringComparison.Ordinal)) Under(Under(record.minecraft,"mods"),entry.name.Substring(5));
                if(entry.root=="launcher" && entry.name!="launcher_profiles.json" && entry.name!="versions/"+ProfileId+"/"+ProfileId+".json") throw new InvalidDataException("安装记录包含不受管理的启动器文件。");
            }
            return record;
        }
        static string Resolve(InstallRecord record,string kind,string name) {
            string root=kind=="game"?record.sekiro:kind=="mc"?record.minecraft:kind=="launcher"?record.launcher:null;
            if(String.IsNullOrEmpty(root)) throw new InvalidDataException("未知安装记录目录。");
            return Under(root,name);
        }
        public InstallPlan Check(Settings settings) {
            RequireClosed(); if(!Environment.Is64BitOperatingSystem) throw new InvalidOperationException("仅支持 Windows x64。");
            if(settings.Loader!="Fabric" && settings.Loader!="Forge")throw new InvalidOperationException("请选择 Fabric 或 Forge。");
            bool isForge=settings.Loader=="Forge";
            if(isForge && settings.NewOfficialProfile)throw new InvalidOperationException("Forge 请先用启动器安装 1.20.1 + Forge 47.4.26 并运行一次，再选择已有实例。安装器不会自动安装 Forge 加载器。");
            var game=Full(settings.Sekiro); var selected=Full(settings.Minecraft);
            var exe=Under(game,"sekiro.exe");
            if(!File.Exists(exe)) throw new InvalidOperationException("只狼目录选错了：请选择含 sekiro.exe 的文件夹。");
            if(Bundle.HashFile(exe)!=bundle.Info.gameSha256) throw new InvalidOperationException("当前只狼程序指纹不受支持。不要只改哈希绕过检查；需要另行适配游戏构建。");
            if(!Regex.IsMatch(settings.Grapple??"","^[A-Za-z]$")) throw new InvalidOperationException("钩索键必须为一个字母，并与你的只狼设置一致。");
            var plan=new InstallPlan { Game=game, MC=selected, Runtime=Under(game,ManagedFolder+"/runtime") };
            if(settings.NewOfficialProfile) {
                if(!File.Exists(Under(selected,"versions/1.20.1/1.20.1.json")) || !File.Exists(Under(selected,"versions/1.20.1/1.20.1.jar")) || !File.Exists(Under(selected,"launcher_profiles.json")))
                    throw new InvalidOperationException("新建选项需要官方启动器的 .minecraft 根目录。请先在官方启动器安装并启动一次原版 1.20.1；第三方启动器请先建立 Fabric 实例，再选“已有实例”。");
                plan.Launcher=selected; plan.MC=Under(selected,"versions/"+ProfileId);
                var profile=Bundle.Json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(bundle.Files["fabric-profile.json"]));
                profile["id"]=ProfileId;
                plan.Changes.Add(new Change { Root="launcher",Name="versions/"+ProfileId+"/"+ProfileId+".json",Bytes=Text(Bundle.Json.Serialize(profile)) });
                var launcher=ObjectJson(Under(selected,"launcher_profiles.json")); object oldProfiles;
                var profiles=launcher.TryGetValue("profiles",out oldProfiles)?oldProfiles as Dictionary<string,object>:null;
                if(profiles==null) throw new InvalidDataException("官方启动器配置无效；未修改。");
                profiles[ProfileId]=new Dictionary<string,object> { {"name","SekiroCraft · MC 1.20.1 Fabric"},{"type","custom"},{"lastVersionId",ProfileId},{"gameDir",plan.MC} };
                plan.Changes.Add(new Change { Root="launcher",Name="launcher_profiles.json",Bytes=Text(Bundle.Json.Serialize(launcher)) });
                plan.Notes.Add("将创建独立官方启动器配置；首次启动时启动器需要联网补齐 Fabric 库。");
            } else {
                if(!Directory.Exists(selected) || !Directory.Exists(Under(selected,"mods"))) throw new InvalidOperationException("MC 目录需含 mods 文件夹。请从启动器打开该实例的游戏目录，不要选择启动器 EXE 所在目录。");
                if(isForge) {
                    var marker=Under(selected,"logs/latest.log");var latest=File.Exists(marker)?ReadHead(marker,262144):"";
                    var logVersion=Regex.Match(latest,"--fml\\.forgeVersion[, =]+(47\\.[0-9]+\\.[0-9]+)");
                    var validLog=logVersion.Success && new Version(logVersion.Groups[1].Value)>=new Version("47.4.10") && Regex.IsMatch(latest,"--fml\\.mcVersion[, =]+1\\.20\\.1(?:[, \\]]|$)") && !latest.Contains("net.neoforged");
                    if(!IsForgeDirectory(selected) && !validLog)throw new InvalidOperationException("未识别到 MC 1.20.1 Forge >=47.4.10,<48。推荐已测试的 47.4.26；请先运行一次该实例再退出，并从启动器打开实际游戏目录。");
                } else if(!IsFabricDirectory(selected)) {
                    // Official launcher custom gameDir may keep its version JSON elsewhere.
                    var marker=Under(selected,"logs/latest.log"); var latest=File.Exists(marker)?ReadHead(marker,262144):"";
                    if(!Regex.IsMatch(latest,"Loading Minecraft 1\\.20\\.1 with Fabric Loader (0\\.(?:1[6-9]|[2-9][0-9])\\.[0-9]+)"))
                        throw new InvalidOperationException("未识别到 Minecraft 1.20.1 Fabric（Loader 至少 0.16.10）。请先用启动器运行一次该 Fabric 实例，再选择它的游戏目录；建议 Loader 0.19.5。");
                }
            }
            if(Equal(game,plan.MC) || plan.MC.StartsWith(game+"\\",StringComparison.OrdinalIgnoreCase) || game.StartsWith(plan.MC+"\\",StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("两款游戏需使用独立目录。");
            plan.Previous=ReadRecord(game);
            if(plan.Previous!=null && plan.Previous.active && !Equal(plan.Previous.loader??"Fabric",settings.Loader))throw new InvalidOperationException("该只狼由另一加载器方案管理。请先恢复安装前状态，再切换 Fabric/Forge，避免混装。");
            if(plan.Previous!=null && plan.Previous.active && !Equal(Full(plan.Previous.minecraft),plan.MC)) throw new InvalidOperationException("该只狼已关联另一 MC 目录。请先恢复旧安装，再更换实例。");
            if(plan.Previous!=null && plan.Previous.active) foreach(var file in plan.Previous.files) {
                var path=Resolve(plan.Previous,file.root,file.name);
                if(file.name.EndsWith(".dll",StringComparison.OrdinalIgnoreCase) || file.name.EndsWith(".jar",StringComparison.OrdinalIgnoreCase)) {
                    var hash=File.Exists(path)?Bundle.HashFile(path):null;
                    if(!Equal(hash,file.installedHash)) throw new InvalidOperationException("已安装的文件被改动，未覆盖："+path+"。请先备份并恢复该文件。");
                }
            }
            var dll=Under(game,"dinput8.dll"); var ini=Under(game,"sekirobridge.ini");
            if((plan.Previous==null || !plan.Previous.active) && (File.Exists(dll)||File.Exists(ini)) && !settings.ReplaceOtherLoader)
                throw new InvalidOperationException("已有只狼模组/桥接配置。若确认暂时替换，请勾选“备份并替换已有只狼加载器”；它们会被完整备份。旧脚本与新安装器不要混用。");
            var config=File.Exists(ini)?File.ReadAllText(ini):Encoding.UTF8.GetString(bundle.Files["sekirobridge.ini"]);
            if(config.IndexOf("[SekiroBridge]",StringComparison.OrdinalIgnoreCase)<0 && settings.ReplaceOtherLoader) config=Encoding.UTF8.GetString(bundle.Files["sekirobridge.ini"]);
            var settingsMap=new Dictionary<string,string> { {"enabled","1"},{"channel","default"},{"data_root",plan.Runtime},{"grapple_key",settings.Grapple.ToUpperInvariant()},
                {"combat_trace","0"},{"native_hits",settings.AutoBoss?"1":"0"},{"native_phase_finish","0"},{"auto_boss_phases",settings.AutoBoss?"1":"0"} };
            var iniBytes=Encoding.Unicode.GetPreamble().Concat(Encoding.Unicode.GetBytes(Ini(config,settingsMap))).ToArray();
            plan.Changes.Add(new Change { Root="game",Name="dinput8.dll",Bytes=bundle.Files[isForge?bundle.Info.forgeNativeName:"dinput8.dll"] });
            plan.Changes.Add(new Change { Root="game",Name="sekirobridge.ini",Bytes=iniBytes });
            var mods=Under(plan.MC,"mods");
            if(Directory.Exists(mods)) foreach(var jar in Directory.GetFiles(mods,"*.jar")) {
                Under(plan.MC,"mods/"+Path.GetFileName(jar));
                string id="";
                try { using(var stream=File.OpenRead(jar)) using(var zip=new ZipArchive(stream,ZipArchiveMode.Read))id=ModId(zip); }
                catch(InvalidDataException) { throw new InvalidDataException("MC mods 中有损坏的 JAR："+Path.GetFileName(jar)); }
                var selectedJar=isForge?bundle.Info.forgeJarName:bundle.Info.jarName;
                if((Path.GetFileName(jar)==selectedJar && id!="sekirobridge") || (!isForge && Path.GetFileName(jar)==bundle.Info.apiName && id!="fabric-api"))
                    throw new InvalidDataException("目标文件名已被其它模组占用，未覆盖："+Path.GetFileName(jar));
                if(isForge && id=="fabric-api")throw new InvalidOperationException("Forge 实例中检测到 Fabric API。请将它移回 Fabric 实例，避免混用加载器。");
                if((id=="sekirobridge" || (!isForge && id=="fabric-api")) && Path.GetFileName(jar)!=selectedJar && (isForge || Path.GetFileName(jar)!=bundle.Info.apiName))
                    plan.Changes.Add(new Change { Root="mc",Name="mods/"+Path.GetFileName(jar),Bytes=null });
            }
            var jarName=isForge?bundle.Info.forgeJarName:bundle.Info.jarName;
            plan.Changes.Add(new Change { Root="mc",Name="mods/"+jarName,Bytes=bundle.Files[jarName] });
            if(!isForge)plan.Changes.Add(new Change { Root="mc",Name="mods/"+bundle.Info.apiName,Bytes=bundle.Files[bundle.Info.apiName] });
            var properties=Under(plan.MC,"sekirobridge/bridge.properties"); var latin=Encoding.GetEncoding(28591);
            var propertiesText=File.Exists(properties)?File.ReadAllText(properties,latin):"# SekiroCraft bridge settings\r\n";
            plan.Changes.Add(new Change { Root="mc",Name="sekirobridge/bridge.properties",Bytes=latin.GetBytes(SetProperties(propertiesText,new Dictionary<string,string> { {"channel","default"},{"gui_trace","false"} })) });
            plan.Changes.Add(new Change { Root="mc",Name="SekiroCraft-简易教程.txt",Bytes=bundle.Files["简易教程.txt"] });
            plan.Notes.Add(isForge?"安装已测试的 Forge preview.6 JAR 与配套只狼 DLL；不安装 Fabric API，不包含血条位置切换。":"安装原有已发布 Fabric 0.1.0 成对 DLL/JAR 与 Fabric API 0.92.12；Fabric 模组本次不升级。");
            plan.Notes.Add(settings.AutoBoss?"已选 Boss 血量耗尽扣红点（临时方案，完整 MC 忍杀尚未实现）。":"未启用自动扣红点；Boss 可能无法通过 MC 攻击推进阶段。");
            plan.Notes.Add("已知问题：只狼攻击命中时，MC 人物/方块仍可能闪烁，后续继续修复。");
            return plan;
        }
        static string ReadHead(string path,int size) {
            using(var file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite)) { var bytes=new byte[(int)Math.Min(file.Length,size)]; var count=file.Read(bytes,0,bytes.Length); return Encoding.UTF8.GetString(bytes,0,count); }
        }
        public void Install(Settings settings) {
            var plan=Check(settings); RequireClosed(); var managed=Under(plan.Game,ManagedFolder);
            var recordFile=Under(managed,"installation.json");
            var record=plan.Previous!=null && plan.Previous.active?plan.Previous:new InstallRecord { sekiro=plan.Game,minecraft=plan.MC,launcher=plan.Launcher };
            if(record.launcher==null) record.launcher=plan.Launcher;
            var run="transactions/"+DateTime.UtcNow.ToString("yyyyMMddHHmmssfff")+"-"+Guid.NewGuid().ToString("N"); var runRoot=Under(managed,run);
            Directory.CreateDirectory(runRoot); var before=new Dictionary<string,byte[]>(); var applied=new List<string>();
            try {
                before[recordFile]=File.Exists(recordFile)?File.ReadAllBytes(recordFile):null;
                foreach(var change in plan.Changes) {
                    var target=Resolve(record,change.Root,change.Name); before[target]=File.Exists(target)?File.ReadAllBytes(target):null;
                    var entry=record.files.FirstOrDefault(file=>file.root==change.Root && file.name==change.Name);
                    if(entry==null) {
                        var backup=run+"/original-"+record.files.Count+".bin";
                        entry=new OwnedFile { root=change.Root,name=change.Name,originalHash=before[target]==null?null:Bundle.Hash(before[target]),backup=before[target]==null?null:backup };
                        if(before[target]!=null) AtomicWrite(Under(managed,backup),before[target]); record.files.Add(entry);
                    }
                    entry.installedHash=change.Bytes==null?null:Bundle.Hash(change.Bytes);
                }
                // Back up Sekiro saves without altering them; MC worlds/options are never touched.
                if(Directory.Exists(SaveRoot)) foreach(var file in Directory.GetFiles(SaveRoot,"*.sl2*",SearchOption.AllDirectories)) {
                    var relative=Path.GetFullPath(file).Substring(Full(SaveRoot).Length+1); Under(SaveRoot,relative);
                    AtomicWrite(Under(runRoot,"sekiro-saves/"+relative),File.ReadAllBytes(file));
                }
                AtomicWrite(Under(runRoot,"before-installation.json"),before[recordFile]??Text("null"));
                Directory.CreateDirectory(plan.Runtime); RequireClosed();
                foreach(var change in plan.Changes) {
                    var target=Resolve(record,change.Root,change.Name);
                    if(!Equal(File.Exists(target)?Bundle.HashFile(target):null,before[target]==null?null:Bundle.Hash(before[target]))) throw new IOException("文件在检查后被改变，已停止："+target);
                    applied.Add(target);
                    if(change.Bytes==null) { if(File.Exists(target)) File.Delete(target); } else AtomicWrite(target,change.Bytes);
                    if(!Equal(File.Exists(target)?Bundle.HashFile(target):null,change.Bytes==null?null:Bundle.Hash(change.Bytes))) throw new IOException("安装后校验失败："+target);
                    Log("已安装并校验："+change.Name); AfterWrite(applied.Count);
                }
                record.active=true; record.loader=settings.Loader; record.version=bundle.Info.version; record.sourceCommit=bundle.Info.sourceCommit; record.installedUtc=DateTime.UtcNow.ToString("o");
                AtomicWrite(recordFile,Text(Bundle.Json.Serialize(record)));
                Log("安装完成。备份保存在 "+managed+"；不用 MC 桥接时可用本程序停用或恢复。");
            } catch(Exception failure) {
                var failures=new List<string>();
                foreach(var target in applied.AsEnumerable().Reverse().Concat(new[]{recordFile})) try { if(before[target]==null) { if(File.Exists(target)) File.Delete(target); } else AtomicWrite(target,before[target]); } catch(Exception rollback) { failures.Add(target+": "+rollback.Message); }
                if(failures.Count>0) throw new IOException("安装失败，部分恢复未完成。保留备份并查看："+runRoot+"\r\n"+String.Join("\r\n",failures),failure);
                throw new IOException("安装失败，已恢复本轮修改前的文件："+failure.Message,failure);
            }
        }
        public void Disable(string game) {
            RequireClosed(); game=Full(game); var record=ReadRecord(game);
            if(record==null || !record.active) throw new InvalidOperationException("该目录没有本安装器管理的活动安装。");
            var path=Under(game,"sekirobridge.ini"); var original=File.ReadAllText(path);
            var bytes=Encoding.Unicode.GetPreamble().Concat(Encoding.Unicode.GetBytes(Ini(original,new Dictionary<string,string> { {"enabled","0"},{"native_hits","0"},{"auto_boss_phases","0"} }))).ToArray();
            var previous=File.ReadAllBytes(path); var entry=record.files.Single(file=>file.root=="game" && file.name=="sekirobridge.ini"); entry.installedHash=Bundle.Hash(bytes);
            try { AtomicWrite(path,bytes); AtomicWrite(Under(game,ManagedFolder+"/installation.json"),Text(Bundle.Json.Serialize(record))); }
            catch { AtomicWrite(path,previous); throw; }
            Log("已停用只狼端桥接与自动扣红点；下次启动只狼生效。重新安装可启用。");
        }
        public void Restore(string game) {
            RequireClosed(); game=Full(game); var record=ReadRecord(game);
            if(record==null || !record.active) throw new InvalidOperationException("没有需要恢复的活动安装。");
            var originals=new Dictionary<string,byte[]>(); var before=new Dictionary<string,byte[]>();
            foreach(var entry in record.files) {
                var target=Resolve(record,entry.root,entry.name); var current=File.Exists(target)?File.ReadAllBytes(target):null;
                var original=entry.backup==null?null:File.ReadAllBytes(Under(game,ManagedFolder+"/"+entry.backup));
                if(!Equal(original==null?null:Bundle.Hash(original),entry.originalHash)) throw new IOException("原始备份校验失败，未进行恢复。");
                if(entry.root=="launcher" && entry.name=="launcher_profiles.json") {
                    var now=Bundle.Json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(current??new byte[0]));
                    var old=Bundle.Json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(original??new byte[0]));
                    var profiles=now["profiles"] as Dictionary<string,object>; var oldProfiles=old["profiles"] as Dictionary<string,object>;
                    var owned=profiles.ContainsKey(ProfileId)?profiles[ProfileId] as Dictionary<string,object>:null;
                    if(owned!=null && (!owned.ContainsKey("lastVersionId") || Convert.ToString(owned["lastVersionId"])!=ProfileId)) throw new IOException("创建的启动器配置已改为其它版本，未移除。");
                    if(oldProfiles.ContainsKey(ProfileId)) profiles[ProfileId]=oldProfiles[ProfileId]; else profiles.Remove(ProfileId);
                    originals[target]=Text(Bundle.Json.Serialize(now)); before[target]=current; continue;
                }
                if(!Equal(current==null?null:Bundle.Hash(current),entry.installedHash)) {
                    if(entry.root=="mc" && entry.name=="sekirobridge/bridge.properties") { Log("保留已由 MC 更新的界面偏好："+entry.name); continue; }
                    throw new IOException("安装后文件已被修改，未覆盖或删除："+target+"。请先备份并恢复该文件，再重试。");
                }
                originals[target]=original; before[target]=current;
            }
            var changed=new List<string>(); var state=Under(game,ManagedFolder+"/installation.json"); var stateBytes=File.ReadAllBytes(state);
            try {
                RequireClosed(); foreach(var item in originals) { changed.Add(item.Key); if(item.Value==null) { if(File.Exists(item.Key)) File.Delete(item.Key); } else AtomicWrite(item.Key,item.Value); }
                record.active=false; AtomicWrite(state,Text(Bundle.Json.Serialize(record))); Log("已还原首次安装前的文件。存档、MC 世界、其它模组与个人按键均保留；备份目录也保留。");
            } catch(Exception failure) {
                var errors=new List<string>(); foreach(var path in changed.AsEnumerable().Reverse()) try { if(before[path]==null) { if(File.Exists(path)) File.Delete(path); } else AtomicWrite(path,before[path]); } catch(Exception ex) { errors.Add(ex.Message); }
                try { AtomicWrite(state,stateBytes); } catch(Exception ex) { errors.Add(ex.Message); }
                if(errors.Count>0) throw new IOException("恢复失败且回滚未完成，请保留备份："+String.Join("; ",errors),failure); throw;
            }
        }
    }
}
