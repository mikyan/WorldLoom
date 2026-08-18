# 存档与公开回放

文档状态：Implemented v2<br>
更新日期：2026-08-19

## 1. Run 目录

`SaveCoordinator` 是共享应用层的存档入口，支持：

- 从固定内容版本创建多个 Run；
- 列出 Run 的世界、名称、生命周期、最后事件序列和诊断；
- 继续、重命名、归档与恢复归档；
- 查看已完成 Run 的最终状态、结局和公开时间线。

目录元数据不是世界事实。Run 的 `ACTIVE` / `COMPLETED` 等状态来自 EventLog 中的生命周期 Event；归档只改变目录可见性，不能改变游戏结局。

## 2. 继续游戏校验

继续 Run 时依次执行：

```text
world contentVersion 匹配
→ save/event schema 可解码
→ Snapshot 可选校验
→ Event sequence 连续
→ Reducer 从 Snapshot 尾部或完整 EventLog 重建
→ 重建状态与 Run/World 身份一致
```

Snapshot 是缓存。Schema 不兼容、JSON 损坏或身份/序列不匹配时，系统忽略它并从完整 EventLog 重建，同时在界面显示诊断。EventLog 无法解码、序列断裂或重放被 Reducer 拒绝时，系统拒绝继续，不用默认值掩盖损坏。

## 3. 公开回放

`worldloom.public-replay/v1` 只从 EventLog 生成，包含：

- 公开事件摘要与稳定 Event 类型；
- EventId、sequence、correlation 和 causation；
- 判定使用的 Random Record、骰值、总值与结果档位；
- 行动、场景、时间、活动、旅行、Behavior 派生、NPC 公开动作和已揭示的固定知识摘要。

离线校验会重新运行 Reducer，并要求结果与当前权威状态相等。默认投影不读取以下分区：

- 平台凭据保险箱和 Provider 配置秘密；
- GM/NPC 模型请求与最终正文；
- Agent Session、私有记忆、信念、目标和上下文检查点；
- 未通过公开 Command/Event 揭示的世界秘密。

## 4. 时间线与界面

常规 Presentation 只保留最近 200 个事件，Compose `LazyColumn` 按需布局；玩家可每次向前读取最多 200 条。事件卡显示摘要、类型、因果命令和可选随机审计。角色字段、库存、状态、关系、任务与进度钟使用 Definition 驱动的横向卡片，窄屏可横向滚动，桌面可显示更多卡片。减少动态效果路径用静态文字替代持续旋转的加载反馈。

## 5. 主持人历史与中断恢复

主持人回合是按 Run 隔离的公开表现记录，不是 EventLog 世界事实。继续游戏后，界面会分页恢复玩家输入、公开主持叙事、澄清、取消和归一化失败信息，并校验每条叙事引用的事件范围不超过当前 EventLog。

遗留的 `ACCEPTED` / `RUNNING` 回合按权威序列处理：

- EventLog 未前进：原回合标为可安全重试，新请求使用新 TurnId、相同输入并引用原 Turn；
- EventLog 已前进：已提交事实保持不变，只能启动禁用所有写工具的补叙述回合；
- 记录损坏或引用未来事件：只隔离该表现记录，不能借此修改或丢弃世界状态。

取消路径在不可取消的最小持久化区写入终态，再刷新历史。Provider 原始错误和模型私有正文不会直接显示；UI 只使用稳定错误分类告诉玩家能否重试或补叙述。

玩家定向 NPC 的公开发言属于 EventLog 事实，并随存档、回放和公开导出保存；它包含目标 NPC/Entity、场景、限长文本与幂等键。NPC 模型的私有最终正文仍不属于公开回放，只有后续 `npc.speak` / `npc.act` 工具提交的内容可见。

NPC 揭示知识时，EventLog 只保存知识 DefinitionId 与世界包固定的 `publicSummary`。私有正文不进入 Command/Event、主持历史或公开回放；重复揭示会复用既有公开事实，不产生第二条知识事件。

## 6. 主持人长期连续性

终态主持回合会按 Run 修复成只含玩家输入、公开主持叙事和已提交公开 EventId 的连续记录，并使用固定 `worldloom.agent.gm` 身份写入该 Run 的 Agent Memory 分区。重启后，主持上下文组合最后有效检查点、检查点后的原始公开回合尾部和当前 Presentation；其他 Run 的 GM 记录以及任意 NPC 的私密记忆不会参与召回。

达到回合数或上下文水位后，压缩任务冻结开始时的连续回合范围，在后台生成候选并校验 AgentId、范围、来源 Event 与非空摘要，再把检查点和公开情节记忆原子发布。候选失败或应用退出不会覆盖旧检查点；新回合直接使用旧检查点加当前原始尾部，不等待压缩。检查点只用于叙事连贯，不能恢复或修改 GameState；提示始终声明当前 Presentation、EventLog 结果和动态 Tool Schema 优先。

## 快速继续与自动存档证据

Run 目录保存最后持久化 Event 序列、终态 GM TurnId、保存状态和非权威保存时间戳。EventLog/GM Turn 的权威落盘与目录证据更新不在同一事务中：目录更新失败时，已提交的事实和回合仍有效，目录通过实际最大 Event 序列和最新 Turn 检测差异并显示“事实已保存、目录待修复”。修复只回填这些派生字段，不修改 EventLog、Snapshot、Turn 或 GameState。

快速继续只选择排序最靠前的未归档活动 Run，然后执行与普通继续相同的世界内容版本、EventLog 连续性、Snapshot 重建和生命周期校验。最近 Run 损坏时操作失败并保留可定位诊断，不静默打开次新的 Run。主持历史恢复随后把遗留 `ACCEPTED/RUNNING` Turn 分类为安全重试或只读补叙述；取消和 Provider 失败也根据是否已有权威事件提供对应操作。
