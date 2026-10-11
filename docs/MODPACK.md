# 当前 Minecraft、Fabric 与模组配置

运行配置采集于 2026-10-08，来自实际启动日志和已安装 JAR；2026-10-11 将桥接更新到 0.1.1，保留 Loader、API 和其它玩法模组的原配置。第三方下载版本通过文件 SHA1 与原作者 Modrinth 发布记录核对。版本清单及 SHA256/SHA512 见 [minecraft-runtime.json](../config/minecraft-runtime.json)，本轮桥接修复见 [更新日志](../CHANGELOG.md)。这份配置是已运行的组合，不是所有新版模组均兼容的承诺。

| 组件 | 当前版本 | 安装位置或下载来源 |
| --- | --- | --- |
| Minecraft Java Edition | 1.20.1 | 启动器中的独立实例 |
| Fabric Loader | 0.19.5 | 通过启动器或 [Fabric 安装器](https://fabricmc.net/use/installer/) 安装 |
| Java | 21 | 选择 Windows x64 Java 21 运行实例；桥接字节码最低为 17 |
| Fabric API | 0.92.12+1.20.1 | [准确版本](https://modrinth.com/mod/fabric-api/version/rvI2dfzR)，放入 `mods` |
| Sekiro Minecraft Passthrough | Fabric 0.1.1，协议 v3 | 本仓库 [Release](https://github.com/Xu060113/SekiroCraft-Passthrough/releases/tag/v0.1.1-20261011)，放入 `mods`，与 DLL 成对安装 |
| 拔刀剑：重织 / SlashBlade: Refabricated | 1.20.1-1.4.0-Resharped-1.9.65 | [准确版本](https://modrinth.com/mod/slashblade-refabricated/version/a7m4w2ic) |
| TaCZ：重织 / TaCZ: Refabricated | 1.20.1-0.7.1-forge1.1.8-hotfix2 | [准确版本](https://modrinth.com/mod/tacz-refabricated/version/3ShZi2cZ) |
| Forge Config API Port | 8.0.3，1.20.1 Fabric | [准确版本](https://modrinth.com/mod/forge-config-api-port/version/HvR3IdRE) |

拔刀剑与枪械为可选玩法模组。使用它们时安装表中的 Forge Config API Port；这是 Fabric 的配置库，不能用它加载任意 Forge 模组。TaCZ 文件名中的 `forge1.1.8` 表示它移植的上游版本，这里下载的是 **Refabricated** 文件。TaCZ 作者说明及源码见 [TACZ-Refabricated](https://github.com/Sh1roCu/TACZ-Refabricated)，拔刀剑源码见 [SlashBlade-Refabricated](https://github.com/Sh1roCu/SlashBlade-Refabricated)。

## Release 中的文件

- `SekiroCraft-Installer-0.1.1-20261011.zip`：推荐新手使用的一键安装程序，可选 Fabric / Forge，附中文记事本教程。
- `SekiroCraft-Fabric-1.20.1-0.1.1.zip`：手动部署平铺包，含配套只狼 DLL、Fabric 0.1.1 JAR、API、哈希和说明。按 [更新步骤](ENTITY_TERRAIN.md) 安装，不对平铺包运行旧 ZIP 的脚本更新器。
- `SekiroCraft-Fabric-1.20.1-0.1.1.mrpack`：MC 客户端环境配置，固定 Minecraft 1.20.1 和 Loader 0.19.5；包含桥接 JAR，通过原作者 CDN 下载表中的四个第三方模组并验证哈希。不包含只狼 DLL，仍需安装器或手动 ZIP 里的只狼端。
- `sekiro-minecraft-passthrough-0.1.1.jar`：单独的桥接 JAR，便于已配置实例更新；必须与此次 Release 的 DLL 配套。
- `SHA256SUMS.txt`：下载文件的 SHA256。

环境包不包含 MC 游戏本体、账号配置、个人按键、存档、世界或另加的枪械资源包。首次进入可用的独立世界后仍需执行 `/sekirobridge on`，不会自动接管游戏。

## 新建配置

支持 Modrinth 整合包的启动器可以导入 `.mrpack`，让启动器安装 Loader 和下载模组。Prism Launcher 的操作为添加实例、选择导入并选择包文件；Modrinth App 可从文件导入。选择 Java 21，完成下载后核对 Loader 为 0.19.5。原格式说明见 [Modrinth pack format](https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack)。

不支持 `.mrpack` 的启动器可手动新建 `1.20.1 + Fabric 0.19.5`，然后将表中的 JAR 放进该实例的 `mods`。桥接 JAR 从 Release 获取，四个第三方模组从表中准确版本下载。不要直接复制别人的完整 `.minecraft`。

只狼端按根目录 [README](../README.md#一键部署无需编译推荐新用户) 或手动 ZIP 的 [更新说明](ENTITY_TERRAIN.md) 安装。导入 `.mrpack` 后已经有桥接 JAR 和 Fabric API，无需再次运行旧脚本。MC 必须开单人世界，不能使用远程服务器或作为独立服务器运行该桥接。

## 已有实例升级 Fabric

1. 正常退出两款游戏，备份 MC 世界以及原安装仓库的 `runtime`。
2. 在启动器里为 **同一个 MC 1.20.1 游戏目录** 安装/切换 Loader 0.19.5。Loader 不是一个需要放进 `mods` 的 JAR；不要同时升级 MC 游戏版本。Fabric 官方升级步骤见 [Windows 更新指南](https://docs.fabricmc.net/players/updating-fabric/windows)。
3. 将旧 Fabric API 移到实例外的备份目录，再放入 `fabric-api-0.92.12+1.20.1.jar`。`mods` 里仅保留一个 Fabric API 和一个桥接 JAR。不要把 `.disabled.jar` 仍放在 `mods` 里。
4. 使用新版安装器的“一键安装 / 更新”，或按对应发布的手动说明更新 DLL/JAR，保留安装记录与备份。不要用旧脚本与安装器交替管理；安装器保留个人按键、世界和其它玩法模组。
5. 启动 MC 后，检查日志开头为 `Loading Minecraft 1.20.1 with Fabric Loader 0.19.5`，模组列表中 `fabric-api` 为 `0.92.12+1.20.1`。开启桥接，回归背包、F5、R 换弹和 M 钩索。

源码编译与运行配置分开：`mc/build.gradle` 仍固定 Loader 0.16.10、API 0.92.2，用于兼容基线编译；运行依赖声明为 Loader `>=0.16.10`、Minecraft `1.20.1`、Java `>=17`、Fabric API 存在。源码构建默认附旧基线 API，使用上表配置时手动替换；本次 Release 和 `.mrpack` 固定附/下载 0.92.12。没有改变桥接协议或 MC 游戏版本。

## 操作与兼容注意

- M 仅用于原生钩索；F6–F9 用于桥接，不要给枪械/拔刀剑设置同样的键。R 已释放给 MC，可绑定枪械换弹。G 可由 MC 模组自行使用。
- 用户指定的枪械键位为 TaCZ **开火和瞄准均用鼠标中键**，换弹使用 R；左右键保留 MC 原版操作。环境包不包含个人 `options.txt`，导入后请自行在控制设置中这样绑定。
- 鼠标侧键 4/5 和 Caps Lock 已转发，但动作是否接受鼠标由该模组自身决定；当前 TaCZ 换弹事件监听键盘，可用 R，鼠标侧键适合绑定它支持鼠标监听的动作。
- 拔刀剑首次选择原生敌人的类型适配已加入，默认关闭友伤时无需先用其他武器伤害敌人。
- TaCZ 使用深度模板缓冲，桥接已匹配实际深度格式；换枪械模组、光影或渲染模组后需回归 F5。Sodium/Iris、多重采样和其它版本的枪械包未据此宣称兼容。
- 不把未来的 MC 忍杀动作方案标成已完成；当前仍使用可选的血量耗尽 Boss 阶段推进。回归步骤见 README 的开发文档。
