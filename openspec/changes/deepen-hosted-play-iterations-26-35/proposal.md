## Why

Worldloom 已能以 Fake 主持人把一个内置剧本从建角推进到结局，但玩家仍缺少可恢复的主持叙事历史、明确的 NPC 对话入口、跨长局连续性和面向中断/卡关的产品反馈。只有先把这些能力在两个手写世界中证明稳定，TXT/EPUB 识别和世界草稿生成才有可靠的可玩性目标。

## What Changes

- 持久化并投影主持人回合记录，使叙事、澄清、失败和权威事件范围在重启后仍可恢复。
- 为中断回合提供确定性的恢复、重试与 UI 反馈，不重复调用模型或重放已提交工具。
- 增加面向当前场景 NPC 的定向对话，公开台词进入事件历史，私有反思和知识继续隔离。
- 将 NPC 对话记忆、显式知识揭示和主持人可见结果纳入版本化边界。
- 把主持人长期连续性接入增量记忆与上下文检查点，不阻塞前台回合或取代 EventLog。
- 增加 Definition 驱动的教程、场景提示和无死路建议，以及快速继续、自动存档状态和中断反馈。
- 将 `station-ai` 扩充为第二个完整内置短剧本，使用同一 Runtime 完成多路线、NPC、存档、结局和回放。
- 在两个内置世界验收后，增加 TXT/EPUB 剧本识别工作区、来源映射和受控草稿试玩沙箱；草稿必须通过 `playable-world/v1` 才能试玩或安装。
- 第 35 轮建立新的候选门禁，验证手写世界与识别草稿共用相同加载、主持和回放边界。

## Capabilities

### New Capabilities

- `hosted-turn-history`: 主持人回合历史、叙事投影、澄清状态、中断恢复和安全重试。
- `directed-npc-dialogue`: 玩家定向 NPC 对话、公开事件、NPC 记忆和显式知识揭示边界。
- `gm-continuity`: 主持人长期记忆、增量检查点、上下文组装与可恢复压缩。
- `player-guidance`: Definition 驱动的教程、场景提示、卡关建议和可访问的行动入口。
- `resilient-run-resume`: 快速继续、自动存档状态、恢复诊断和中断反馈。
- `bundled-station-scenario`: 第二个完整内置空间站短剧本及其跨题材契约。
- `source-recognition-workspace`: TXT/EPUB 识别任务、来源片段映射、进度、取消和恢复。
- `draft-playtest-sandbox`: 隔离的世界草稿验证、快速试玩、安装边界和候选门禁。

### Modified Capabilities

无。现有 16–25 变更的公共要求保持兼容，新能力以增量 Schema 和迁移扩展。

## Impact

主要影响 `agent-runtime`、`application`、`persistence`、`ui-game`、`world-package`、`content-generation` 和两个 `contract-worlds`。将新增持久化迁移、版本化回合/对话/识别任务 Schema、公开 Presentation 投影和平台入口组装；不会让 LLM、UI、NPC 或生成器绕过 Command/Event 权威管线，也不会执行世界包任意代码。
