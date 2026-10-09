# SekiroCraft-Passthrough

在《只狼：影逝二度》的场景里操作真正的 Minecraft 人物、方块、背包和生物的 Windows 实验性模组。

本项目同时运行 **Minecraft 1.20.1 Fabric 与只狼**：Minecraft 负责人物操作和游戏逻辑，只狼提供场景、原生敌人和钩索；通过共享内存、JNI 与 D3D11 合成画面和同步状态。它不是把 Minecraft 反编译成 C++，也不是完整移植。原创 C++ 方块版本独立保留，本仓库只包含双进程桥接版本。

当前版本：`0.1.0`；补丁：`gameplay5-defense-render`；通信协议：`v3`。这是持续开发的版本，离线检查通过不代表所有场景和 Boss 都已完成实机验收。

2026-10-09 更新包含大型 Boss 动画部位受击判定、拔刀剑加法剑气显示、再战强者传送加载保护及第一人称界面恢复入口，详见 [更新日志](CHANGELOG.md)。用户确认本轮游戏测试完成并授权同步；新增 HUD 恢复命令的单独实机记录及不同地图、Boss、第三方模组的覆盖情况见更新日志。MC 自定义忍杀动作与结算接口目前处于 [方案设计](docs/MC_DEATHBLOW_DESIGN.md) 阶段，尚未替代自动扣红点。后续 GitHub 提交和推送仍需先经过用户游戏测试及上传授权。

可下载的桥接安装包和 MC 环境配置包见 [Releases](https://github.com/Xu060113/SekiroCraft-Passthrough/releases)。当前 **Fabric Loader 0.19.5 + Fabric API 0.92.12** 配置、模组的精确版本及原作者下载链接见 [MC 配置与模组](docs/MODPACK.md)。

## 功能与边界

| 功能 | 当前行为 |
| --- | --- |
| MC 操作 | 移动、跳跃、疾跑、潜行、创造飞行、背包、合成、方块、红石、第一/第三人称由真实 MC 处理 |
| F5 人物显示 | 匹配枪械模组修改后的实际深度格式，避免世界层复制失败；回归步骤见 [F5 验收](docs/F5_RENDER_TEST.md) |
| 原生地面 | 采样附近地面，支持放置第一块方块、生物蛋以及附近 MC 实体的地面碰撞 |
| 投射物 | MC 方块和实体命中保留；原生场景使用射线检测，敌人通过不可见 MC 代理接收命中 |
| 战斗 | MC 近战、投射物、爆炸和敌对怪物可向附近原生敌人传递伤害；原生姿态和玩家 HP 参与同步 |
| 大型敌人受击 | 主模型骨骼包围盒随动画更新，用于多部位射线、范围和距离判定；装配模型及特殊分离部件未全部覆盖，见 [受击框说明](docs/BOSS_HITBOX_TEST.md) |
| 拔刀剑剑气 | 已核对的加法发光剑气可参与合成和地形遮挡；减法次元斩需单独适配，见 [剑气说明](docs/SWORD_EFFECT_TEST.md) |
| Boss 阶段 | MC 桥接玩法建议开启“血量耗尽自动扣一颗红点”的简化结算；MC 忍杀动作与接口尚未实现，不能保证特殊剧情阶段、忍杀演出及最终奖励正确 |
| 玩家状态 | 生存血条按原生 HP 比例同步；创造/旁观模式使用原生无伤害位；饥饿和复活次数不等价同步；已识别的敌人攻击由 MC 处理护甲、盾牌和吸收血 |
| 峡谷低处 | 取消桥接角色与原生敌人代理因 MC 固定高度线产生的虚空误伤，原生死亡仍同步；见 [峡谷验收](docs/CANYON_VOID_TEST.md) |
| 传送加载 | 再战强者及地图重载期间保留角色虚空保护、暂停移动与战斗回传；场景和出生位置稳定后重新同步，仍需按 [加载验收](docs/CANYON_VOID_TEST.md) 实机验证 |
| 钩索 | MC 模式按 M 请求原生钩索，钩索位移反馈给 MC；普通跑跳和鼠标仍由 MC 控制，狼模型隐藏 |
| 音效 | 由后台 Minecraft 播放真实 MC 音效 |
| 菜单 | F6 在 MC 与只狼菜单输入之间切换，背包点击按所显示画面的坐标转发 |
| 剧情过场 | 检测原生过场生命周期，暂停 MC 画面、输入和相机覆盖；结束后等待新帧恢复；各剧情场景仍需分别验证 |

完整原生受击、击退、弹反和武器参数适配仍在开发。附近只狼 NPC 的方块碰撞使用人形尺寸近似，巨大 Boss、寻路和脚本瞬移不保证正确；没有创建原生 Havok 方块刚体。原生 NPC 主动攻击 MC 怪物尚未实现。最新定向验收见 [HP 阶段与钩索测试](docs/HP_STAGE_TRAVERSAL_TEST.md)。

## 环境要求

- Windows x64，支持 D3D11 的显卡；键盘和鼠标。手柄未验证。
- 合法安装的只狼 1.06。**仅支持下面这个可执行文件指纹**，版本号相同也不代表兼容：

  ```text
  637ACA527538C0EC6E1F136C8ED66046E95DFBDBB1F51926E134D9916398B856
  ```

- 当前运行配置：Minecraft Java Edition **1.20.1**，Fabric Loader **0.19.5**，Fabric API **`0.92.12+1.20.1`**，Java 21。启动器和 Fabric 配置需自行准备。
- **Loader 版本建议：优先使用较新的稳定版，以提高 MC 端 Fabric 模组兼容性。** 保持 Minecraft 1.20.1，并选择满足所装模组依赖要求的 Loader，有助于减少因旧版加载器导致的加载问题；本项目当前运行参考版本为 **0.19.5**。源码编译基线与运行版本独立，实际兼容性仍以所装模组的版本要求和游戏测试为准。Fabric 官方安装指南也建议选择较新的 Loader，见 [官方安装建议](https://wiki.fabricmc.net/install#mojang_s_minecraft_launcher)。
- 源码编译仍固定 Loader `0.16.10` 与 Fabric API `0.92.2+1.20.1` 的兼容基线；`fabric.mod.json` 声明 Loader `>=0.16.10`、Minecraft `1.20.1`、Java `>=17`。运行配置升级不代表支持其他 MC 游戏版本或任意更新的 API。
- 构建需要 Git、Windows x64 JDK 21 和 PowerShell（推荐 PowerShell 7）。Java 字节码目标为 17。
- 首次构建需要联网下载依赖。脚本固定 llvm-mingw `20260922`、Gradle `8.8`、Fabric Loom `1.6.12`。

Forge/OptiFine 配置不能直接加载本 Fabric 模组。Sodium、Iris、其他渲染模组及其他只狼 DLL 加载器的兼容性尚未验证，首次测试请用独立的原版 Fabric 实例。

## 下载与构建

在 PowerShell 中执行：

```powershell
git clone https://github.com/Xu060113/SekiroCraft-Passthrough.git
Set-Location .\SekiroCraft-Passthrough

# 改为自己安装的 Windows x64 JDK 21 目录；不要填写 bin 目录。
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

& .\scripts\bootstrap.ps1
& .\scripts\build-passthrough.ps1
```

已有完整依赖缓存时可以用 `& .\scripts\build-passthrough.ps1 -Offline`。`bootstrap.ps1` 准备工具链和第三方源码，构建脚本编译两端并运行离线检查，均不会启动游戏。不要用 `-SkipTests` 生成用于安装的验证包。

构建结果：

| 路径 | 内容 |
| --- | --- |
| `dist/SekiroCraft-Passthrough/dinput8.dll` | 只狼端模组 |
| `dist/SekiroCraft-Passthrough/minecraft/` | MC 模组 JAR（含 JNI）和 Fabric API |
| `dist/SekiroCraft-Passthrough-0.1.0.zip` | 本地打包结果 |
| `build/verification.json` | 构建检查结果、协议和文件 SHA256 |

Git 仓库提供源码；[Releases](https://github.com/Xu060113/SekiroCraft-Passthrough/releases) 提供成对的 DLL/JAR 安装包及 `.mrpack` 环境配置包，不包含游戏文件。安装包保留更新器需要的 `dist`、`build/verification.json` 和根目录 `scripts`，可以在解压后的根目录按下面步骤安装。源码用户先完成构建，不能只复制 ZIP 内的脚本单独执行。

## 首次部署

先备份只狼存档和 MC 世界，正常退出两款游戏。关闭仍在运行的 Java/Gradle 进程后再执行更新器。

**当前建议安装者开启 Boss 自动扣红心（游戏中的 Boss 红点）模式。** MC 忍杀动作与结算接口尚未实现，因此使用 MC 战斗时，建议以“Boss 当前阶段血量耗尽后自动扣一颗红点”作为临时结算方式。该模式默认关闭，单独复制 DLL/JAR 不会启用；请保留下面更新命令中的 `-AutoBossPhases` 参数。

1. 在启动器中建立专用的 `1.20.1 + Fabric 0.19.5` 配置，并安装 Fabric API `0.92.12+1.20.1`，设置独立的**游戏目录**。它必须与下面 `$mcDir` 一致，不一定是启动器所在目录。也可以导入 Release 的 `.mrpack`，详见 [环境配置步骤](docs/MODPACK.md)。
2. 在只狼设置中把钩索绑定为 M。
3. 在刚刚克隆并构建的仓库根目录运行以下命令，替换两条路径：

   ```powershell
   $sekiroDir = 'C:\Games\Sekiro'
   $mcDir = 'C:\Games\Minecraft\SekiroCraft'

   # 检查指纹，应与环境要求里的 SHA256 相同。
   Get-FileHash -LiteralPath (Join-Path $sekiroDir 'sekiro.exe') -Algorithm SHA256

   & .\scripts\switch-sekiro.ps1 -Action Install -GameDirectory $sekiroDir
   & .\scripts\prepare-minecraft.ps1 -GameDirectory $mcDir

   # 启用 M 钩索与简化 Boss 自动扣红点，同时校验两端版本。
   & .\scripts\update-installed.ps1 -MinecraftDirectory $mcDir -NativeGrappleKey M -AutoBossPhases
   ```

`switch-sekiro.ps1` 检查游戏指纹、备份可识别的旧 DLL，并保存安装记录；遇到未知 `dinput8.dll` 会拒绝覆盖。`prepare-minecraft.ps1` 只复制模组，**不会安装启动器或 Fabric Loader**。更新器备份并校验 DLL/JAR，第二端写入失败时回滚；可能保存的只狼存档备份不代替用户自己的备份。

源码构建包默认附编译基线的 Fabric API `0.92.2`；使用当前运行配置时，在关闭 MC 后将它替换为 [Fabric API 0.92.12+1.20.1](https://modrinth.com/mod/fabric-api/version/rvI2dfzR)，`mods` 中只保留一份 Fabric API。本次 Release 安装包已附 `0.92.12`，无需再替换。已升级的实例不要重复用首次准备脚本添加旧 API；正常更新器只替换桥接 DLL/JAR。

Fabric Loader 0.19.5 通过启动器或 Fabric 安装器安装，不作为普通模组放入 `mods`。升级时保留 Minecraft 1.20.1、同一个游戏目录及个人世界；启动后在日志开头核对 Loader 0.19.5 和 API 0.92.12+1.20.1。

更新器每次根据**本次命令的参数**设置该模式，不会自动保留上次的开关：后续更新也必须带上 `-AutoBossPhases`，否则会重新关闭。启用后，只狼游戏目录中的 `sekirobridge.ini` 应包含 `native_hits=1` 和 `auto_boss_phases=1`；重启只狼后配置才会生效。请通过更新器切换模式，避免手动修改已安装文件导致哈希校验失败。`-Diagnostic` 是定向排查选项，日常运行无须开启。

**不使用 MC 模组、恢复正常只狼玩法时，应关闭桥接及该简化结算模式，避免影响原生忍杀和操作。** 停用与恢复步骤见[下文](#停用与恢复)。

**请保留原安装仓库及其 `runtime/installation.json`、备份目录和目录位置。** 记录含本地绝对路径，更换克隆目录、删除 `runtime` 或移动仓库可能影响更新和恢复。

### PowerShell 阻止执行时

可以为单次命令设置执行策略，无需修改整个系统。示例（路径自行替换）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Mods\SekiroCraft-Passthrough\scripts\switch-sekiro.ps1' -Action Install -GameDirectory 'C:\Games\Sekiro'
```

若提示 `Passthrough is installed. Restore it before replacing or updating the build.`，表示该目录已记录安装，**不要再次 Install**。同一仓库的常规更新使用下面的更新流程。

## 启动与操作

1. 启动只狼并进入可移动的游戏场景。
2. 启动专用 Fabric 配置，建立独立的**单人创造虚空世界**用于首轮测试；保持 MC 进程运行。
3. 在 MC 聊天框执行 `/sekirobridge on`。连接初始化从狼的位置取得出生坐标，默认第一人称。
4. 切换到只狼窗口，使用 MC 操作。若正在只狼菜单输入模式，按 F6 返回 MC 控制。
5. 用 `/sekirobridge status` 检查连接；停止桥接执行 `/sekirobridge off`。

两端默认共享内存通道均为 `default`；使用同一 Windows 登录会话运行，两端设置必须一致。通信不需要开放网络端口。

| 按键 | 功能 |
| --- | --- |
| WASD / Space / Shift / Ctrl | MC 移动 / 跳跃 / 潜行 / 疾跑（以 MC 当前键位为准） |
| 双击 Space | 创造模式飞行；Space 上升，Shift 下降 |
| E / Q / F / F5 | 背包 / 丢弃 / 换手 / 切换第一、第三人称 |
| F1 | MC 原版隐藏/显示界面，同时影响第一人称手臂、准星和状态栏；背包不受影响 |
| 1–9 / 滚轮 | 选择快捷栏 |
| 左键 / 右键 | 挖掘或攻击 / 使用物品或放置 |
| 鼠标中键 | 用户指定的枪械配置：开火和瞄准均用中键，以避开 MC 操作冲突；在 TaCZ 控制设置中自行绑定 |
| M | 请求原生钩索；`grapple_key` 应匹配只狼实际钩索键，本指南使用 M |
| R | 留给 MC 和其他模组自定义，不触发原生攻击或忍杀 |
| 鼠标侧键 4 / 5 | 转发到 MC，可在控制设置里绑定支持鼠标输入的模组动作；见 [模组输入测试](docs/MOD_INPUT_TEST.md) |
| F6 | 切换只狼菜单输入与 MC 控制 |
| Esc | 关闭 MC 当前界面；MC 无界面时进入只狼菜单输入 |
| F7 / F8 / F9 | 桥接诊断 / 暂停或恢复 MC 控制 / 生成测试草方块 |

F6–F9、M 用于桥接，请避免绑定相冲突的 MC 操作。R 已释放给 MC 模组，例如枪械换弹。**G 不转发原生跑跳，M 只请求钩索**；M 也不会接管普通跑跳。打开只狼菜单后按 F6 将输入交给只狼，操作完毕再按 F6 返回 MC。

枪械按键以 MC 的实际控制设置为准。本次用户指定的键位为 **TaCZ 开火、瞄准都绑定到鼠标中键**，换弹使用 R；左右键继续用于 MC 攻击、挖掘、放置与使用物品。这些个人键位不会由安装器自动改写，下载环境包后需在控制设置中手动设置。

拿着方块或生物蛋瞄准已采样的只狼地面，右键放置；后续邻面放置使用 MC 原版规则。原生地面不可挖掘，非整数地面可能与第一块方块之间留下不到一格的间隙。测试怪物攻击时使用非和平难度，选择僵尸/骷髅等敌对生物；普通动物保持原版行为。

## 更新与卸载

### 已安装后的更新

正常退出两款游戏，在**原安装仓库**运行：

```powershell
git pull --ff-only
& .\scripts\build-passthrough.ps1
& .\scripts\update-installed.ps1 -MinecraftDirectory 'C:\Games\Minecraft\SekiroCraft' -NativeGrappleKey M -AutoBossPhases
```

继续使用 MC 自动扣红点模式时，每次更新都保留 `-AutoBossPhases`。DLL 与含 JNI 的 MC JAR 必须成对更新。不要手动混用不同提交的产物，也不要对已安装实例重复执行首次安装命令。更新器会检查安装记录和文件哈希；文件被手动修改时先核对原因，不要通过删除记录强行绕过。

### 停用与恢复

**正常玩只狼、不使用 MC 模组时，应停用桥接和自动扣红点模式。** 临时停止桥接，在 MC 执行 `/sekirobridge off`；F6 只切换菜单输入，F8 只暂停 MC 控制，不能代替停用。

如果保留桥接安装但需要关闭自动扣红点，正常退出两款游戏，在**原安装仓库**运行下面命令，替换 MC 游戏目录；不要加 `-AutoBossPhases` 或 `-Diagnostic`：

```powershell
& .\scripts\update-installed.ps1 -MinecraftDirectory 'C:\Games\Minecraft\SekiroCraft' -NativeGrappleKey M
```

此命令将 `auto_boss_phases` 和 `native_hits` 均设为 `0`，下次启动只狼生效；它不会代替 `/sekirobridge off`。以后继续使用 MC 自动扣红点时，再按更新步骤带上 `-AutoBossPhases`。

**只玩原版只狼时，推荐直接恢复原 DLL 和桥接配置。** 正常退出两款游戏后，在原仓库根目录执行；选择恢复时无需先运行上面的模式切换命令：

```powershell
& .\scripts\switch-sekiro.ps1 -Action Restore -GameDirectory 'C:\Games\Sekiro'
```

恢复脚本只处理它记录拥有的只狼 DLL 和桥接配置，MC 模组不会自动删除。完全停用 MC 端时，在关闭 MC 后移除专用实例 `mods` 中的 `sekiro-minecraft-passthrough-0.1.0.jar`；Fabric API 是否保留取决于其他模组。保留备份和世界，不要通过删除整个游戏目录卸载。

## 防御与难度设置

已识别的只狼敌人攻击先进入 MC 原版伤害流程，再把实际扣血同步回只狼。生存模式可手持/副手装备盾牌并按住右键，按原版起手延迟和正面方向判定格挡；护甲、韧性、保护附魔、抗性效果、耐久与受伤冷却使用 MC 原规则。后方攻击不会被盾牌保护。无法定位来源的攻击仍可计算护甲，但不能保证盾牌方向判断。原生坠落、脚本死亡与忍杀伤害保留原路径，不将它们一律变成可格挡攻击。原生受击动作和姿态不等同于 MC 格挡动作。当前接入的原生攻击按普通生物攻击处理，原生元素伤害标签尚未逐项映射。

远程攻击没有有效攻击者模型引用时，使用经过验证的命中方向还原来袭侧，以供 MC 原版盾牌判断；不使用玩家朝向猜测来袭方向。测试时请面向箭或子弹来袭方向，举盾至少半秒，并对比背向攻击和松开盾牌的扣血。炮弹、范围攻击和法术需要单独验收。

默认普通敌人一条血量对应 40 点 MC 伤害，Boss 每阶段对应 200 点；同时降低 MC 攻击对姿态的比例，避免只改血量却仍几刀破姿态。可通过更新器参数 `-NormalHealthPoints` 和 `-BossHealthPoints` 调整 `sekirobridge.ini` 的对应数值（20–10000，越高越耐打；未指定时保留已有设置）。例如退出两款游戏后在原仓库运行：

```powershell
& .\scripts\update-installed.ps1 -MinecraftDirectory 'C:\Games\Minecraft\SekiroCraft' -NativeGrappleKey M -AutoBossPhases -BossHealthPoints 300
```

例如默认 7 点剑伤约削去 Boss 一阶段 3.5% 血量，暴击/附魔仍按 MC 实际伤害计算。

协议 v3 增加原生伤害确认队列，须同时更新 DLL、JNI 和 JAR。新包的闪烁、防御方向、耐久及实际 Boss 难度仍需要实机验收。

## 注意事项与排查

- **地形是局部近似。** 地面采样覆盖玩家附近约 6 米，缓存约 1.5 秒；没有完整墙顶、洞穴或多层地形碰撞。高速移动、巨大敌人及采样范围外实体可能穿透或失去支撑。
- **MC 战斗建议开启 Boss 自动扣红点。** MC 忍杀尚未实现，该模式作为临时结算方案，默认关闭，需在安装及每次更新时指定 `-AutoBossPhases`。它在血量耗尽时尝试推进原生阶段；特殊脚本门槛、忍杀演出、最终奖励仍需逐个验证，不能把它当成完整原生战斗适配。不用 MC 模组时请按[停用与恢复](#停用与恢复)关闭，以免影响正常只狼操作。
- **拔刀剑首次命中适配。** 原生敌方代理使用 MC `Monster` 类型，友方/中立 NPC 保留原类型，解决默认关闭友伤时只能攻击已受伤目标的问题；换版本后请按 [拔刀剑验收](docs/SLASHBLADE_TEST.md) 回归。R 已释放给 MC 模组；后续计划在原生红花确认后自动开始忍杀，当前尚未实现。
- **钩索与相机交接仍需实机验收。** 当前读取原生动作状态、仅在确认钩索时跟随位移，不套用固定等待时间；史蒂夫没有只狼忍杀/钩索动作动画。
- **存档与地图独立。** MC 方块由真实单人世界保存，不转换原创版本 SCW 存档。尚无地图或存档槽自动绑定，切换地图应单独校准。
- **高度范围。** `sekirobridge.ini` 的 `y_offset` 默认为 128。桥接角色与原生代理不再因 MC 的固定虚空高度线误伤，但可放方块的高度仍受 MC 世界范围限制。修改偏移不会自动搬动已放置的方块。
- **画面不可见/闪烁。** 用 F7 查看连接与提交状态，确认两端协议匹配、MC 在单人世界且桥接已开启。先在无其他渲染模组的 Fabric 实例复现，保留日志后正常退出。
- **背包或只狼菜单点击无效。** 先用 F6 确认输入归属；检查是否误按 F8 暂停 MC 输入。报告当时界面、分辨率、缩放、操作步骤，避免仅描述“点不到”。
- **第一人称手臂和状态栏同时消失，但方块与背包正常。** 先按 F1 检查是否启用了 MC 的隐藏界面开关；也可在 MC 执行 `/sekirobridge hud` 显式恢复状态栏与手部渲染，单独玩 MC 时也可使用。`/sekirobridge on` 会恢复可见界面，之后仍可用 F1 手动隐藏；状态命令会显示 `hudHidden` 和当前视角。若恢复后仍不可见，请区分 MC 窗口与只狼窗口的表现。
- **无 MC 音效。** 检查 Windows 音量混合器中的 Java/Minecraft 和 MC 声音设置；声音来自后台 MC，不由只狼端播放。
- **更新报游戏仍在运行。** 正常退出后检查 `sekiro`、`java`、`javaw`；更新器也会拒绝仍运行的 Gradle JVM，关闭相应进程后再试。
- **不支持的只狼指纹。** 安装器会拒绝，不能仅修改哈希绕过；需要重新适配地址和机器码才能支持其他构建。

问题反馈请附游戏指纹、仓库提交号、F7 状态、最短复现步骤，以及脱敏后的相关日志。不要上传游戏可执行文件、游戏资源、存档、账号凭据或完整本机个人路径。

## 开发资料与来源

| 文档 | 内容 |
| --- | --- |
| [CHANGELOG.md](CHANGELOG.md) | 本轮功能、修复、测试记录与保留的限制 |
| [BOSS_HITBOX_TEST.md](docs/BOSS_HITBOX_TEST.md) | 大型敌人动画部位受击判定与回退行为 |
| [SWORD_EFFECT_TEST.md](docs/SWORD_EFFECT_TEST.md) | 拔刀剑加法剑气的画面合成与遮挡 |
| [MODPACK.md](docs/MODPACK.md) | 当前 Fabric 版本、模组下载、环境配置包与升级步骤 |
| [F5_RENDER_TEST.md](docs/F5_RENDER_TEST.md) | 第三人称人物与枪械深度格式回归 |
| [MOD_INPUT_TEST.md](docs/MOD_INPUT_TEST.md) | R 换弹、Caps Lock 与鼠标侧键 |
| [CANYON_VOID_TEST.md](docs/CANYON_VOID_TEST.md) | 原生低处地形与虚空误伤 |
| [CINEMATIC_TEST.md](docs/CINEMATIC_TEST.md) | 剧情过场与画面恢复 |
| [SLASHBLADE_TEST.md](docs/SLASHBLADE_TEST.md) | 拔刀剑首次选中敌人 |
| [DEFENSE_RENDER_TEST.md](docs/DEFENSE_RENDER_TEST.md) | 特效闪烁、Boss 难度与盾牌/护甲验收 |
| [PROTOCOL.md](docs/PROTOCOL.md) | 两端协议与共享内存 |
| [REFERENCE_DESIGN.md](docs/REFERENCE_DESIGN.md) | 参考项目思路与本实现的差异 |
| [GUI_NATIVE_COMBAT_TEST.md](docs/GUI_NATIVE_COMBAT_TEST.md) | 背包、原生菜单、战斗交接验收 |
| [HP_STAGE_TRAVERSAL_TEST.md](docs/HP_STAGE_TRAVERSAL_TEST.md) | 自动 Boss 阶段、M 钩索与生物蛋验收 |
| [LIFE_ACTION_PROJECTILE_TEST.md](docs/LIFE_ACTION_PROJECTILE_TEST.md) | 死亡、复活、原生动作与投射物 |

`docs` 中的历史测试说明用于记录迭代过程；当前安装步骤和按键以本 README 为准。

思路参考 [universal-modder 的 Minecraft/GTA5 passthrough 示例](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough) 的两端传输与画面分层，以及 [SkyCraft](https://github.com/chasmlol/SkyCraft) 的 MC 物理和地形就绪管理。本项目独立使用 Win32 共享内存、自有 JNI 和 D3D11 hook 适配只狼，没有复制这两个项目的源文件，也没有实现 SkyCraft 的完整原生网格渲染和碰撞系统。

第三方组件与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [licenses](licenses)。本仓库目前未声明整体开源许可证；公开可见不代表授予任意使用或再分发许可。Minecraft 与只狼的代码、资源和商标属于各自权利人，仓库不分发游戏内容。
