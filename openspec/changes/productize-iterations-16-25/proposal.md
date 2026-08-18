## Why

Worldloom 当前已经证明了动态 Definition、判定、EventLog、Agent Tool 和跨题材 Runtime 边界，但两个契约世界实际上都只有“一个数值字段 + 一个检定”，尚不能完成场景推进、资源选择、NPC 互动、目标和结局。世界识别与自动生成必须建立在一个人工制作、可完整试玩的黄金世界契约上，因此迭代 16–25 优先跑通内置剧本，再把生成与导入能力后移。

## What Changes

- 定义“可玩世界 v1”验收契约，覆盖开局、角色创建、场景、自由行动、判定、失败推进、时间、目标和结局。
- 将 CharacterCreationProfile 接入新游戏流程，使角色创建通过类型化 Command/Event 进入事实历史。
- 增加专职主持人 Agent 与游戏回合编排，使其负责理解玩家意图、必要时澄清、选择受限工具、调度公开 NPC/Behavior 后续，并把已提交事实组织成最终叙述。
- 增加世界配置驱动的时间、活动和旅行能力，用于休息、搜索、移动、消耗和途中事件。
- 增加最小通用冒险状态能力：物品、Condition、关系、Quest 和 Clock；能力仍由 manifest 显式启用。
- 将 Behavior AST 接入提交后 Event 调度，用确定性行为推进场景、目标、时钟和结局，并限制递归事件风暴。
- 将 NPC Agent 接入场景感知、消息和事件调度，公开行动走 Tool/Command/Event，私有上下文继续隔离。
- 把 `war-survival` 从契约夹具扩展为可从开局玩到多个结局的内置短篇，同时保持 `station-ai` 跨题材回归契约。
- 重做面向游玩的 Compose 信息架构，增加叙事流、场景、行动建议、状态、目标、时间线、存档与恢复。
- 建立内置剧本全路线自动验收、人工试玩、平衡、故障恢复、安全、性能和封闭 Alpha 门禁。

Brief/Corpus-to-World、TXT/EPUB 导入、世界工坊、真实模型生成和用户世界安装目录不属于迭代 16–25；现有基础代码保留，但计划延后到可玩世界契约稳定后的迭代 26+。

本变更不引入 Runtime 题材分支，不把战争世界字段写进通用状态，也不改变 `Intent → Command → Event → Presentation` 权威边界。

## Capabilities

### New Capabilities

- `playable-world-contract`: 定义并自动验证一个世界达到“可从开局玩到结局”的最小契约。
- `character-creation-flow`: 根据世界 CharacterCreationProfile 创建玩家角色并进入权威事件历史。
- `gm-agent-orchestration`: 定义主持人身份、可见上下文、意图理解、受限工具决策、场景节奏、NPC/Behavior 调度和事实一致叙述组成的原子游戏回合。
- `time-activity-travel`: 世界配置驱动的时间推进、活动结算、旅行和途中事件能力。
- `adventure-state-modules`: manifest 驱动的物品、Condition、关系、Quest 和 Clock 最小通用模块。
- `behavior-event-orchestration`: 从已发生事件确定性触发 Behavior AST，并通过 CommandValidator 提交效果。
- `npc-scene-orchestration`: 基于场景感知和事件策略唤醒私有 NPC Agent，并发布可见行动与对话。
- `bundled-war-survival-world`: 使用通用能力实现可完整试玩的内置战争生存短篇内容。
- `save-and-replay-experience`: 面向实际游玩的多 Run、恢复、叙事时间线和隐私安全回放体验。
- `closed-alpha-readiness`: 内置剧本全路线、跨端、性能、恢复、安全与发布门禁组成的封闭 Alpha 基线。

### Modified Capabilities

无。仓库当前没有 OpenSpec 主规格；本提案以新能力规格描述现有设计文档之上的可玩化增量。

## Impact

- 主要影响 `shared/application`、`shared/ui-game`、`shared/domain-world`、`shared/domain-rules`、`shared/rule-module-api`、`shared/rule-module-registry`、`shared/behavior-runtime`、`shared/agent-runtime` 和 `shared/persistence`。
- `contract-worlds/war-survival` 将升级为内置黄金世界包/夹具；`station-ai` 继续验证 Runtime 没有世界 ID 或题材分支。
- 需要新增版本化的场景、时间、活动、旅行、物品、Condition、关系、Quest、Clock、角色创建和结局相关 Command/Event/Definition/Projection Schema。
- 新能力必须由 manifest 模块或世界包配置显式启用；没有相应能力的世界不会注册 Tool、Command、Event 或 UI 投影。
- SQLDelight 将增加 Run 目录、角色创建、Behavior 因果和 NPC 调度所需迁移，同时保持既有 EventLog、Agent Session、Provider 配置和旧 Run 兼容。
- Compose UI 只通过 application coordinator 和只读投影交互，不直接修改 WorldState 或按 `war.*` 键读取题材数据。
- `shared/content-generation`、`shared/world-package` 的现有实现保持可编译和测试，但本阶段不继续扩展生成、导入和用户安装产品面。
