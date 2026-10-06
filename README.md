# SekiroCraft-Passthrough

在《只狼：影逝二度》的场景里操作真正的 Minecraft 人物、方块、背包和生物的 Windows 实验性模组。

本项目同时运行 **Minecraft 1.20.1 Fabric 与只狼**：Minecraft 负责人物操作和游戏逻辑，只狼提供场景、原生敌人和钩索；通过共享内存、JNI 与 D3D11 合成画面和同步状态。它不是把 Minecraft 反编译成 C++，也不是完整移植。原创 C++ 方块版本独立保留，本仓库只包含双进程桥接版本。

当前版本：`0.1.0`；补丁：`gameplay5-defense-render`；通信协议：`v3`。这是持续开发的版本，离线检查通过不代表所有场景和 Boss 都已完成实机验收。

本次更新修复桥接启动时两端互相等待第一帧的问题，并补齐远程攻击的来源、来袭方向和共用扣血路径。保留攻击特效深度过滤、盾牌/护甲减伤与可配置的 Boss 伤害比例；两端安装文件须一起更新。测试步骤见 [防御与画面验收](docs/DEFENSE_RENDER_TEST.md)。

## 功能与边界

| 功能 | 当前行为 |
| --- | --- |
| MC 操作 | 移动、跳跃、疾跑、潜行、创造飞行、背包、合成、方块、红石、第一/第三人称由真实 MC 处理 |
| 原生地面 | 采样附近地面，支持放置第一块方块、生物蛋以及附近 MC 实体的地面碰撞 |
| 投射物 | MC 方块和实体命中保留；原生场景使用射线检测，敌人通过不可见 MC 代理接收命中 |
| 战斗 | MC 近战、投射物、爆炸和敌对怪物可向附近原生敌人传递伤害；原生姿态和玩家 HP 参与同步 |
| Boss 阶段 | 可选“血量耗尽自动扣一颗红点”的简化结算；不能保证特殊剧情阶段、忍杀演出及最终奖励正确 |
| 玩家状态 | 生存血条按原生 HP 比例同步；创造/旁观模式使用原生无伤害位；饥饿和复活次数不等价同步；已识别的敌人攻击由 MC 处理护甲、盾牌和吸收血 |
| 钩索 | MC 模式按 M 请求原生钩索，钩索位移反馈给 MC；普通跑跳和鼠标仍由 MC 控制，狼模型隐藏 |
| 音效 | 由后台 Minecraft 播放真实 MC 音效 |
| 菜单 | F6 在 MC 与只狼菜单输入之间切换，背包点击按所显示画面的坐标转发 |

完整原生受击、击退、弹反和武器参数适配仍在开发。附近只狼 NPC 的方块碰撞使用人形尺寸近似，巨大 Boss、寻路和脚本瞬移不保证正确；没有创建原生 Havok 方块刚体。原生 NPC 主动攻击 MC 怪物尚未实现。最新定向验收见 [HP 阶段与钩索测试](docs/HP_STAGE_TRAVERSAL_TEST.md)。

## 环境要求

- Windows x64，支持 D3D11 的显卡；键盘和鼠标。手柄未验证。
- 合法安装的只狼 1.06。**仅支持下面这个可执行文件指纹**，版本号相同也不代表兼容：

  ```text
  637ACA527538C0EC6E1F136C8ED66046E95DFBDBB1F51926E134D9916398B856
  ```

- Minecraft Java Edition 1.20.1，Fabric Loader 0.16.10，Fabric API `0.92.2+1.20.1`。启动器和 Fabric 配置需自行准备。
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

GitHub 仓库提供源码，不包含游戏文件或预编译发行包。以下安装和更新步骤以**保留完整构建目录**为前提；更新器还需要 `build/verification.json`，不能只复制 ZIP 内的脚本单独执行。

## 首次部署

先备份只狼存档和 MC 世界，正常退出两款游戏。关闭仍在运行的 Java/Gradle 进程后再执行更新器。

1. 在启动器中建立专用的 `1.20.1 + Fabric 0.16.10` 配置，设置独立的**游戏目录**。它必须与下面 `$mcDir` 一致，不一定是启动器所在目录。
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

不需要自动 Boss 阶段时，最后一条命令去掉 `-AutoBossPhases`。更新器每次根据本次参数设置该开关，后续更新要保留此参数才能继续启用。`-Diagnostic` 是定向排查选项，会开启记录及原生命中后端，日常运行无须开启。

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
| 1–9 / 滚轮 | 选择快捷栏 |
| 左键 / 右键 | 挖掘或攻击 / 使用物品或放置 |
| M | 请求原生钩索；`grapple_key` 应匹配只狼实际钩索键，本指南使用 M |
| R | 转发原生攻击、红点忍杀或复活输入，能否生效取决于原生状态 |
| F6 | 切换只狼菜单输入与 MC 控制 |
| Esc | 关闭 MC 当前界面；MC 无界面时进入只狼菜单输入 |
| F7 / F8 / F9 | 桥接诊断 / 暂停或恢复 MC 控制 / 生成测试草方块 |

F6–F9、M、R 用于桥接，请避免绑定相冲突的 MC 操作。**G 不转发原生跑跳，M 只请求钩索**；M 也不会接管普通跑跳。打开只狼菜单后按 F6 将输入交给只狼，操作完毕再按 F6 返回 MC。

拿着方块或生物蛋瞄准已采样的只狼地面，右键放置；后续邻面放置使用 MC 原版规则。原生地面不可挖掘，非整数地面可能与第一块方块之间留下不到一格的间隙。测试怪物攻击时使用非和平难度，选择僵尸/骷髅等敌对生物；普通动物保持原版行为。

## 更新与卸载

### 已安装后的更新

正常退出两款游戏，在**原安装仓库**运行：

```powershell
git pull --ff-only
& .\scripts\build-passthrough.ps1
& .\scripts\update-installed.ps1 -MinecraftDirectory 'C:\Games\Minecraft\SekiroCraft' -NativeGrappleKey M -AutoBossPhases
```

DLL 与含 JNI 的 MC JAR 必须成对更新。不要手动混用不同提交的产物，也不要对已安装实例重复执行首次安装命令。更新器会检查安装记录和文件哈希；文件被手动修改时先核对原因，不要通过删除记录强行绕过。

### 停用与恢复

临时停用：在 MC 执行 `/sekirobridge off`。恢复只狼原 DLL：正常退出两款游戏后，在原仓库根目录执行：

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
- **Boss 自动扣红点是可选的实验功能。** 它在血量耗尽时尝试推进原生阶段；特殊脚本门槛、忍杀演出、最终奖励仍需逐个验证，不能把它当成完整原生战斗适配。
- **钩索与相机交接仍需实机验收。** 当前读取原生动作状态、仅在确认钩索时跟随位移，不套用固定等待时间；史蒂夫没有只狼忍杀/钩索动作动画。
- **存档与地图独立。** MC 方块由真实单人世界保存，不转换原创版本 SCW 存档。尚无地图或存档槽自动绑定，切换地图应单独校准。
- **高度范围。** `sekirobridge.ini` 的 `y_offset` 默认为 128，需把场景映射到 MC 的 -64…319。修改偏移不会自动搬动已放置的方块。
- **画面不可见/闪烁。** 用 F7 查看连接与提交状态，确认两端协议匹配、MC 在单人世界且桥接已开启。先在无其他渲染模组的 Fabric 实例复现，保留日志后正常退出。
- **背包或只狼菜单点击无效。** 先用 F6 确认输入归属；检查是否误按 F8 暂停 MC 输入。报告当时界面、分辨率、缩放、操作步骤，避免仅描述“点不到”。
- **无 MC 音效。** 检查 Windows 音量混合器中的 Java/Minecraft 和 MC 声音设置；声音来自后台 MC，不由只狼端播放。
- **更新报游戏仍在运行。** 正常退出后检查 `sekiro`、`java`、`javaw`；更新器也会拒绝仍运行的 Gradle JVM，关闭相应进程后再试。
- **不支持的只狼指纹。** 安装器会拒绝，不能仅修改哈希绕过；需要重新适配地址和机器码才能支持其他构建。

问题反馈请附游戏指纹、仓库提交号、F7 状态、最短复现步骤，以及脱敏后的相关日志。不要上传游戏可执行文件、游戏资源、存档、账号凭据或完整本机个人路径。

## 开发资料与来源

| 文档 | 内容 |
| --- | --- |
| [DEFENSE_RENDER_TEST.md](docs/DEFENSE_RENDER_TEST.md) | 特效闪烁、Boss 难度与盾牌/护甲验收 |
| [PROTOCOL.md](docs/PROTOCOL.md) | 两端协议与共享内存 |
| [REFERENCE_DESIGN.md](docs/REFERENCE_DESIGN.md) | 参考项目思路与本实现的差异 |
| [GUI_NATIVE_COMBAT_TEST.md](docs/GUI_NATIVE_COMBAT_TEST.md) | 背包、原生菜单、战斗交接验收 |
| [HP_STAGE_TRAVERSAL_TEST.md](docs/HP_STAGE_TRAVERSAL_TEST.md) | 自动 Boss 阶段、M 钩索与生物蛋验收 |
| [LIFE_ACTION_PROJECTILE_TEST.md](docs/LIFE_ACTION_PROJECTILE_TEST.md) | 死亡、复活、原生动作与投射物 |

`docs` 中的历史测试说明用于记录迭代过程；当前安装步骤和按键以本 README 为准。

思路参考 [universal-modder 的 Minecraft/GTA5 passthrough 示例](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough) 的两端传输与画面分层，以及 [SkyCraft](https://github.com/chasmlol/SkyCraft) 的 MC 物理和地形就绪管理。本项目独立使用 Win32 共享内存、自有 JNI 和 D3D11 hook 适配只狼，没有复制这两个项目的源文件，也没有实现 SkyCraft 的完整原生网格渲染和碰撞系统。

第三方组件与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [licenses](licenses)。本仓库目前未声明整体开源许可证；公开可见不代表授予任意使用或再分发许可。Minecraft 与只狼的代码、资源和商标属于各自权利人，仓库不分发游戏内容。
