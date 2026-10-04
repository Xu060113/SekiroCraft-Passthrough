# gameplay2-combatfix1

两端配套更新，只修改 Passthrough 仓库。保留 inventoryfix1 的背包输入修复。没有启动只狼或 MC；离线测试不等于实机完成验收。

本轮离线结果：32 项原生战斗夹具、63 项位置钩子/碰撞/角色隐藏、160 项 D3D11、30 项独立 HUD、44 项地形、45 项输入、28 项 Java/JNI、17 项音效、28 项姿态协议/血量/受伤反馈、13 项隔离目录中的配套更新/回滚检查通过。实际双进程通信通过；开发/发行版 Minecraft 注入点分别通过 246/248 项检查。

## 修复范围

- MC 1.20.1 的裂纹层（RenderPhase.method_23505）原版使用 DST_COLOR/SRC_COLOR 保留颜色，但 alpha 用 ONE/ZERO，覆盖了方块的透明度。桥接期间仅将该层 alpha 改为 ZERO/ONE，保留目标方块已有覆盖率；裂纹 RGB 不变，玻璃/水/GUI 混合也不变。注入到这个版本的精确静态方法，开发及 remap JAR 均检查。
- 延迟 D3D11 上下文的绑定、绘制和 FinishCommandList 元数据使用独立短锁。不会再因 Present 正在上传纹理/合成而放弃边界，或递增全局 generation 丢掉其它线程记录。即时上下文仍采用零等待；窗口调整按 scene→metadata 顺序清理。
- MC 接管期间持续隐藏原生角色，不随一次世界合成失败释放。Draw 位被原生攻击更新重设后，在相机尾部、场景绘制前重新隐藏；关闭/死亡/换角色恢复原有位，不写入已被替换的旧角色地址。
- physics-v2 携带完整采样覆盖盒和最多 4096 个 MC 方块形状。原生 ChrPhysics 最终位置钩子验证 owner/module，对 NPC 也执行扫掠；13×12×13 格区域每 50ms 发布，NPC 共用一次每 16ms 的非等待读取。过期、未知、跨 epoch 和超 3 米脚本位移保持原生结果。不是 Havok 刚体或原生导航网格；身体按 MC 人形尺寸近似，初始实体重叠尚无推离处理。
- combat-state-v2 发布姿态剩余值/最大值、NoDeath/NoPostureConsume 与当前 Boss node。MC 命中依次调用原生 HP 和姿态 setter，重复序号不重放。20 MC 伤害暂对应一条原生生命/姿态。MC HUD 以 `1 - remaining/max` 显示姿态损耗，零剩余不标记为已确认忍杀机会。
- 原生玩家 HP 下降后发 vanilla EntityDamageS2CPacket，播放 MC 受伤音效及 hurtTime/闪红。先扣除已确认的 MC 伤害/回血增量，避免一个伤害两次反馈；不再扣一遍生命。没有伤害来源或击退向量映射。

## 原生接口证据与未完成项

本机已核对 1.06 exe 指纹。缓存加载代码的反汇编确认：`0xBD6710` 的 RCX=ChrData、EDX=新的剩余姿态、R8B=恢复历史处理标志；读取 `+0x148/+0x14C`，写回 `+0x148` 并更新姿态历史。运行时检查入口 16 字节及 `0xBD679A` 的写回指令，任一不匹配即禁用姿态写入。该 setter 的降低分支保留 NoPostureConsume。参考 [SekiroTool 源码](https://github.com/borgCode/SekiroTool) 的 FreezeTargetPosture/ChrData 定义，最终 ABI 取本机反汇编，不取项目宣传描述。

**Boss 忍杀和完整原生受击尚未完成。** 已查本地引用项目，缺少可用于本机的已验证 ApplyDamage/忍杀调用签名、攻击上下文布局、Boss 可忍杀判定及阶段结算入口。HP/姿态 setter 不会构造原生攻击上下文，也不能保证触发受击动作、弹反、忍杀动画或事件奖励。不能把清 HP、修改 Boss node/NoDeath 或写胜利事件旗标当作实现。需要下一次有针对性的游戏内接口捕获，分别验证实际被击中与实际忍杀时的参数和线程，再接入 MC 命中事件。

MC 原生反馈使用 [Fabric/Yarn 1.20.1 LivingEntity](https://maven.fabricmc.net/docs/yarn-1.20.1+build.10/net/minecraft/entity/LivingEntity.html#onDamaged(net.minecraft.entity.damage.DamageSource)) 的伤害通知路径；本机映射 JAR 字节码确认其声音/hurtTime 行为。

## 回来后的最短验收

1. 先用普通人形敌人试走过一格墙、两格墙、半砖台阶；走远约 6 MC 格后碰撞不保证。观察日志 `npcBlockCorrections` 是否增加。方块放入已存在敌人体内不在这次的保证范围。
2. 第一人称让敌人攻击，连续移动、转头、跳跃；观察方块是否闪现、是否看到狼。日志不应持续出现 Finish busy 导致丢列表；世界仍失败时记录 `world=` 原因。
3. 攻击敌人，观察其原生血量、MC 目标姿态条以及 `postureHits`；生存玩家受原生伤害应出现一次 MC 受伤音效/闪红，创造模式不应出现受伤反馈。
4. Boss 保留原本 NoDeath/阶段规则。本版不能通过 MC 点击完成忍杀，不在验收结果中写成通过。背包单击、拖拽、右键放置各做一次，检查旧功能是否回退。
