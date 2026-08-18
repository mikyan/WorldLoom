## ADDED Requirements

### Requirement: 自动存档状态必须对应持久化证据
系统 SHALL 在 Event、GM Turn 和 Run 目录元数据成功落盘后投影最后持久化序列、回合和状态；写失败时不得显示“已保存”。

#### Scenario: 正常自动存档
- **WHEN** 玩家行动及主持回合全部持久化完成
- **THEN** UI 显示与 EventLog 序列一致的已保存状态和最近保存时间

#### Scenario: 目录元数据失败
- **WHEN** Event 已提交但 Run 目录元数据更新失败
- **THEN** 世界事实保持有效，UI 显示“事实已保存、目录待修复”并可通过重建目录恢复

### Requirement: 快速继续必须重新验证持久化状态
应用启动时 SHALL 选择最近可继续 Run，但 MUST 校验固定世界内容版本、EventLog 连续性、Snapshot 和生命周期后才进入游戏。

#### Scenario: 最近 Run 可继续
- **WHEN** 最近活跃 Run 的版本和日志有效
- **THEN** 玩家可以一键继续并看到恢复的主持历史、场景和保存状态

#### Scenario: 最近 Run 损坏
- **WHEN** 最近 Run 的 EventLog 不连续或世界版本不可用
- **THEN** 快速继续被拒绝并显示可定位诊断，不自动创建替代 Run 或静默丢弃事实

### Requirement: 中断反馈必须可操作
Provider、存储、取消和进程终止恢复 SHALL 映射为稳定错误类别，并为可安全重试、只补叙述或需要选择其他存档提供不同操作。

#### Scenario: 前台回合取消
- **WHEN** 玩家取消尚未提交工具的回合
- **THEN** Turn 历史记录取消状态并提供重新发送入口，世界状态不变
