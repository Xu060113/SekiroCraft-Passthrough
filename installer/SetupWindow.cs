using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace SekiroCraftSetup {
    public sealed class SetupWindow:Form {
        readonly Bundle bundle;
        readonly Engine engine;
        readonly TextBox sekiro=new TextBox(), minecraft=new TextBox(), log=new TextBox();
        readonly ComboBox mode=new ComboBox(), grapple=new ComboBox(), loader=new ComboBox();
        readonly CheckBox boss=new CheckBox(), replace=new CheckBox();
        readonly List<Button> actions=new List<Button>();
        readonly Label result=new Label();
        readonly ProgressBar progress=new ProgressBar();
        bool working;
        public bool Preview;
        protected override bool ShowWithoutActivation { get { return Preview; } }
        public SetupWindow(Bundle data) {
            bundle=data; engine=new Engine(data); engine.Log=WriteLog;
            Text="SekiroCraft 中文安装程序"; Font=new Font("Microsoft YaHei UI",9F);
            AutoScaleMode=AutoScaleMode.Dpi; ClientSize=new Size(900,720); MinimumSize=new Size(860,680);
            StartPosition=FormStartPosition.CenterScreen; BackColor=Color.FromArgb(247,248,250);
            var panel=new TableLayoutPanel { Dock=DockStyle.Fill,Padding=new Padding(22),ColumnCount=1,RowCount=13 };
            for(int row=0;row<13;row++) panel.RowStyles.Add(new RowStyle(row==9?SizeType.Percent:SizeType.Absolute,row==9?100:row==0?52:row==1?46:row==2?50:row==3?56:row==4?42:row==5?42:row==6?36:row==7?36:row==8?44:row==10?26:row==11?38:34));
            Controls.Add(panel);
            panel.Controls.Add(new Label { Text="把 Minecraft 装进只狼",Dock=DockStyle.Fill,Font=new Font(Font.FontFamily,20F,FontStyle.Bold),TextAlign=ContentAlignment.MiddleLeft },0,0);
            panel.Controls.Add(new Label { Text="选择目录 → 检查 → 安装。无需 Git、编译工具或 PowerShell 命令。\r\n版本 "+data.Info.version+" · 受击闪烁仍待修复 · 不包含两款游戏和第三方玩法模组",Dock=DockStyle.Fill },0,1);
            panel.Controls.Add(PathRow("只狼目录",sekiro,"选择含 sekiro.exe 的文件夹"),0,2);
            panel.Controls.Add(PathRow("MC 游戏目录",minecraft,"从启动器打开所选加载器的 1.20.1 游戏目录（含 mods）；不要混装"),0,3);
            var loaderRow=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false };
            loaderRow.Controls.Add(new Label { Text="MC 加载器",AutoSize=true,Margin=new Padding(0,8,26,0) });
            loader.DropDownStyle=ComboBoxStyle.DropDownList;loader.Width=610;
            loader.Items.AddRange(new object[]{"Forge · MC 1.20.1 / 47.4.26 已测试 · preview.6（无血条位置切换）","Fabric · MC 1.20.1 / Loader 0.19.5 · 保留已发布 0.1.0"});
            loaderRow.Controls.Add(loader);panel.Controls.Add(loaderRow,0,4);
            var modeRow=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false };
            modeRow.Controls.Add(new Label { Text="MC 安装方式",AutoSize=true,Margin=new Padding(0,8,14,0) });
            mode.DropDownStyle=ComboBoxStyle.DropDownList; mode.Width=520;
            loader.SelectedIndexChanged+=delegate { var fresh=mode.SelectedIndex==1;mode.Items.Clear();if(loader.SelectedIndex==0)mode.Items.Add("已有 Minecraft 1.20.1 Forge 实例（先在启动器准备并运行一次）");else mode.Items.AddRange(new object[]{"已有 Minecraft 1.20.1 Fabric 实例（含 mods 文件夹）","官方启动器：新建独立的 1.20.1 Fabric 0.19.5 实例"});mode.SelectedIndex=loader.SelectedIndex==1&&fresh?1:0; };
            loader.SelectedIndex=0;modeRow.Controls.Add(mode);panel.Controls.Add(modeRow,0,5);
            var keyRow=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false };
            keyRow.Controls.Add(new Label { Text="只狼钩索键",AutoSize=true,Margin=new Padding(0,6,16,0) });
            grapple.DropDownStyle=ComboBoxStyle.DropDownList; grapple.Width=60; for(char key='A';key<='Z';key++) grapple.Items.Add(key.ToString()); grapple.SelectedItem="M"; keyRow.Controls.Add(grapple);
            boss.Text="启用 Boss 血量耗尽自动扣红点（建议，完整 MC 忍杀尚未实现）"; boss.AutoSize=true; boss.Checked=true; boss.Margin=new Padding(20,5,0,0); keyRow.Controls.Add(boss); panel.Controls.Add(keyRow,0,6);
            replace.Text="已有只狼加载器/桥接时：备份并替换（暂停其它只狼模组；不要混用旧安装脚本）"; replace.AutoSize=true; panel.Controls.Add(replace,0,7);
            var buttons=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false };
            AddButton(buttons,"检查目录",118);
            AddButton(buttons,"一键安装 / 更新",170);
            AddButton(buttons,"停用桥接",118);
            AddButton(buttons,"恢复安装前状态",158);
            panel.Controls.Add(buttons,0,8);
            log.Multiline=true; log.ReadOnly=true; log.ScrollBars=ScrollBars.Vertical; log.Dock=DockStyle.Fill; log.BackColor=Color.White; panel.Controls.Add(log,0,9);
            progress.Dock=DockStyle.Fill; progress.Visible=false; progress.Style=ProgressBarStyle.Marquee; panel.Controls.Add(progress,0,10);
            var extra=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false };
            var tutorial=new Button { Text="记事本教程",AutoSize=true }; tutorial.Click+=delegate { OpenTutorial(); }; extra.Controls.Add(tutorial);
            var directory=new Button { Text="打开 MC 目录",AutoSize=true }; directory.Click+=delegate { try { var root=Engine.Full(minecraft.Text); if(mode.SelectedIndex==1) root=Path.Combine(root,"versions",Engine.ProfileId); if(!Directory.Exists(root)) throw new IOException("目录尚未创建。"); Process.Start(new ProcessStartInfo(root){UseShellExecute=true}); } catch(Exception ex) { ShowError(ex); } }; extra.Controls.Add(directory);
            var admin=new Button { Text="权限不足时以管理员重试",AutoSize=true }; admin.Click+=delegate { if(working) return; try { Process.Start(new ProcessStartInfo(Application.ExecutablePath,"--sekiro "+Quote(sekiro.Text)+" --mc "+Quote(minecraft.Text)+" --loader "+(loader.SelectedIndex==0?"Forge":"Fabric")+" --new "+(mode.SelectedIndex==1?"1":"0")+" --grapple "+Quote(Convert.ToString(grapple.SelectedItem))+" --boss "+(boss.Checked?"1":"0")+" --replace "+(replace.Checked?"1":"0")) { UseShellExecute=true,Verb="runas" }); Close(); } catch(Exception ex) { ShowError(ex); } }; extra.Controls.Add(admin);
            panel.Controls.Add(extra,0,11);
            result.Dock=DockStyle.Fill; result.Text="安装前退出游戏与启动器；切换加载器前先恢复旧安装。"; panel.Controls.Add(result,0,12);
            sekiro.Text=FindSekiro(); minecraft.Text=FindMinecraft("Forge");
            WriteLog("已载入并校验内置组件。其它模组、options.txt 与 MC 世界不被改写；自动保留旧文件和只狼存档备份。");
            FormClosing+=delegate(object sender,FormClosingEventArgs args) { if(working) { args.Cancel=true; MessageBox.Show(this,"正在备份或写入文件，请等待完成。","请稍候"); } };
        }
        Settings Settings() { return new Settings { Sekiro=sekiro.Text,Minecraft=minecraft.Text,Loader=loader.SelectedIndex==0?"Forge":"Fabric",Grapple=Convert.ToString(grapple.SelectedItem),NewOfficialProfile=mode.SelectedIndex==1,AutoBoss=boss.Checked,ReplaceOtherLoader=replace.Checked }; }
        Control PathRow(string title,TextBox input,string hint) {
            var row=new TableLayoutPanel { Dock=DockStyle.Fill,ColumnCount=3,RowCount=2 };
            row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,104)); row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100)); row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,88));
            row.Controls.Add(new Label { Text=title,AutoSize=true,Margin=new Padding(0,6,0,0) },0,0); input.Dock=DockStyle.Fill; row.Controls.Add(input,1,0);
            var browse=new Button { Text="选择…",Dock=DockStyle.Fill }; browse.Click+=delegate { using(var dialog=new FolderBrowserDialog { Description=hint,SelectedPath=Directory.Exists(input.Text)?input.Text:"",ShowNewFolderButton=false }) if(dialog.ShowDialog(this)==DialogResult.OK) { var selected=dialog.SelectedPath; if(input==minecraft && String.Equals(Path.GetFileName(selected),"mods",StringComparison.OrdinalIgnoreCase)) selected=Path.GetDirectoryName(selected); input.Text=selected; } }; row.Controls.Add(browse,2,0);
            var note=new Label { Text=hint,AutoSize=true,ForeColor=Color.DimGray }; row.Controls.Add(note,1,1); row.SetColumnSpan(note,2); return row;
        }
        void AddButton(FlowLayoutPanel parent,string label,int width) {
            var button=new Button { Text=label,Width=width,Height=32 }; parent.Controls.Add(button); actions.Add(button);
            button.Click+=async delegate {
                if(working) return;
                // Capture UI values before moving work to the background thread.
                var selected=Settings(); var game=sekiro.Text;
                if(label=="恢复安装前状态" && MessageBox.Show(this,"将还原本安装器首次安装前的文件。新 MC 世界和个人按键保留。\r\n如果安装前已有其它模组，将恢复它们。继续？","恢复确认",MessageBoxButtons.OKCancel,MessageBoxIcon.Question)!=DialogResult.OK) return;
                working=true; foreach(var item in actions) item.Enabled=false; sekiro.ReadOnly=minecraft.ReadOnly=true; loader.Enabled=mode.Enabled=grapple.Enabled=boss.Enabled=replace.Enabled=false; progress.Visible=true;
                try {
                    await Task.Run(delegate {
                        if(label=="一键安装 / 更新") engine.Install(selected);
                        else if(label=="停用桥接") engine.Disable(game);
                        else if(label=="恢复安装前状态") engine.Restore(game);
                        else { var plan=engine.Check(selected); foreach(var note in plan.Notes) WriteLog(note); WriteLog("检查通过。实际 MC 游戏目录："+plan.MC); }
                    });
                    result.Text=label=="一键安装 / 更新"?"安装成功。点击“记事本教程”，按步骤启动 MC 和只狼。":"操作完成，请查看上面的记录。";
                    if(label=="一键安装 / 更新") MessageBox.Show(this,"安装成功！\r\n请打开记事本教程，按步骤启动游戏和桥接。\r\n只狼中的钩索键需与你选择的字母一致。","SekiroCraft",MessageBoxButtons.OK,MessageBoxIcon.Information);
                } catch(Exception ex) { result.Text="未完成，请按提示处理后重试。"; ShowError(ex); }
                finally { working=false; foreach(var item in actions) item.Enabled=true; sekiro.ReadOnly=minecraft.ReadOnly=false; loader.Enabled=mode.Enabled=grapple.Enabled=boss.Enabled=replace.Enabled=true; progress.Visible=false; }
            };
        }
        void WriteLog(string text) { if(InvokeRequired) { BeginInvoke(new Action<string>(WriteLog),text); return; } log.AppendText(DateTime.Now.ToString("HH:mm:ss")+"  "+text+Environment.NewLine); }
        void ShowError(Exception ex) { WriteLog(ex.Message); MessageBox.Show(this,ex.Message,"请处理后重试",MessageBoxButtons.OK,MessageBoxIcon.Warning); }
        void OpenTutorial() { try { var folder=Path.Combine(Path.GetTempPath(),"SekiroCraft-Setup",Guid.NewGuid().ToString("N")); Directory.CreateDirectory(folder); var path=Path.Combine(folder,"简易教程.txt"); File.WriteAllBytes(path,bundle.Files["简易教程.txt"]); Process.Start(new ProcessStartInfo("notepad.exe",Quote(path)) { UseShellExecute=true }); } catch(Exception ex) { ShowError(ex); } }
        static string Quote(string value) { var text=Regex.Replace(value??"","(\\\\*)\"","$1$1\\\""); text=Regex.Replace(text,"(\\\\+)$","$1$1"); return "\""+text+"\""; }
        public void SetPaths(string game,string mc,bool fresh) { if(game!=null) sekiro.Text=game; if(mc!=null) minecraft.Text=mc; mode.SelectedIndex=fresh?1:0; }
        public void SetLoader(string choice){loader.SelectedIndex=choice=="Fabric"?1:0;}
        public void SetChoices(string key,bool? automatic,bool? overwrite) { if(key!=null && grapple.Items.Contains(key.ToUpperInvariant())) grapple.SelectedItem=key.ToUpperInvariant(); if(automatic.HasValue) boss.Checked=automatic.Value; if(overwrite.HasValue) replace.Checked=overwrite.Value; }
        static string FindSekiro() {
            var roots=new List<string>();
            try { using(var key=Registry.CurrentUser.OpenSubKey("Software\\Valve\\Steam")) if(key!=null) roots.Add(Convert.ToString(key.GetValue("SteamPath"))); } catch {}
            foreach(var drive in DriveInfo.GetDrives().Where(item=>item.DriveType==DriveType.Fixed)) foreach(var folder in new[]{"Steam","SteamLibrary","Program Files (x86)/Steam"}) roots.Add(Path.Combine(drive.RootDirectory.FullName,folder));
            foreach(var root in roots.ToArray()) try { var file=Path.Combine(root,"steamapps/libraryfolders.vdf"); if(File.Exists(file)) foreach(Match match in Regex.Matches(File.ReadAllText(file),"\"path\"\\s*\"([^\"]+)\"")) roots.Add(match.Groups[1].Value.Replace("\\\\","\\")); } catch {}
            foreach(var root in roots.Distinct(StringComparer.OrdinalIgnoreCase)) { var path=Path.Combine(root,"steamapps/common/Sekiro"); if(File.Exists(Path.Combine(path,"sekiro.exe"))) return path; }
            return "";
        }
        static string FindMinecraft(string choice) {
            var roots=new List<string> { Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),".minecraft") };
            foreach(var drive in DriveInfo.GetDrives().Where(item=>item.DriveType==DriveType.Fixed)) roots.Add(Path.Combine(drive.RootDirectory.FullName,".minecraft"));
            foreach(var root in roots) try { var versions=Path.Combine(root,"versions"); if(Directory.Exists(versions)) foreach(var directory in Directory.GetDirectories(versions).OrderByDescending(path=>Directory.GetLastWriteTime(path))) if((choice=="Forge"?Engine.IsForgeDirectory(directory):Engine.IsFabricDirectory(directory))&&Directory.Exists(Path.Combine(directory,"mods"))) return directory; } catch {}
            return roots.FirstOrDefault(Directory.Exists)??"";
        }
    }
    static class Program {
        [STAThread] static int Main(string[] args) {
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            try {
                Bundle data; using(var stream=typeof(Program).Assembly.GetManifestResourceStream("SekiroCraft.payload.zip")) { if(stream==null) throw new IOException("内置安装组件缺失。"); data=Bundle.Load(stream); }
                using(var window=new SetupWindow(data)) {
                    string game=null,mc=null,render=null,key=null,choice="Forge"; bool fresh=false; bool? automatic=null,overwrite=null;
                    for(int i=0;i+1<args.Length;i+=2) { if(args[i]=="--sekiro") game=args[i+1]; else if(args[i]=="--mc") mc=args[i+1]; else if(args[i]=="--new") fresh=args[i+1]=="1"; else if(args[i]=="--render") render=args[i+1]; else if(args[i]=="--grapple") key=args[i+1]; else if(args[i]=="--boss") automatic=args[i+1]=="1"; else if(args[i]=="--replace") overwrite=args[i+1]=="1"; }
                    for(int i=0;i+1<args.Length;i+=2)if(args[i]=="--loader")choice=args[i+1];
                    window.SetLoader(choice);
                    window.SetPaths(game,mc,fresh && choice=="Fabric");
                    window.SetChoices(key,automatic,overwrite);
                    if(render!=null) { window.Preview=true; window.ShowInTaskbar=false; window.Opacity=0; window.StartPosition=FormStartPosition.Manual; window.Location=new Point(-10000,-10000); window.Show(); Application.DoEvents(); using(var bitmap=new Bitmap(window.Width,window.Height)) { window.DrawToBitmap(bitmap,new Rectangle(Point.Empty,bitmap.Size)); bitmap.Save(render); } window.Hide(); return 0; }
                    Application.Run(window); return 0;
                }
            } catch(Exception ex) { MessageBox.Show("安装程序无法启动："+ex.Message,"SekiroCraft",MessageBoxButtons.OK,MessageBoxIcon.Error); return 1; }
        }
    }
}
