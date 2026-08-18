## ADDED Requirements

### Requirement: 主持人回合必须形成可恢复的分页历史
系统 SHALL 按 Run 持久化玩家输入、回合状态、公开输出、证据事件序列范围和错误分类，并 SHALL 以稳定顺序分页读取；模型正文不得进入 EventLog 作为世界事实。

#### Scenario: 重启后重建主持记录
- **WHEN** 玩家完成多个叙事、澄清和失败回合后重启并继续同一 Run
- **THEN** 系统按 TurnId 顺序恢复公开回合且每个回合引用不超过当前 EventLog 的证据范围

#### Scenario: 损坏的表现记录不篡改世界
- **WHEN** 某条 Turn 记录引用不存在的未来事件或 Schema 不兼容
- **THEN** 系统隔离该表现记录并给出诊断，同时继续仅从有效 EventLog 重建世界状态

### Requirement: 中断回合必须按事实边界恢复
系统 MUST 区分“尚未提交事实”和“已提交事实但未完成叙述”的中断，不得自动再次执行原回合工具。

#### Scenario: 无事实中断可安全重试
- **WHEN** RUNNING 回合在 EventLog 序列未前进时恢复
- **THEN** 系统将其标记为可重试并要求使用新的 TurnId

#### Scenario: 已提交事实只允许补叙述
- **WHEN** RUNNING 回合恢复时 EventLog 已超过接受序列
- **THEN** 系统保留已提交事实并只允许无写工具的补叙述请求引用该事件范围

### Requirement: UI 必须呈现明确的回合状态
共享 UI SHALL 区分玩家输入、主持叙事、澄清、运行、已取消、可重试失败和事实已提交失败，并 SHALL 支持加载较早回合。

#### Scenario: 玩家理解失败影响
- **WHEN** Provider 在工具提交前或提交后失败
- **THEN** UI 分别显示“世界未改变，可重试”或“事实已保存，叙事待恢复”而不使用模糊错误覆盖历史
