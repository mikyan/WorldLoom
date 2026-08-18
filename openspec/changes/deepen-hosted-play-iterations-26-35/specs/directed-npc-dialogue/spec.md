## ADDED Requirements

### Requirement: 玩家必须能够定向当前场景 NPC 发言
系统 SHALL 提供带稳定目标 NPC、限长公开文本和幂等键的类型化对话 Command/Event；目标 MUST 是当前场景可见参与者且世界包允许交互。

#### Scenario: 定向对话成功
- **WHEN** 玩家选择当前场景可交互 NPC 并提交合法文本
- **THEN** 系统原子追加玩家公开发言事件并只为目标 NPC 创建稳定工作项

#### Scenario: 场景外 NPC 被拒绝
- **WHEN** 玩家或 Agent 尝试向不在当前场景的 NPC 发言
- **THEN** CommandValidator 在写 Event 前拒绝请求且不唤醒任何 NPC

### Requirement: NPC 公开响应必须继续经过受限工具
NPC 对玩家发言的响应 MUST 通过 `npc.speak` 或获准的 `npc.act` 形成公开 Event；最终模型正文、思考和未发布工具参数不得进入玩家 Presentation。

#### Scenario: NPC 私有反思不泄露
- **WHEN** NPC 在调用公开发言工具后返回包含私密推理的最终正文
- **THEN** 主持人历史和公开回放只包含工具提交的公开内容

### Requirement: 知识揭示必须使用显式白名单 ID
NPC Profile SHALL 以稳定 ID 声明私有知识及可选公开摘要；NPC 只能揭示自己拥有且获准公开的 ID，揭示结果 SHALL 成为可重放的公开事件。

#### Scenario: 合法知识揭示
- **WHEN** NPC 工具提交其白名单内的知识 ID
- **THEN** 系统公开对应摘要、记录揭示事实并在后续主持上下文中投影该摘要

#### Scenario: 越权知识揭示
- **WHEN** NPC 请求揭示另一个角色或未声明的知识 ID
- **THEN** 系统拒绝工具调用且不暴露该知识正文、ID 对应关系或存在性细节

### Requirement: 对话记忆必须按角色隔离
每个 NPC SHALL 只在自己的 AgentId/RunId 分区保存所感知的玩家发言、公开回应和已揭示知识，不得读取其他 NPC 私有对话记忆。

#### Scenario: 多 NPC 同场隔离
- **WHEN** 玩家只与一个 NPC 进行定向对话
- **THEN** 未被选中的 NPC 不创建对话工作项且其私有记忆保持不变
