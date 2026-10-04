# SekiroCraft-Passthrough

独立的 **真实 Minecraft 1.20.1 + 只狼双进程桥接项目**，首版 0.1.0。原创 C++ 项目单独保留在相邻的 `SekiroCraft-Original` 仓库。本项目没有把 MC 重写成 C++。

当前已生成只狼端 `dinput8.dll` 和 MC Fabric 模组 JAR，并通过离线通信、JNI、D3D11 合成与注入点检查。首次 MC 实机启动发现天空渲染注入选中了静态重载，已修复为精确描述符，并新增回调签名及发行 JAR 映射检查。**尚不能称为完整移植。** 修复后的实际 MC 启动、帧导出、输入与只狼整合仍待验收。

后续修复了共享内存忙碌被误判为断线造成的闪屏、全屏暗角使透明 HUD 变黑，以及 F7/F8/F9 同时收到物理键与窗口消息时重复触发的问题。更新包含只狼 DLL 与 MC JAR，两款游戏正常退出后再安装；离线回归检查通过，实机效果继续验收。

本轮修正了玩家绘制标志、MC/只狼按键冲突、背包软件光标、第三人称准星射线与 MC 走跑动画，并加入**需要实机验收的玩家碰撞约束和创造飞行**。第一人称尚未完成；不能用 MC 的 F5 代替只狼相机控制。新功能说明与测试入口见 [本轮功能验收](docs/FEATURE_TEST.md)。

## 工作方式

只狼端读取原角色位置、相机与场景深度；MC 端运行真正的方块、背包、合成、红石、机器和生物逻辑。两个进程使用当前 Windows 会话中的命名共享内存交换状态和画面，通道默认 `default`，没有网络监听。

MC 的天空、云、天气背景及雾在桥接激活期间关闭。世界 RGBA、深度和手部/HUD/菜单 RGBA 分层导出，使用三组 PBO 和零等待 GPU fence。只狼端用 D3D11 深度合成与相机重投影，恢复原有渲染状态；不依赖 GTA ScriptHookV，也不要求安装 ReShade。

只狼控制地面移动和镜头，MC 玩家随其位置移动。F8 开启 MC 操作；左键挖掘/攻击、右键放置/使用、滚轮和 1–9 选择物品、**I 背包、O 丢弃、J 换手**。E/Q/F 保留给只狼，避免影响钩锁。MC 界面打开时转接鼠标、键盘和字符输入，并隔离只狼键鼠输入。光标由最终合成层绘制。原角色只在有效 MC 帧成功合成后关闭其自身的 Draw 标志；断线、过期画面、失去场景/焦点、死亡或 resize 时恢复。MC 模组仅在单人世界内执行 `/sekirobridge on` 后启用，离开世界会撤销。

创造模式在 F8 开启时用 **F6** 切换飞行，WASD 移动、Space 上升、Shift 下降、Ctrl 加速。只狼位置更新钩子只有在本机 1.06 的机器码完全匹配后才安装，飞行还需通过重力/滞空计时接口校验。MC 传回附近方块的真实 VoxelShape，供玩家包围盒做扫掠碰撞。钩子、坐标反馈及状态恢复已通过离线夹具；游戏内效果与引擎的速度/落地状态仍待验证。F7 可关闭这两个实验功能，或退出游戏后设置 `player_features=0`。

MC 建造潜行改用 Alt，避免只狼 Shift 跑步时 MC 模型同时下蹲；背包内 Shift 转移物品照常。

## 当前边界

- 没有原生地形 raycast：只狼地面尚不会转换成 MC 屏障。MC 掉落和生物仍受 MC 自己的世界地形约束。
- 玩家碰撞采用坐标更新阶段的包围盒约束，**没有在 Havok 中创建刚体**；敌人、钩锁锚点、弹道和原生地形没有新增 MC 碰撞。协议不宣称 `native-block-collision` 能力。MC 方块的侧面、落地、天花板、半砖形状有离线计算检查，实机需验证，尤其是离开悬空平台后的原生下落速度。
- 第一人称尚未实现。参考的旧第一人称脚本针对 1.04；需要继续定位本机 1.06 的相机写入路径，再同步两端视图和手部显示。
- 没有跨游戏伤害、爆炸、敌人代理和骨骼动作同步；MC 中的伤害/生物行为不等于已接入只狼战斗。
- 尚无地图、存档槽、原生暂停菜单识别，亦未验证第三方 MC 渲染模组或手柄。首轮用独立创造虚空世界；F8 关闭后使用只狼原有操作。
- 只支持本机已校验 SHA256 的只狼 1.06；MC 固定 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.2。现有 Forge/OptiFine 实例不能直接加载这个 Fabric 模组。
- 保存由真实 MC 单人世界负责，不导入、修改或转换原创项目的 SCW 存档。`y_offset` 必须使本场景映射到 MC 的 -64…319 高度范围；切换地图需要单独校准，不自动搬动已有方块。

## 构建

本机 Windows x64、JDK 21，Java 字节码目标 17。便携 C++ 编译器固定 llvm-mingw 20260922，Gradle 固定 8.8，Fabric Loom 固定 1.6.12。工具和依赖可在两个项目间共享缓存，Git 元数据、代码、存档和构建结果相互独立。

```powershell
& .\scripts\bootstrap.ps1
& .\scripts\build-passthrough.ps1
# 已缓存依赖时：
& .\scripts\build-passthrough.ps1 -Offline
```

构建脚本仅编译和运行离线夹具，不调用 `runClient`，不启动只狼或 Minecraft。Minecraft 开发依赖下载到忽略的 `.cache`，不进入源码包或模组发行包。发行包包含 Fabric API 的 Apache 2.0 依赖。

产物：`dist/SekiroCraft-Passthrough-0.1.0.zip`；验证记录：`build/verification.json`。未来部署前请读 [回来后的验收步骤](docs/RETURN_TEST.md)。

## 与参考项目的关系

架构参考 [universal-modder 的 Minecraft/GTA5 passthrough 示例](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough)。本项目重新编写 MC 1.20.1 桥接和只狼适配，通信使用 Win32 共享内存及自有 JNI，合成使用本项目 D3D11 hook。没有复制示例的 GTA 插件或 MC 源码，也没有分发 Minecraft/只狼程序、地图或纹理。
