# GTA passthrough 与 SkyCraft：本项目采用的组合

2026-10-04 核对的是固定提交中的实现，不将设计草稿当作已经完成的功能：

- universal-modder：`8607693be42ce02442251f05e0495f58c2b77e5d`
- SkyCraft：`bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32`

## 两个参考项目实际提供什么

GTA 示例将 MC 世界颜色、深度和 HUD 分开导出，每份图像保存捕获时的相机与时间。它发送按下/释放事件，并把 GTA 地面映射为 MC 屏障块，给原版放置射线提供支撑。其默认行走仍由 GTA 主导，只有特定分支改由 MC 驱动，不能照搬来满足本项目的 MC 操作要求。见 [FrameExporter](https://github.com/rehan-remade/universal-modder/blob/8607693be42ce02442251f05e0495f58c2b77e5d/examples/minecraft-gta5-passthrough/mc/src/client/java/dev/rehan/passthrough/client/FrameExporter.java#L179)、[WorldBridge](https://github.com/rehan-remade/universal-modder/blob/8607693be42ce02442251f05e0495f58c2b77e5d/examples/minecraft-gta5-passthrough/mc/src/main/java/dev/rehan/passthrough/WorldBridge.java#L55) 和 [PlayerSync](https://github.com/rehan-remade/universal-modder/blob/8607693be42ce02442251f05e0495f58c2b77e5d/examples/minecraft-gta5-passthrough/mc/src/client/java/dev/rehan/passthrough/client/PlayerSync.java#L34)。

SkyCraft 当前已经导出 MC 方块/流体网格，交给 Skyrim 绘制；FrameExporter 主要传 HUD 覆盖层。原生 Havok 碰撞则以区域三角形与细分体素传给 MC。玩家物理由 MC 计算，进入未知碰撞区域时等待就绪；相机朝向还有宿主驱动的适配。因此它不是简单的“透明 MC 窗口叠在游戏上”。见 [WorldExporter](https://github.com/chasmlol/SkyCraft/blob/bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32/fabric/src/client/java/dev/skycraft/client/render/WorldExporter.java#L58)、[FrameExporter](https://github.com/chasmlol/SkyCraft/blob/bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32/fabric/src/client/java/dev/skycraft/client/FrameExporter.java#L14)、[Collision](https://github.com/chasmlol/SkyCraft/blob/bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32/skse/src/Collision.cpp#L660) 和 [SkyClient](https://github.com/chasmlol/SkyCraft/blob/bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32/fabric/src/client/java/dev/skycraft/client/SkyClient.java#L256)。

## mc-owner2 落地的组合

| 部分 | 只狼版本的实现 | 对当前问题的作用 |
| --- | --- | --- |
| 人物控制 | MC 原版移动、物理、背包和物品使用；只狼角色跟随 MC | 避免两套操作同时争抢角色 |
| 输入 | 有序按下/释放、Windows 双击、位置和修饰键；快照只修复漏掉的释放 | 防止背包快速点击被重复或撤销 |
| 画面 | 世界/深度/HUD 分层；已完成图像绑定捕获相机；按实际 D3D 命令列表执行关联原生深度 | 避免跑跳时新相机配上旧世界图像 |
| 地形 | 固定世界格采样，客户端和单人服务器共享临时碰撞形状 | 避免地面格随人物移动而漂移 |
| 地形就绪 | 区分未知格和已采样的空格；未知范围暂缓本玩家移动与坠落，收到覆盖采样后自动释放 | 防止初始化或短暂采样中断被当作无地面 |
| 放置 | 已采样原生地面提供虚拟顶面命中，后续由 MC 原版放置和服务器校验 | 支持在原生地面放下第一块 MC 方块 |

这里借鉴的是接口分工和状态管理，代码独立适配 MC 1.20.1/GLFW 与只狼 1.06。没有复制两个参考项目的源文件，也不需要安装 ScriptHookV、SKSE 或 ReShade。

地形就绪不等于自动造地板：新射线确认未命中时立即清除该格碰撞，允许真实空处坠落。未知时不做远距离传送或自动抬升；只有已知地面的浅穿透可以在阶高范围内修正。关闭桥接立即解除限制。

## 尚未移植的部分

后续 `gameplay1` 加入原生生物代理和血量增量回传。原生实体从已校验的物理回调观察，通过当前 WorldChrMan handle resolver 核对身份；字段和分类参考 [sekiro-coop live.rs](https://github.com/mstampfli/sekiro-coop/blob/main/crates/sekiro-sdk-sys/src/live.rs) 与 [soulsmods Sekiro EMEDF](https://soulsmods.github.io/emedf/sekiro-emedf.html)。玩家 NoDamage 位另外与本地 SekiroTool/ElaDiDu 1.06 定义核对。HP setter 的 ABI、clamp 与 NoDeath 分支由本机已加载 1.06 代码的离线反汇编确认，运行时再次检查指纹。没有复制参考项目源文件，没有调用猜测的 ApplyDamage 函数。

MC 中不可见、禁止保存的命中实体接收原版近战/投射物/爆炸/怪物伤害。HP 与创造无敌由独立双向状态通道传递，伤害命令带递增序号，玩家伤害/治疗是累计增量并带确认，避免反馈环。实体地面扩展到两端同一维度的 MC 生物、TNT 和掉落物；真实 OpenAL 声音在 MC 后台播放。

当前仍使用分层图像合成，**没有完成 SkyCraft 式的 MC 网格原生渲染器**。这一路线需要另建网格/材质传输、只狼场景绘制与光照接口，以及角色动画和透明材质处理；不能只换几个地址。

只狼射线高度采样仍只描述附近地面；“已知格”不代表墙壁、屋顶或整个垂直空间已知。SkyCraft 的 Havok 区域导出依赖 Skyrim/SKSE 的对象结构，GTA 的隐藏碰撞道具依赖 ScriptHookV。完整墙顶、多层地形、只狼 NPC 碰撞 MC 方块、原生受击/姿态/忍杀及原生 NPC 反击 MC 怪物仍需独立实现。当前 HP 交互不等于完整战斗管线。

右键原生地面当前只保证普通方块的顶面目标。需要真实支撑块的特殊方块、原生墙面放置和挖掘原生地形不在本版范围内。非整数高度可能产生不到一个 MC 格的离地间隙。

离线回归覆盖输入、地形就绪、原版碰撞反例、放置目标和相机/深度配对。游戏内效果按 [FEATURE_TEST.md](FEATURE_TEST.md) 验收；没有由助手启动两款游戏。
