# SekiroCraft-Passthrough

独立的 **真实 Minecraft 1.20.1 + 只狼双进程桥接项目**，首版 0.1.0。原创 C++ 项目单独保留在相邻的 `SekiroCraft-Original` 仓库。本项目没有把 MC 重写成 C++。

当前已生成只狼端 `dinput8.dll` 和 MC Fabric 模组 JAR，并通过离线通信、JNI、D3D11 合成与注入点检查。首次 MC 实机启动发现天空渲染注入选中了静态重载，已修复为精确描述符，并新增回调签名及发行 JAR 映射检查。**尚不能称为完整移植。** 修复后的实际 MC 启动、帧导出、输入与只狼整合仍待验收。

## 工作方式

只狼端读取原角色位置、相机与场景深度；MC 端运行真正的方块、背包、合成、红石、机器和生物逻辑。两个进程使用当前 Windows 会话中的命名共享内存交换状态和画面，通道默认 `default`，没有网络监听。

MC 的天空、云、天气背景及雾在桥接激活期间关闭。世界 RGBA、深度和手部/HUD/菜单 RGBA 分层导出，使用三组 PBO 和零等待 GPU fence。只狼端用 D3D11 深度合成与相机重投影，恢复原有渲染状态；不依赖 GTA ScriptHookV，也不要求安装 ReShade。

只狼控制移动和镜头，MC 玩家随其位置移动。F8 开启 MC 操作；左键挖掘/攻击、右键放置/使用、滚轮和 1–9 选择物品、E 背包、Q 丢弃、F 换手。MC 界面打开时转接鼠标、键盘和字符输入，并隔离只狼键鼠输入。原角色只在有效 MC 帧成功合成后隐藏；断线、过期画面、失去场景/焦点、死亡或 resize 时停止合成并恢复原角色。MC 模组仅在单人世界内执行 `/sekirobridge on` 后启用，离开世界会撤销。

## 当前边界

- 没有原生地形 raycast：只狼地面尚不会转换成 MC 屏障。MC 掉落和生物仍受 MC 自己的世界地形约束。
- 没有只狼原生碰撞体创建接口：只狼角色和敌人仍可穿过新增 MC 方块。协议明确不宣称 `native-block-collision` 能力。
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
