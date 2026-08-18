## ADDED Requirements

### Requirement: 草稿必须通过完整可玩性验证后才能试玩
沙箱 SHALL 运行世界包 Schema、Definition/引用闭包、模块兼容、Behavior AST/命令白名单、失败推进和结局可达性验证；任一错误 MUST 阻止启动。

#### Scenario: 有效草稿启动试玩
- **WHEN** 草稿满足 `playable-world/v1` 且所有验证通过
- **THEN** 系统以固定草稿版本创建隔离 Sandbox Run 并使用与内置世界相同的主持与规则管线

#### Scenario: 恶意 Behavior 被拒绝
- **WHEN** 草稿包含未白名单命令或任意脚本内容
- **THEN** 验证在执行前失败并报告具体路径，沙箱不加载该行为

### Requirement: 沙箱事实必须与正式存档隔离
Sandbox EventLog、Agent Session、NPC 记忆和 Run 目录 MUST 使用独立命名空间；删除或重置沙箱不得影响正式 Run。

#### Scenario: 重置试玩
- **WHEN** 作者重置同一草稿的沙箱
- **THEN** 仅该草稿版本的临时事实和私有 Agent 数据被清除，正式存档保持逐字节不变

### Requirement: 安装必须原子固定内容版本
只有验证成功的草稿 SHALL 可安装；安装 MUST 生成内容寻址版本、复制声明式资源并原子发布目录记录，不携带来源全文、密钥或沙箱 EventLog。

#### Scenario: 安装期间失败
- **WHEN** 复制或最终校验失败
- **THEN** 世界目录继续引用上一有效版本且不会出现半安装条目

### Requirement: 第 35 轮候选门禁必须覆盖手写世界与草稿
候选门禁 SHALL 同时执行两个内置世界黄金路线、一个来源映射草稿、沙箱隔离、迁移、隐私审计、Android/Desktop 构建和 iOS Simulator Kotlin 编译。

#### Scenario: 所有候选门禁通过
- **WHEN** 仓库运行第 35 轮候选任务
- **THEN** 每个要求都有自动证据和 artifact hash；缺少真机或真人证据时版本仍明确标记为 candidate 而非 ready
