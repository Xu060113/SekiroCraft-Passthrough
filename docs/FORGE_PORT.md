# Forge 1.20.1 独立移植预览

这是独立的 Forge 构建，原来的 `mc/` Fabric 工程保留。**preview.6 已由用户在 Forge 47.4.26 游戏中测试通过，2026-10-11 授权发布。** Fabric 发布载荷本次不更新；其它设备与模组组合仍需分别验证。

## 环境

- Windows x64；Minecraft **1.20.1**。
- Forge **47.4.10** 为构建基准，声明范围 `>=47.4.10,<48`；推荐已实测 **47.4.26**，其它 47.x 未逐一验证。
- 游戏运行使用 Java **17**；编译生成 Java 17 字节码。
- 只狼端使用当前配套的 ABI v3 `dinput8.dll` 与配置。JNI DLL 内置于 Forge JAR，通信格式与 Fabric 版一致。
- Forge 版不依赖 Fabric Loader、Fabric API 或 Architectury API。Architectury Loom 仅作为构建工具，用于沿用 Yarn 映射并将产物重映射到 Forge 的 SRG 名称。

参考：[Forge 1.20.1 官方版本页](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)、[Architectury Loom 映射说明](https://docs.architectury.dev/loom/introduction/)。

## 测试安装

1. 在启动器里新建 **1.20.1 + Forge 47.4.26** 的独立实例，开启版本隔离。先不加其他模组，运行到主菜单后正常退出。
2. 将 `sekiro-minecraft-passthrough-forge-0.1.0-forge-preview.6.jar` 放进该实例的 `mods`，移走旧的 Forge 桥接 JAR，避免同时安装两个版本。不要把 Fabric 版桥接 JAR 或 Fabric API 放进去。
3. 保留现有 Fabric 实例和存档。测试用新的本地单人世界，不要直接用生产存档验证跨加载器迁移。
4. preview.6 必须成对更新：两款游戏正常退出后，备份只狼目录的旧 `dinput8.dll`，替换为预览 ZIP 中配套的 `dinput8.dll`；保留当前 `sekirobridge.ini`。首次手动安装才将 ZIP 的默认配置一同放入只狼目录。只更换 JAR 无法支持技能相机倾斜，桥接会提示只狼端版本过旧。检查配置的 `channel` 与 MC 的 `sekirobridge/bridge.properties` 一致，默认都是 `default`。完整 MC 忍杀尚未实现，建议 `native_hits=1` 和 `auto_boss_phases=1` 开启耗尽血量扣红点；默认配置仍为关闭。正常玩只狼时关闭桥接及该模式，或恢复旧 DLL；新手建议使用一键安装程序设置。
5. 启动两端并进入场景。MC 内输入 `/sekirobridge status` 查看 JNI 状态，输入 `/sekirobridge on` 启用。
6. 操作沿用 Fabric 版：WASD、跳跃、背包、鼠标、F5；M 仅转发钩索，需与只狼绑定一致。`/sekirobridge off` 解除桥接，F8 暂停桥接输入，F6 进入只狼菜单输入。
7. `/sekirobridge hud` 恢复第一人称手臂和原版 HUD；`/sekirobridge stamina on|off|toggle` 控制独立姿态条。
8. `/sekirobridge performance on|off` 控制 MC 窗口的桥接垂直同步优化，默认开启。桥接时跳过该窗口的垂直同步等待；解除桥接后恢复 MC 选项中的当前值，不改写 `options.txt` 的垂直同步设置。设置保存为 `sekirobridge/bridge.properties` 的 `bridge_disable_vsync`，不降低特效质量、分辨率或渲染距离。

**新版一键安装程序已支持 Forge/Fabric 选择**，见 [简易教程](../installer/简易教程.txt)。先用启动器准备 Forge 1.20.1 实例并运行一次；旧 `prepare-minecraft.ps1` 仍只用于 Fabric，不能选择 Forge 实例。手动安装时使用本次 ZIP 的成对 DLL/JAR。

### preview.6 次元斩绝的相机与粒子深度

根据单独 MC 和桥接后的两段用户视频，修复世界画面采集与技能相机不一致的路径：在 Forge 相机事件、位置和角度修改之后记录实际世界视图，传递横向倾斜；配套只狼端用同一方向、倾斜和投影绘制，避免用较早的普通视角判断人物和特效遮挡。旧的不带倾斜的 Fabric 帧仍兼容新只狼端；本次安装器的 Fabric 路径使用旧发布的完整 DLL/JAR 对；不发布新 Fabric 载荷。

AAA/Effekseer 粒子的原始颜色、动画、材质和 VIX 后处理保持原流程。桥接额外将同一粒子几何绘制到私有覆盖颜色/深度目标，通过同一渲染线程内的原生 OpenGL 深度写入覆盖获得真实几何深度。之后仅向世界深度合并存在粒子覆盖的像素，不改写原世界 RGB/HUD；泛光和色差边缘有限传播邻近粒子深度（最多 16 个 MC 窗口像素）。没有粒子实例、桥接关闭或手臂/HUD 阶段不执行辅助绘制；结束技能的下一帧丢弃旧覆盖。异常退出会释放深度覆盖并恢复 GL 状态，原模组特效仍继续绘制。

这是对视频中斩击、光圈、泛光和运镜路径的适配，用户已确认 preview.6 测试通过。辅助绘制有额外 GPU 成本；其它模组版本、大范围后处理变形及强烈透明重叠仍需单独验证，不能保证任意第三方特效均兼容。本次发布和安装器不包含原生 Boss 血条移动或切换键，血条保持原位置。

### preview.5 桥接性能优化

此前只狼日志出现同一合成帧中多次深度快照复制；该计数指出一个性能热点，不能独自证明完整的帧率瓶颈。配套原生端复用已经执行的同一个不可变深度来源，跳过后续批次的重复 GPU 复制。复用键包含生产该来源的命令列表执行版本：即使在同一呈现帧重放同一命令列表，也会刷新；可变原生深度继续每次复制，资源重建后清空缓存。日志增加 `immutableReuses`，便于实机查看命中情况。GPU 回归同时检查多次请求复用、下一执行版本更新、真实方块遮挡、可变深度和资源重置。

MC 窗口桥接时默认关闭垂直同步等待，避免后台窗口与只狼分别等待显示刷新；已有的桥接 60 FPS 上限保留。关闭性能开关或解除桥接后恢复 MC 当前垂直同步选项；全屏切换和选项重新应用也遵循这个临时策略。保留鬼泣/VIX 原后处理流程，没有关闭粒子、泛光或扭曲。

原生快照优化需要此次重新构建的 `dist/SekiroCraft-Passthrough/dinput8.dll`；单独更新 Forge JAR 只包含 MC 窗口优化。两端通信仍为 ABI v3。离线检查不代表实机 FPS 提升已经量化，需在相同位置、相同技能、相同分辨率下比较桥接开启/关闭及性能开关开启/关闭。

### preview.4 可选适配启动修复

preview.3 的可选 VIX 检查请求未转换的字节码，Forge 的 ModLauncher/Mixin 0.8.5 不支持该调用，导致 MC 在 Mixin 准备阶段崩溃。preview.4 改用该服务支持的默认字节码读取入口，不定义或初始化 VIX 类，缺少可选目标时仍跳过适配。使用实际 Mixin 0.8.5 的 `MixinLaunchPluginLegacy` 复现旧异常，并通过生产检查方法验证实际 VIX 字节码、缺失目标和读取失败；该离线回归不等于完整游戏启动验收。保留下述黑屏顺序修复，第三方 JAR 保持原文件。

### preview.3 鬼泣/VIX 黑屏修复（preview.4 保留）

针对实际安装的 `DevilMineCraft 1.0.5-no-startup`、`VIX 1.20-1.3.6` 和 `AAA Particles 2.3.2` 组合。VIX 的原始 `RenderPost` 在 MC 世界和手臂绘制之后执行；桥接原先已将世界与 HUD 分开，导致全屏后处理把不透明黑底写进 HUD，遮住只狼。桥接开启时，在世界采集、清屏前运行原来的 VIX 后处理，再将后续的重复调用推迟到下一帧。继续使用原模组的特效、处理器、队列和资源，不修改第三方 JAR；单独玩 MC 或关闭桥接时保留原路径。手臂阶段新排队的世界特效最多推迟一帧。

回归检查复现旧路径的不透明黑底，并检查新路径中蓝色特效 RGB、世界深度、HUD 透明度、重复执行保护、每帧恢复和处理失败回退。仍需实机验证右键、连续蓄力、粒子/扭曲/泛光、背包、F5 及停止技能后只狼场景恢复，不能把离线通过理解为所有鬼泣动作已验收。只狼原生 Boss 血条移动和左右切换键按用户安排另行定位，此预览未实现。

### preview.2 启动修复

用户在 Forge 47.4.26 上启动 preview.1 时，加载画面触发 `NativeBridge.clockMs()` 的 `UnsatisfiedLinkError`。Forge 可以在客户端初始化的排队任务执行前调用渲染钩子，因此 JNI 尚未加载就进入了桥接轮询。preview.2 在没有可用 JNI 会话时跳过渲染轮询、客户端 Tick 与集成服务器 Tick；JNI 初始化失败也保持 MC 可用。另补齐 `pack.mcmeta`，消除模组资源包元数据缺失提示。

此修复通过未加载 JNI 的真实客户端入口回归；仍需用户重启 Forge 47.4.26 验证主菜单、进入世界及实际桥接。此次报告未指向其他模组冲突或 Java 21 不兼容，无需仅因这次报错降级 Forge。

## 验收顺序

- 不启用桥接时，MC 能进入世界、退出并再次启动，背包与原版输入正常。
- 启用后确认方块、手臂、HUD、第三人称模型、下蹲、屏幕菜单可见，键位与鼠标侧键可转发。
- 测试地面放置、碰撞、箭、TNT、生物蛋，近战与远程攻击，盾牌、装备、玩家生命同步。
- 测试传送加载、死亡/复活、动画切换、钩索结束后的镜头恢复。
- 测试姿态条开关及重启后保存；关闭桥接、退出世界时输入与生命保护都解除。
- 基础测试通过后，逐个加入 **Forge 1.20.1 版**拔刀剑、枪械、动画库及其依赖。Fabric Refabricated 版本不能直接搬过来。渲染优化、光影和自定义模型组合需单独验证。
- 保存 Forge 实例 `logs/latest.log`、崩溃报告及只狼桥接日志，记录所用 Forge、模组版本与触发步骤。

## 实现边界

共享代码由 `syncSharedJava` 在构建时从 `mc/src/main/java` 复制到构建输出，排除 Fabric 初始化、指令、实体注册与 HUD 入口。Forge 使用独立的 `BridgeClient`、`CombatBridge`、`ForgeBridgeMod`、`ForgeClientEvents`。本次共享源码补充相机/通信支持，Fabric 已发布的游戏文件不重新构建或替换。

初始化、指令、实体注册、HUD、世界渲染采集、碰撞和破坏透明度存在独立适配器。若这些原 Fabric 文件或 Mixin 配置后续改变，`verifyAdapterBaseline` 会拒绝继续构建，要求先同步 Forge 适配逻辑，再更新 `adapter-baseline.json`，避免悄悄漏掉新修复。

实体类型通过 `DeferredRegister` 注册，属性和渲染器分别在 mod bus 事件里注册；客户端指令、客户端及单人服务器 Tick 通过 Forge runtime bus 接入。ForgeGui 的姿态条与暗角由 Forge HUD 事件管理。Forge 专用世界采集点在 `AFTER_LEVEL` 的全部模组绘制之后、手臂清除深度之前，包含世界末尾特效。Minecraft 停止时关闭 JNI 和帧缓冲。Mixin 配置仅在客户端环境加载，仍覆盖本地集成服务器；专用服务器不启动客户端/JNI。

Forge 的 Mixin 0.8.5 不支持对默认接口方法的原注入方式，地形碰撞改为在真实 `World` 上实现对应的接口方法，使用 Forge 的五参数碰撞迭代器保留原版方块碰撞，再追加已采样地形。破坏贴图透明度在同名渲染阶段启动后处理，避开编译器隐藏的内部 lambda。两处只在桥接原有作用域内改变行为。

原来的已知限制继续存在：只狼受击/特效导致的闪烁尚未彻底解决；真正的 MC 动作忍杀接口、NPC 对 MC 生物的完整仇恨与导航尚未完成。血量扣空自动结算 Boss 阶段仍为临时模式。Forge 版不能据此宣称所有第三方战斗/渲染模组已经兼容。

## 构建

在项目根目录运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/build-forge.ps1`。首次需要下载官方 Forge 与 Loom 依赖；之后可加 `-Offline`。

脚本复用当前已构建的配套 JNI，如不存在会先构建原生端。编译原生性能改动时须先执行 `scripts/build-native.ps1`，再执行 Forge 构建脚本。Forge 产物位于 `dist/forge-preview/preview.6/`，包含 JAR、配套 DLL、这份说明、SHA256 和验证记录。可传 `-VixJar` 指向实际 VIX JAR，额外检查其真实后处理注入点；`-RuntimeMixinJar` 可指定启动器使用的 Mixin 0.8.5 JAR，让可选适配启动回归使用实际运行版本。不会安装游戏文件、启动游戏或提交 GitHub。
