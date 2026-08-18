## ADDED Requirements

### Requirement: 主持人必须使用 Run 隔离的长期连续性上下文
系统 SHALL 为稳定 GM AgentId 保存公开情节记忆与最后有效检查点，并 SHALL 在每回合组合检查点、未压缩尾部和当前权威 Presentation。

#### Scenario: 长局上下文恢复
- **WHEN** Run 跨越检查点并在进程重启后继续
- **THEN** GM 收到同一 Run 的最后有效摘要、检查点后的公开尾部和当前状态，不收到其他 Run 或 NPC 私有记忆

### Requirement: 上下文压缩必须增量、异步和可恢复
压缩任务 MUST 冻结输入序列范围、校验候选摘要并原子发布；运行中的前台回合不得等待压缩完成。

#### Scenario: 压缩期间继续游玩
- **WHEN** 新回合在检查点候选生成期间开始并提交新事件
- **THEN** GM 使用旧检查点与当前原始尾部，候选只覆盖冻结范围且不吞掉新事件

#### Scenario: 候选摘要失败
- **WHEN** Provider 失败、候选为空或范围校验不通过
- **THEN** 系统保留上一有效检查点并允许正常回合继续

### Requirement: GM 记忆不得成为事实来源
GM 记忆和摘要 MUST 只提供叙事连续性；当其与 EventLog/Presentation 冲突时，系统提示 SHALL 明确以当前权威事实为准。

#### Scenario: 过时记忆冲突
- **WHEN** 检查点提到的状态已被后续事件改变
- **THEN** 当前 Presentation 覆盖该描述且写操作仍需通过当前动态 Tool Schema
