## ADDED Requirements

### Requirement: 空间站世界必须成为完整内置短剧本
`station-ai` SHALL 提供固定内容版本、角色创建 Profile、7–14 个关键场景或阶段、至少两个 NPC、三条有意义路线及约 45–90 分钟的可玩内容。

#### Scenario: 从目录开始空间站游戏
- **WHEN** 玩家在内置目录选择空间站世界并完成建角
- **THEN** 同一通用 Runtime 加载初始场景、动态行动、NPC、规则模块与教程而不读取题材专用分支

### Requirement: 空间站世界必须组合完整冒险能力
世界包 SHALL 声明时间、活动、旅行、库存、状态、关系、任务、进度钟和 Behavior，并 SHALL 通过公共 PresentationDefinition 显示结果。

#### Scenario: 多模块路线推进
- **WHEN** 玩家沿任一黄金路线进行等待、调查、对话、资源选择和旅行
- **THEN** 所有变化由类型化 Command/Event 产生且重放不重新随机或执行 Agent

### Requirement: 空间站世界必须有多结局与失败推进
世界包 SHALL 提供至少三个可达结局，包括成功、代价成功和失败，并 MUST 保证单次失败不会产生无反馈死局。

#### Scenario: 三条黄金路线
- **WHEN** Fake GM 分别执行成功、代价成功和连续失败路线
- **THEN** 每条路线抵达不同声明式结局并通过存档恢复与公开回放校验

### Requirement: 两个内置世界必须共用 Runtime
契约测试 MUST 同时运行 `war-survival` 与 `station-ai`，并 SHALL 审计共享生产代码中不存在 worldId、题材名称或私有 DefinitionId 分支。

#### Scenario: 跨题材回归
- **WHEN** 新增空间站内容和能力后执行全仓契约测试
- **THEN** 两个世界都完成加载、主持、NPC、Behavior、存档、结局和回放且无需修改通用题材逻辑
