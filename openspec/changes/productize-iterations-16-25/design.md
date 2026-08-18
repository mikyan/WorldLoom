## Context

迭代 1–15 已经建立 KMP/Compose 三端入口、动态 Definition/TypedValue、Command/Event、可审计判定、SQLDelight EventLog、受限 Agent Runtime、Provider 配置、Agent 记忆与压缩、NPC 基础运行时、`.worldloom` v1、Behavior AST 和内容生成基础。

然而当前应用的可玩面非常窄：`war-survival` 只有 `war.health` 和一次求生检定，`station-ai` 只有 `station.energy` 和一次完整性检定；UI 主要支持直接调整一个数值、触发检定、输入自然语言和回放。Runtime 尚未证明能够承载完整的角色创建、场景、时间、活动、旅行、物品、状态、关系、目标、NPC 互动和结局。

因此本阶段把人工编写的内置战争生存短篇作为黄金契约。只有 Runtime 能可靠加载并完整玩通这个世界后，后续世界识别/生成才有稳定、可验证的目标 Schema，而不是生成“结构正确但不可玩”的包。

## Goals / Non-Goals

**Goals:**

- 交付一个无需导入资料或生成世界、启动应用即可从开局玩到结局的内置短篇。
- 建立可由其他题材世界复用的角色创建、游戏回合、场景、时间、活动、旅行和冒险状态能力。
- 把现有 Behavior 和 NPC Runtime 接入真实游戏事件，同时保持确定性、权限、预算和信息隔离。
- 为实际游玩补齐叙事 UI、目标/状态反馈、存档恢复、回放和失败处理。
- 用 `station-ai` 持续证明新增 Runtime 能力不按战争题材、WorldId 或 DefinitionId 分支。

**Non-Goals:**

- 不在迭代 16–25 实现 Brief/Corpus-to-World 产品流程、TXT/EPUB 导入、世界工坊或真实模型生成世界。
- 不实现在线世界市场、任意代码脚本、语音、多人、完整战术战斗或所有规划规则模块。
- 不要求 Alpha 短篇逐日模拟设计中的 300–1000 日战争；短篇可以用场景和时间跳跃表现其中一段生存历程。
- 不把内置战争内容写进 Runtime、通用 PlayerState、UI 分支或规则模块常量。

## Decisions

### 1. 先建立“可玩世界 v1”黄金契约

`playable-world-contract` 不规定题材内容，而规定一个世界包若声明可完整游玩，必须具备的引用闭包和运行能力：入口、玩家创建 Profile、初始场景、允许的行动能力、至少一个进展目标、失败推进策略、结局状态、表现绑定和必要模块。

验证分两层：静态 Validator 检查 Schema/引用/模块/可达性；Fake Agent 路线测试从新 Run 走到结局，检查所有客观变化都来自 EventLog。`war-survival` 是黄金实现，`station-ai` 提供较小的跨题材负向/兼容契约。

替代方案是先继续生成更多内容 Schema，但没有可玩运行基准，无法判断生成物是否真正可用，因此不采用。

### 2. Run 使用显式生命周期

新 Run 的生命周期为 `CREATED → CHARACTER_CREATION → ACTIVE → COMPLETED/ABANDONED`。生命周期变化是版本化 Event；目录和 UI 状态只是投影。CharacterCreationProfile 驱动共享表单，确认后提交类型化 Command，由 WorldEngine 原子产生玩家 Entity、初始组件和 Run 激活 Events。

旧契约 Run 继续使用兼容初始化路径；新内置短篇必须走正式角色创建。Runtime 不根据世界 ID 猜测默认角色字段。

### 3. 主持人 Agent 与 GameTurnOrchestrator 共同负责一次玩家可感知回合

主持人拥有固定 Agent/Actor 身份和按 Run 隔离的 Session，但它不是单纯的 Narrator。它负责理解玩家意图、识别缺失的目标或选择、决定是否调用当前场景与模块允许的工具、控制场景节奏、请求相关 NPC/Behavior 前台后续，并根据公开已提交事实完成叙述。主持人记忆只保存叙事连续性和玩家已知经历，不能替代 WorldState、EventLog 或 NPC 私有记忆。

`GmContextProjector` 只投影固定世界版本、当前玩家可见状态、场景、最近公开 Events、可选行动、预算和按主持人身份过滤后的 Tool Schema。现有 `GameAgentController` 的 Narrator 会话在本轮迁移为这个显式主持人边界，而不是继续用通用状态文本直接拼接一个旁路提示。

每个回合使用稳定 TurnId 串联：

```text
Player Intent
→ Context/Perception Projection
→ GM Agent Loop
→ zero or more validated Tool Commands
→ committed GameEvents
→ Behavior/NPC follow-up within budgets
→ Presentation + final narration
```

Orchestrator 负责取消、超时、预算、前台优先级、重复请求和失败状态，但不成为事实来源。已提交 Event 不因后续叙述或模型失败回滚；最终 Presentation 必须从当前投影生成，并明确区分“没有产生事实的叙述失败”和“部分行动已经产生公开事实”。

### 4. 场景、时间与冒险状态都是可选通用模块

本阶段只实现内置短篇所需的最小可复用能力：

- 场景：当前位置、进入/离开、场景标签、可见实体和可用行动；
- 时间：离散 Tick/世界时刻、活动耗时和计划触发；
- 活动与旅行：配置驱动的前置条件、成本、检查、效果和到达；
- 物品：带 DefinitionId 的实例/数量、持有者和原子转移/消耗；
- Condition：带强度/层数/期限的定义化状态；
- 关系：命名空间维度的角色间 TypedValue；
- Quest/Clock：目标、阶段、可见说明、进度和完成/失败状态。

每个模块注册自己的 Definition、Tool、Command、Event、Reducer、投影和 UI 能力。世界 manifest 未启用时不注册。战争世界只提供配置；模块不得引用 `war.*`。

### 5. Behavior 从提交后事件推进剧情

EventStore 成功追加后，BehaviorOrchestrator 按 `(runId, eventSequence, priority, behaviorId)` 调度固定世界版本中的已验证 Behavior。每次执行重新读取当前状态、评估 guard，并把 effect 作为新 Command 提交。

每条因果链记录 root/parent Event、BehaviorId、深度、触发次数、派生 Command 数和重复签名。达到限制时停止后续派生并产生诊断，不回滚已经成立的事实。回放复用记录事实和 RandomRecord，不重新让模型或隐式随机决定剧情。

### 6. NPC 是场景参与者，不是旁路叙事器

NpcSceneOrchestrator 只从已提交的场景、对话、时间、目标和相关世界事件产生稳定 Trigger。ContextProjector 按 NPC 身份、位置、感知和知识边界构建私有上下文。NPC 的公开台词、移动、物品或关系行动必须经 Tool/Command/Event；私有反思只进入该 NPC 记忆。

GM 在最终叙事中只获得公开 NPC 结果。调度受每场景并发、每事件唤醒数、Token、费用、步骤和超时限制，前台玩家回合优先。

### 7. 内置战争生存短篇是数据而非代码

Alpha 短篇目标为一次约 45–90 分钟的完整试玩，包含 7–14 个关键场景或阶段、至少 2 名可互动 NPC、资源压力、至少 3 类活动/旅行选择、成功与代价/失败推进，以及至少 3 个可区分结局。具体数量是内容验收基线，可在试玩后调整。

人物、地点、物品、战争事实、资源 Definition、场景图、目标、Behavior、结局和表现绑定全部位于世界包/内容夹具中。Runtime 只看通用 Schema。隐藏战争信息只在世界事实或授权投影中存在，不进入 NPC 不可见上下文或公开回放。

### 8. UI 以“叙事与选择”为中心

共享 Compose UI 重组为：世界/存档入口、角色创建、当前场景与叙事流、自然语言输入、情境行动建议、角色状态、目标/时钟、最近事件和可展开时间线。调试用的“直接减数值”按钮不再是主要玩家路径，可保留在开发构建。

所有卡片由 PresentationDefinition、模块投影或 application view state 驱动；UI 不读取 `war.health` 等世界键。长叙事和事件列表必须虚拟化，后台 Agent/Behavior/NPC 工作不得阻塞 UI 线程。

### 9. 存档恢复围绕 Run/EventLog

SaveCoordinator 管理 Run 元数据、固定世界版本、最后 Event 序号、预览和完整性状态；EventLog 仍是唯一事实历史。恢复验证世界包、Event 连续性、Snapshot 和 reducer 结果。公开回放只导出玩家已知事实、公开 NPC 行动和 RandomRecord，排除密钥、模型正文、NPC 私有记忆和未揭示秘密。

### 10. 十轮按可玩闭环依赖推进

| 迭代 | 主题 | 依赖 | 完成信号 |
|---|---|---|---|
| 16 | 可玩世界契约与黄金路线 | 当前 Runtime/契约世界 | 自动测试能描述从新游戏到结局所缺的能力，内置世界结构通过静态引用与可达性检查 |
| 17 | 新游戏与角色创建 | 16、content-schema | 玩家用世界 Profile 创建角色，Command/Event 激活 Run，重启后可恢复 |
| 18 | 主持人 Agent 与游戏回合编排 | 17、Agent Runtime | 主持人完成意图理解/澄清、受限工具裁决、NPC/Behavior 调度和事实一致叙述，失败不破坏事实 |
| 19 | 时间、活动与旅行 | 18、规则模块 API | 休息/搜索/移动消耗时间和资源，途中事件由配置触发并可回放 |
| 20 | 冒险状态模块 | 19 | 物品、Condition、关系、Quest、Clock 通过通用模块与 Tool 更新并投影 UI |
| 21 | Behavior 剧情推进 | 19–20、behavior-runtime | 已提交事件确定性推进场景、时钟、目标和结局，循环保护和回放一致 |
| 22 | NPC 场景参与 | 18–21、agent-runtime | NPC 按感知对话/行动，公开结果进入事件，私有上下文隔离且预算生效 |
| 23 | 完整内置战争生存短篇 | 16–22 | 至少一条路线从创建角色玩到结局，另有分支/失败路线，生产 Runtime 无题材分支 |
| 24 | 游玩 UI、存档与回放 | 17–23 | 玩家可管理 Run、恢复、理解状态/目标/判定并查看隐私安全时间线 |
| 25 | 试玩与封闭 Alpha 加固 | 16–24 | 全路线、迁移、断网/失败恢复、安全和最低设备性能门禁通过 |

## Risks / Trade-offs

- [一次引入过多通用规则模块] → 每个模块只实现黄金短篇必需的最小 Schema/Command/Event/投影；未被路线测试使用的能力不提前扩展。
- [为了快速做内容而在 Runtime 写战争分支] → `station-ai` 契约测试和生产源码扫描作为每轮门禁，任何 WorldId/题材 Definition 分支都阻断完成。
- [LLM 叙述与事实冲突] → Presentation 和上下文显式提供最近已提交事实；叙述不能产生状态，冲突通过回合诊断和修复提示处理。
- [Behavior/NPC 造成事件风暴或成本失控] → 因果深度、次数、重复签名、场景并发、Token/费用和前台优先级共同限制。
- [短篇内容与长期 300–1000 日目标不一致] → Alpha 验证的是一个可结束篇章和时间跳跃机制，不宣称已经交付完整长期战役。
- [UI 和内容一起推进导致范围膨胀] → 迭代 16–23 优先保证 Fake Agent/应用层可玩，迭代 24 再完成玩家信息架构和视觉体验。
- [先做内置世界导致后续生成 Schema 返工] → 黄金世界的每个内容元素都使用版本化通用 Schema；后续生成器以该包作为契约测试输入/输出样板。

## Migration Plan

1. 迭代 16 只增加可玩世界 Schema/Validator 与测试，不改变旧 Run。
2. 迭代 17 引入显式 Run 生命周期和角色创建 Event；旧 Run 保留兼容初始化，新 Run 使用新流程。
3. 迭代 18–20 的场景和规则模块由 manifest 显式启用；现有两个精简契约世界继续可加载。
4. 迭代 21–22 的 Behavior/NPC orchestrator 默认只对声明相应内容/能力的世界启用。
5. 迭代 23 将战争生存完整内容作为 bundled world 发布，同时保留精简契约夹具用于边界测试，避免测试内容与产品内容互相污染。
6. 迭代 24 为旧 Run 回填目录元数据时只读取 EventLog，不改写事件。
7. 每次公共 Schema/数据库变更都增加上一版本迁移测试；迁移失败时保留原数据并停止正常打开，不静默默认。

回滚以模块/manifest 能力为边界：停用新增模块或 UI 不得删除 EventLog；新世界版本不可原地覆盖已被 Run 固定引用的内容。

## Open Questions

- 内置短篇是独立的 `war-survival-prologue` 世界包，还是 `war-survival` 的首个内容版本？实施前建议选择后者并用 contentVersion 区分。
- Alpha 是否允许只有模板/固定角色创建，还是四种 Profile 模式都必须进入玩家 UI？本计划保留四种 Runtime 支持，但内置短篇可以只启用一到两种。
- 45–90 分钟与 7–14 场景是初始验收基线，迭代 23 的试玩可以在不削弱完整开局/结局闭环的前提下调整。
