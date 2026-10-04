# 回来后的验收与恢复

2026-10-04：原创仓库、0.4.0 发布包与存档副本在相邻 `SekiroCraft-Original` 中；桥接安装与原创备份由本项目 `runtime/installation.json` 记录。首次 MC 启动的 `WorldRendererMixin` 静态重载崩溃已修复，修复只涉及 MC JAR，不需要重新切换已安装的只狼 DLL。实机整合继续按下列步骤验收。

随后发现的黑屏交替与 F8 重复触发修复涉及两端：正常退出 MC 与只狼后，先 `-Action Restore`，再 `-Action Install` 更新 DLL，并备份/替换 MC 桥接 JAR。完成更新后再启动游戏。新日志含 `peerBusy` 与 `presentBusy` 计数，F7 菜单可以查看它们。

## 准备两个进程

1. 先正常退出只狼。解压新发行包，或进入本源码项目。运行 `scripts/switch-sekiro.ps1 -Action Install`。脚本检查只狼指纹和已有 DLL 身份，备份现有原创 DLL，再安装桥接 DLL；不启动游戏。
2. 建立一个独立的 **Minecraft 1.20.1 Fabric Loader 0.16.10** 启动实例。不要将 JAR 放入当前 Forge/OptiFine 实例。给新实例设置独立游戏目录，按该路径运行 `scripts/prepare-minecraft.ps1 -GameDirectory '你的新实例游戏目录'`；发行包提供桥接模组和 Fabric API。
3. 你自行启动 MC，创建创造模式的单人虚空世界。可以使用超平坦的“虚空”预设；正常地图也可显示，但其原有地形会一起合成进只狼。不要在有价值的 MC 存档中尝试。
4. 在 MC 输入 `/sekirobridge on`，然后自行启动只狼，进入一个可自由活动的场景，切回只狼窗口。启动顺序可以颠倒；`/sekirobridge on` 必须在本次世界加载后执行。
5. 只狼 F7 打开诊断；观察 player/camera/depth 是否就绪，以及 MC 是否 connected。关闭 F7，按 F9 请求 **由 MC 原引擎** 在当前视线四米处放置草方块。它可能被原场景墙体挡住；在开阔处尝试。按 F8 开启 MC 操作。

两边的 `channel` 都默认 `default`。MC 配置位于其游戏目录的 `sekirobridge/bridge.properties`；只狼端位于 `sekirobridge.ini`。同一个 Windows 会话只运行一对游戏，额外实例使用不同通道。

## 分项验收

- 方块、史蒂夫、镜头位置和遮挡：绕着草方块移动，确认靠近/远离、前后遮挡、转动镜头是否正确。相机 roll 尚未适配，快速运动的重投影可能出现缺边。
- F8 开启时，左键 MC 挖掘/攻击、右键使用/放置、滚轮/数字选择、E 背包；原版只狼移动和镜头继续控制位置。E/Q/F、数字和 MC 鼠标动作由 DirectInput 标准格式隔离，游戏内仍需确认是否有其他输入路径。
- MC 背包界面：鼠标选物品、Shift 转移、输入创造搜索文字，Esc 关闭界面；确认只狼没有同时攻击或移动。游戏内输入隔离和光标恢复尚待实机验证，默认支持键鼠。
- 真实 MC 功能：用其原版物品做工作台、箱子、熔炉、红石。这些都由 MC 自己运行和保存；只狼没有与新增方块的原生碰撞，MC 与只狼的战斗也不联动。
- 停止桥接：退出 MC 世界、关闭 MC、Alt+Tab、只狼死亡、调整窗口大小，确认旧画面不持续覆盖、原角色显示和原版输入恢复。原生暂停菜单检测尚未完成；打开只狼暂停菜单前先关闭 F8，必要时 MC 执行 `/sekirobridge off`。
- 世界高度：只狼坐标映射为 MC `(x, y+y_offset, -z)`。默认 `y_offset=128`；若测试位置超出 MC 高度，关闭只狼后调配置重新试验。不要在已有方块世界中随意更改这一值。

## 恢复原创安装

关闭只狼后，在桥接项目执行：

```powershell
& .\scripts\switch-sekiro.ps1 -Action Restore
```

它校验当前文件归属，恢复**安装桥接前那一份 DLL**，移除本项目新增的配置。不会删除 MC 世界、原创 SCW 存档、游戏存档或游戏资源。若 DLL 被其他模组替换，脚本会保留文件并停止，避免覆盖。

之后可以继续使用原安装，或进入原创项目运行它自己的 `scripts/install.ps1` 升级到保留的 0.4.0。两个项目同时有发布包，但只狼目录只能加载其中一个 `dinput8.dll`。

日志：桥接项目 `runtime/passthrough.log`；MC 游戏目录 `logs/latest.log`。只有收到你的游戏内测试结果后，才能判断这一版在本机的真实整合表现。
