using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using SekiroCraftSetup;

static class SetupEngineTests {
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateHardLink(string target,string existing,IntPtr reserved);
    static int checks;
    static Bundle bundle;
    static string root,exe;
    static void Check(bool value,string message) { checks++; if(!value) throw new Exception(message); }
    static void Refuses(Action action,string message) { bool rejected=false; try { action(); } catch { rejected=true; } Check(rejected,message); }
    static void Write(string path,string content) { Directory.CreateDirectory(Path.GetDirectoryName(path)); File.WriteAllText(path,content,new UTF8Encoding(false)); }
    static string Hash(string path) { return File.Exists(path)?Bundle.HashFile(path):null; }
    static void Jar(string path,string id) {
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        using(var stream=File.Create(path)) using(var zip=new ZipArchive(stream,ZipArchiveMode.Create)) using(var writer=new StreamWriter(zip.CreateEntry("fabric.mod.json").Open())) writer.Write("{\"id\":\""+id+"\",\"version\":\"old\"}");
    }
    static void ForgeJar(string path,string id) {
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        using(var stream=File.Create(path)) using(var zip=new ZipArchive(stream,ZipArchiveMode.Create)) using(var writer=new StreamWriter(zip.CreateEntry("META-INF/mods.toml").Open())) writer.Write("modLoader=\"javafml\"\n[[mods]]\nmodId=\""+id+"\"\nversion=\"old\"\n");
    }
    static Settings Fixture(string name) {
        var directory=Engine.Under(root,name+"-"+Guid.NewGuid().ToString("N")); var game=Path.Combine(directory,"只狼游戏"); var mc=Path.Combine(directory,"MC 中文 空格");
        Directory.CreateDirectory(game); Directory.CreateDirectory(Path.Combine(mc,"mods"));
        if(!CreateHardLink(Path.Combine(game,"sekiro.exe"),exe,IntPtr.Zero)) File.Copy(exe,Path.Combine(game,"sekiro.exe"));
        Write(Path.Combine(game,"dinput8.dll"),"old loader, never loaded"); Write(Path.Combine(game,"sekirobridge.ini"),"[OtherMod]\r\noption=old\r\n");
        Write(Path.Combine(game,"sekirocraft.ini"),"personal original settings");
        Write(Path.Combine(mc,"fabric.json"),"{\"inheritsFrom\":\"1.20.1\",\"libraries\":[{\"name\":\"net.fabricmc:fabric-loader:0.19.5\"}]} ");
        Jar(Path.Combine(mc,"mods","旧 API.jar"),"fabric-api"); Jar(Path.Combine(mc,"mods","旧桥接.jar"),"sekirobridge"); Jar(Path.Combine(mc,"mods","其它模组.jar"),"example");
        Write(Path.Combine(mc,"options.txt"),"key_key.reload:key.keyboard.r"); Write(Path.Combine(mc,"saves","私人世界","level.dat"),"world stays untouched");
        Write(Path.Combine(mc,"sekirobridge","bridge.properties"),"channel=custom\r\nshow_posture_hud=false\r\nunknown_setting=keep\r\n");
        return new Settings { Sekiro=game,Minecraft=mc,ReplaceOtherLoader=true };
    }
    static Settings ForgeFixture(string name) {
        var settings=Fixture(name);settings.Loader="Forge";
        File.Delete(Engine.Under(settings.Minecraft,"fabric.json"));
        File.Delete(Engine.Under(settings.Minecraft,"mods/旧 API.jar"));
        Write(Engine.Under(settings.Minecraft,"forge.json"),"{\"inheritsFrom\":\"1.20.1\",\"libraries\":[{\"name\":\"net.minecraftforge:forge:1.20.1-47.4.26\"}]}");
        ForgeJar(Engine.Under(settings.Minecraft,"mods/旧 Forge 桥接.jar"),"sekirobridge");
        ForgeJar(Engine.Under(settings.Minecraft,"mods/其他 Forge 模组.jar"),"example_forge");
        return settings;
    }
    // All writes remain in Engine.Under(root, fixture); running games are never
    // touched. This injection avoids blocking offline builds on unrelated Java.
    static Engine TestEngine(Settings settings) { return new Engine(bundle) { SaveRoot=Engine.Under(root,"sample-saves"),Log=delegate {},RequireClosed=delegate {} }; }
    static int Main(string[] args) {
        try {
            using(var stream=File.OpenRead(args[0])) bundle=Bundle.Load(stream);
            root=Path.GetFullPath(args[1]); Directory.CreateDirectory(root); exe=Path.Combine(root,"supported-sekiro-readonly-fixture.exe");
            if(!File.Exists(exe) || Hash(exe)!=Bundle.SupportedExe) File.Copy(args[2],exe,true);
            Check(Hash(exe)==Bundle.SupportedExe,"Fixture fingerprint");
            Write(Engine.Under(root,"sample-saves/player/S0000.sl2"),"save stays untouched");
            Refuses(delegate { Engine.Under(root,"../escape.dll"); },"Reject traversal");
            Refuses(delegate { Engine.Under(root,"C:/escape.dll"); },"Reject absolute path");
            var settings=Fixture("安装更新恢复"); var engine=TestEngine(settings);
            var game=settings.Sekiro; var mc=settings.Minecraft;
            var original=Hash(Path.Combine(game,"dinput8.dll")); var config=Hash(Path.Combine(game,"sekirobridge.ini")); var other=Hash(Path.Combine(mc,"mods","其它模组.jar")); var options=Hash(Path.Combine(mc,"options.txt")); var world=Hash(Path.Combine(mc,"saves","私人世界","level.dat"));
            var deny=new Settings { Sekiro=game,Minecraft=mc };
            Refuses(delegate { engine.Check(deny); },"Foreign loader requires explicit choice");
            engine.Check(settings); Check(!Directory.Exists(Path.Combine(game,Engine.ManagedFolder)),"Check does not mutate");
            engine.Install(settings);
            var recordPath=Engine.Under(game,Engine.ManagedFolder+"/installation.json");
            var legacy=Bundle.Json.Deserialize<InstallRecord>(File.ReadAllText(recordPath)); legacy.loader=null;Write(recordPath,Bundle.Json.Serialize(legacy));
            engine.Check(settings); Check(true,"Legacy Fabric record with no loader remains valid");
            Check(Hash(Path.Combine(game,"dinput8.dll"))==Bundle.Hash(bundle.Files["dinput8.dll"]),"Install paired DLL");
            Check(Hash(Path.Combine(mc,"mods",bundle.Info.jarName))==Bundle.Hash(bundle.Files[bundle.Info.jarName]),"Install paired JAR");
            Check(Hash(Path.Combine(mc,"mods",bundle.Info.apiName))==Bundle.Hash(bundle.Files[bundle.Info.apiName]),"Install pinned API");
            Check(!File.Exists(Path.Combine(mc,"mods","旧 API.jar")) && !File.Exists(Path.Combine(mc,"mods","旧桥接.jar")),"Duplicate IDs removed from mods");
            Check(File.ReadAllText(Path.Combine(game,"sekirobridge.ini")).Contains("auto_boss_phases=1"),"Recommended Boss mode");
            Check(File.ReadAllText(Path.Combine(game,"sekirobridge.ini")).Contains("grapple_key=M"),"Grapple M");
            Check(File.ReadAllText(Path.Combine(mc,"sekirobridge","bridge.properties")).Contains("unknown_setting=keep"),"Keep unknown preferences");
            engine.Disable(game); Check(File.ReadAllText(Path.Combine(game,"sekirobridge.ini")).Contains("enabled=0"),"Disable without deleting backups");
            settings.Grapple="N"; settings.AutoBoss=false; engine.Install(settings);
            var ini=File.ReadAllText(Path.Combine(game,"sekirobridge.ini")); Check(ini.Contains("grapple_key=N") && ini.Contains("auto_boss_phases=0") && ini.Contains("enabled=1"),"Update and reenable");
            Write(Path.Combine(game,"dinput8.dll"),"installed by another mod afterward");
            Refuses(delegate { engine.Restore(game); },"Refuse modified binary restore");
            File.WriteAllBytes(Path.Combine(game,"dinput8.dll"),bundle.Files["dinput8.dll"]);
            Write(Path.Combine(mc,"sekirobridge","bridge.properties"),"channel=default\r\nshow_posture_hud=true\r\n");
            engine.Restore(game);
            Check(Hash(Path.Combine(game,"dinput8.dll"))==original && Hash(Path.Combine(game,"sekirobridge.ini"))==config,"Exact original native restore across update");
            Check(File.Exists(Path.Combine(mc,"mods","旧 API.jar")) && File.Exists(Path.Combine(mc,"mods","旧桥接.jar")),"Restore duplicate old versions");
            Check(!File.Exists(Path.Combine(mc,"mods",bundle.Info.jarName)) && !File.Exists(Path.Combine(mc,"mods",bundle.Info.apiName)),"Remove owned new JARs");
            Check(File.ReadAllText(Path.Combine(mc,"sekirobridge","bridge.properties")).Contains("show_posture_hud=true"),"Keep normal runtime HUD changes");
            Check(Hash(Path.Combine(mc,"mods","其它模组.jar"))==other && Hash(Path.Combine(mc,"options.txt"))==options && Hash(Path.Combine(mc,"saves","私人世界","level.dat"))==world,"Keep unrelated mods/options/world");
            Check(File.ReadAllText(Path.Combine(game,"sekirocraft.ini"))=="personal original settings","Keep Original project config");
            Check(File.ReadAllText(Engine.Under(root,"sample-saves/player/S0000.sl2"))=="save stays untouched","Native save unchanged");
            var fault=Fixture("失败恢复"); var faultEngine=TestEngine(fault); var faultDll=Hash(Path.Combine(fault.Sekiro,"dinput8.dll"));
            faultEngine.AfterWrite=delegate(int count) { if(count==4) throw new IOException("simulated write failure after partial installation"); };
            Refuses(delegate { faultEngine.Install(fault); },"Injected partial install failure");
            Check(Hash(Path.Combine(fault.Sekiro,"dinput8.dll"))==faultDll && File.Exists(Path.Combine(fault.Minecraft,"mods","旧 API.jar")) && File.Exists(Path.Combine(fault.Minecraft,"mods","旧桥接.jar")),"Failure rolls both ends back");
            Check(!File.Exists(Path.Combine(fault.Sekiro,Engine.ManagedFolder,"installation.json")),"Failure leaves no active ownership record");
            var wrong=Fixture("错误版本"); Write(Path.Combine(wrong.Minecraft,"fabric.json"),"{\"inheritsFrom\":\"1.20.2\",\"assetIndex\":{\"id\":\"1.20\"},\"libraries\":[{\"name\":\"net.fabricmc:fabric-loader:0.19.5\"}]}");
            Refuses(delegate { TestEngine(wrong).Check(wrong); },"Reject shared assetIndex from wrong MC version");
            var fresh=Fixture("官方实例"); var launcher=Engine.Under(root,"官方 .minecraft-"+Guid.NewGuid().ToString("N"));
            Write(Path.Combine(launcher,"versions","1.20.1","1.20.1.json"),"{\"id\":\"1.20.1\"}"); Write(Path.Combine(launcher,"versions","1.20.1","1.20.1.jar"),"launcher fixture, never launched");
            Write(Path.Combine(launcher,"launcher_profiles.json"),"{\"profiles\":{\"personal\":{\"name\":\"keep\",\"lastVersionId\":\"1.20.1\"}},\"selectedUser\":\"unchanged\"}");
            fresh.Minecraft=launcher; fresh.NewOfficialProfile=true; var freshEngine=TestEngine(fresh); freshEngine.Install(fresh);
            var launcherData=Bundle.Json.Deserialize<Dictionary<string,object>>(File.ReadAllText(Path.Combine(launcher,"launcher_profiles.json")));
            var profiles=(Dictionary<string,object>)launcherData["profiles"]; Check(profiles.ContainsKey(Engine.ProfileId)&&profiles.ContainsKey("personal"),"Add isolated official profile");
            profiles["new-personal-profile"]=new Dictionary<string,object> { {"name","created after install"} }; Write(Path.Combine(launcher,"launcher_profiles.json"),Bundle.Json.Serialize(launcherData));
            freshEngine.Restore(fresh.Sekiro);
            launcherData=Bundle.Json.Deserialize<Dictionary<string,object>>(File.ReadAllText(Path.Combine(launcher,"launcher_profiles.json"))); profiles=(Dictionary<string,object>)launcherData["profiles"];
            Check(!profiles.ContainsKey(Engine.ProfileId) && profiles.ContainsKey("personal") && profiles.ContainsKey("new-personal-profile"),"Restore only managed launcher profile, preserve unrelated new profiles");
            Check(Convert.ToString(launcherData["selectedUser"])=="unchanged","Launcher user field preserved");
            var forge=ForgeFixture("Forge 双端安装"); var forgeEngine=TestEngine(forge);
            var forgeOriginal=Hash(Path.Combine(forge.Sekiro,"dinput8.dll"));var unrelated=Hash(Engine.Under(forge.Minecraft,"mods/其他 Forge 模组.jar"));
            forgeEngine.Check(forge);forgeEngine.Install(forge);
            Check(Hash(Path.Combine(forge.Sekiro,"dinput8.dll"))==Bundle.Hash(bundle.Files[bundle.Info.forgeNativeName]),"Forge gets its exact paired native DLL");
            Check(Hash(Engine.Under(forge.Minecraft,"mods/"+bundle.Info.forgeJarName))==Bundle.Hash(bundle.Files[bundle.Info.forgeJarName]),"Forge gets tested preview.6 JAR");
            Check(!File.Exists(Engine.Under(forge.Minecraft,"mods/"+bundle.Info.jarName)) && !File.Exists(Engine.Under(forge.Minecraft,"mods/"+bundle.Info.apiName)),"Forge never installs Fabric bridge or API");
            Check(!File.Exists(Engine.Under(forge.Minecraft,"mods/旧桥接.jar")) && !File.Exists(Engine.Under(forge.Minecraft,"mods/旧 Forge 桥接.jar")),"Remove duplicate bridge IDs across loaders");
            Check(Hash(Engine.Under(forge.Minecraft,"mods/其他 Forge 模组.jar"))==unrelated,"Keep third-party Forge JAR");
            var forgeRecord=Bundle.Json.Deserialize<InstallRecord>(File.ReadAllText(Engine.Under(forge.Sekiro,Engine.ManagedFolder+"/installation.json")));
            Check(forgeRecord.loader=="Forge","Persist active loader ownership");
            var cross=new Settings{Sekiro=forge.Sekiro,Minecraft=mc,Loader="Fabric",ReplaceOtherLoader=true};
            Refuses(delegate {forgeEngine.Check(cross);},"Block active loader switch without restore");
            forgeEngine.Restore(forge.Sekiro);
            Check(Hash(Path.Combine(forge.Sekiro,"dinput8.dll"))==forgeOriginal && File.Exists(Engine.Under(forge.Minecraft,"mods/旧 Forge 桥接.jar")),"Restore Forge original native and JAR backups");
            Check(!File.Exists(Engine.Under(forge.Minecraft,"mods/"+bundle.Info.forgeJarName)),"Remove owned Forge JAR on restore");
            forgeEngine.Install(cross); Check(Hash(Path.Combine(forge.Sekiro,"dinput8.dll"))==Bundle.Hash(bundle.Files["dinput8.dll"]),"Loader switch after restore uses frozen Fabric DLL");forgeEngine.Restore(forge.Sekiro);
            var forgeFault=ForgeFixture("Forge 回滚");var failing=TestEngine(forgeFault);var originalForgeDll=Hash(Path.Combine(forgeFault.Sekiro,"dinput8.dll"));
            failing.AfterWrite=delegate(int count){if(count==4)throw new IOException("Forge partial write");};
            Refuses(delegate {failing.Install(forgeFault);},"Forge partial failure injected");
            Check(Hash(Path.Combine(forgeFault.Sekiro,"dinput8.dll"))==originalForgeDll && File.Exists(Engine.Under(forgeFault.Minecraft,"mods/旧 Forge 桥接.jar")),"Forge failure rolls both ends back");
            var badForge=ForgeFixture("Forge 版本识别");var badEngine=TestEngine(badForge);
            foreach(var version in new[]{"47.3.0","48.0.0"}) {
                Write(Engine.Under(badForge.Minecraft,"forge.json"),"{\"inheritsFrom\":\"1.20.1\",\"libraries\":[{\"name\":\"net.minecraftforge:forge:1.20.1-"+version+"\"}]}");
                Refuses(delegate {badEngine.Check(badForge);},"Reject Forge outside declared range "+version);
            }
            Write(Engine.Under(badForge.Minecraft,"forge.json"),"{\"inheritsFrom\":\"1.20.2\",\"libraries\":[{\"name\":\"net.minecraftforge:forge:1.20.1-47.4.26\"}]}");
            Refuses(delegate {badEngine.Check(badForge);},"Reject wrong Minecraft version for Forge");
            Write(Engine.Under(badForge.Minecraft,"forge.json"),"{\"inheritsFrom\":\"1.20.1\",\"libraries\":[{\"name\":\"net.neoforged:forge:1.20.1-47.4.26\"}]}");
            Refuses(delegate {badEngine.Check(badForge);},"Reject NeoForge");
            Write(Engine.Under(badForge.Minecraft,"forge.json"),"{\"clientVersion\":\"1.20.1\",\"libraries\":[{\"name\":\"net.minecraftforge:fmlloader:1.20.1-47.4.26\"}]}");
            badEngine.Check(badForge);Check(Engine.IsForgeDirectory(badForge.Minecraft),"Recognize launcher's merged Forge JSON for automatic directory discovery");
            File.Delete(Engine.Under(badForge.Minecraft,"forge.json"));
            Write(Engine.Under(badForge.Minecraft,"logs/latest.log"),"ModLauncher running: args [--fml.forgeVersion, 47.4.26, --fml.mcVersion, 1.20.1, --launchTarget, forgeclient]");
            badEngine.Check(badForge); Check(true,"Official Forge custom gameDir detected through startup log");
            Jar(Engine.Under(badForge.Minecraft,"mods/API 不该放在 Forge.jar"),"fabric-api");
            Refuses(delegate {badEngine.Check(badForge);},"Reject Fabric API in Forge without deleting it");
            Check(File.Exists(Engine.Under(badForge.Minecraft,"mods/API 不该放在 Forge.jar")),"Wrong-loader refusal leaves user files intact");
            var wrongLoader=Fixture("加载器不一致");wrongLoader.Loader="Forge";
            Refuses(delegate {TestEngine(wrongLoader).Check(wrongLoader);},"Fabric instance cannot receive Forge pair");
            var officialForge=ForgeFixture("官方 Forge 不自动新建");officialForge.NewOfficialProfile=true;
            Refuses(delegate {TestEngine(officialForge).Check(officialForge);},"Do not pretend to create Forge loader profile");
            Check(Bundle.Hash(bundle.Files[bundle.Info.forgeNativeName])=="00368AFC9073E42FE3EA2B73BC919F1789B9A51F6FFD5DD81EAD2A2AC074AE92","Exclude untested Boss HUD native DLL");
            Console.WriteLine("PASS "+checks+" isolated dual-loader installer checks: paired deployment, rollback, loader switching, restore, ownership, Chinese paths, preferences and launcher profile. No game launched."); return 0;
        } catch(Exception ex) { Console.Error.WriteLine(ex.ToString()); return 1; }
    }
}
