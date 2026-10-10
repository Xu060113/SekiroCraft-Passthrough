# 双加载器中文安装程序

2026-10-11 发布的安装器可选 **Forge / Fabric**。Forge 使用用户确认已游戏测试的 preview.6 配对文件；Fabric 冻结为 `v0.1.0-20261010` 的原 DLL/JAR/API，不重新构建。血条位置预览及切换键不打包。受击闪烁仍待修复。

## 运行与管理范围

- Windows 10/11 x64 与系统 .NET Framework，无需 Git、Python、JDK 或 PowerShell 命令运行 EXE。安装器不下载游戏、不登录账号、不安装第三方玩法模组。
- 只狼程序需匹配已适配 SHA256。Forge 实例为 MC 1.20.1 / Forge >=47.4.10,<48，推荐已实测 47.4.26 / Java 17；Fabric 建议 Loader 0.19.5、API 0.92.12+1.20.1 / Java 21，桥接最低 Java 17。
- 从启动器打开实际 `gameDir`，先运行一次再退出，识别 JSON 或日志。Forge 只能安装到已有实例，且不安装 Fabric API。Fabric 可用已有实例或官方启动器新建独立配置。
- 官方新建要求原版 1.20.1 JSON/JAR 与 `launcher_profiles.json` 已存在；使用固定的官方 Fabric 元数据，首次启动由官方启动器联网补库。第三方启动器需先准备实例。
- 游戏运行时拒绝安装；已有加载器需显式选择备份替换。切换加载器或 MC 目录前先恢复旧安装；旧脚本与本安装器不要交替更新。
- 仅管理记录拥有的部署文件，保留其它模组、options.txt、世界。只狼 `.sl2*` 存档仅复制备份，不改写且不自动恢复。备份在只狼目录 `.sekirocraft-installer`，恢复前不要删除或移动。

## 检查记录与实际验收

隔离测试使用忽略目录下的文件夹，校验游戏 EXE 的只读测试副本，永不执行游戏。覆盖 Fabric 与 Forge 成对部署、重复桥接清理、API 隔离、部分写入回滚、同版更新、停用与恢复、不同加载器切换保护、旧安装记录、中文空格路径、个人文件保留、启动日志识别及官方 Fabric 配置合并。窗口以程序自身 DrawToBitmap 渲染检查，不读取游戏屏幕。

两套游戏载荷有用户实机测试记录，但新安装器的完整用户操作和官方启动器联网补库仍需安装者确认；离线通过不代表所有第三方启动器格式或模组组合均已验证。

1. 退出游戏和启动器，解压并双击 EXE，核对中文、默认 Forge 选择和教程。
2. 选择加载器及实例，先检查再安装；核对所选配套 DLL/JAR。Forge 无 Fabric API，Fabric 保留原已发布文件。其它模组与个人键位应保留。
3. 进入 MC 单人测试世界和只狼可操作场景，执行 `/sekirobridge on`。测试背包、放置、F5 下蹲、M 钩索、姿态 HUD 与 Boss 阶段模式。Forge 另验鬼泣黑屏/次元斩绝和性能开关。
4. 退出后停用，确认普通只狼操作；再安装启用。需要切换加载器时先恢复，检查旧文件完全还原，再选另一实例。
5. 官方新建 Fabric 在官方启动器中确认版本选择、补库和 gameDir。恢复仅撤销管理的专用配置，保留后来新增的个人配置。

## 从源码制作安装器

`scripts/build-installer.py` 验证旧 Fabric Release 的配对清单，验证 exact Forge preview.6 的 DLL/JAR 哈希后打包，绝不从后来加入血条预览的本地 dist DLL 取文件。C# 编译器生成内置 ZIP 的 EXE，自动运行隔离检查并输出 ZIP、教程、SHA256SUMS。

```powershell
python scripts/build-installer.py --tag v0.1.0-20261010 --version 0.1.0-20261011 --forge-bundle dist/SekiroCraft-Forge-1.20.1-preview.6.zip --forge-game-tested --fabric-profile .cache/installer-fabric-profile.json --game-exe 'D:\Steam\steamapps\common\Sekiro\sekiro.exe'
```

`--forge-game-tested` 仅用于记录明确的用户验收，不能由离线编译自动推断。Fabric 元数据固定来自 `https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.19.5/profile/json`，构建需先下载至参数所指文件；核对 Minecraft、Loader 和启动主类，不动态升级。

源码与教程位于 `installer/`，校验记录 `build/installer/verification.json`，成品 `dist/installer/0.1.0-20261011/`。发布包含原许可证。EXE 未做商业代码签名，只从本项目 Release 获取并核对校验值，无需关闭安全软件。
