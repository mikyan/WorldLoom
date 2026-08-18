# 存档与公开回放

文档状态：Implemented v1<br>
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
- 行动、场景、时间、活动、旅行、Behavior 派生和 NPC 公开动作。

离线校验会重新运行 Reducer，并要求结果与当前权威状态相等。默认投影不读取以下分区：

- 平台凭据保险箱和 Provider 配置秘密；
- GM/NPC 模型请求与最终正文；
- Agent Session、私有记忆、信念、目标和上下文检查点；
- 未通过公开 Command/Event 揭示的世界秘密。

## 4. 时间线与界面

常规 Presentation 只保留最近 200 个事件，Compose `LazyColumn` 按需布局；玩家可每次向前读取最多 200 条。事件卡显示摘要、类型、因果命令和可选随机审计。角色字段、库存、状态、关系、任务与进度钟使用 Definition 驱动的横向卡片，窄屏可横向滚动，桌面可显示更多卡片。减少动态效果路径用静态文字替代持续旋转的加载反馈。
