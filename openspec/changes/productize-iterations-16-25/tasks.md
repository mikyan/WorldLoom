## 16. 定义可玩世界契约

- [x] 16.1 定义版本化 `playable-world/v1` 契约，明确角色入口、初始场景、可用行动、目标、失败推进、结局和表现定义的最低要求
- [x] 16.2 扩展世界包静态校验，检查 Definition/Entity/Scene/Behavior/Presentation 引用闭包、入口唯一性和模块版本兼容性
- [x] 16.3 实现目标与结局可达性、无效死路和失败后仍可推进的轻量模拟校验，并输出可定位的内容诊断
- [x] 16.4 建立基于 Fake Agent 的黄金路线测试框架，以类型化 Tool → Command → Event 驱动完整游玩而非直接修改状态
- [x] 16.5 将 `war-survival` 整理为可逐步填充的黄金世界骨架，并保留 `station-ai` 作为跨题材反例契约
- [x] 16.6 添加缺失入口、悬空引用、不可达结局、题材硬编码、越权模块和非确定性随机的负向测试
- [x] 16.7 更新 DESIGN 与世界包作者说明，记录“先证明手写世界可玩，再建设自动生成”的验收顺序

## 17. 跑团创建与角色创建

- [x] 17.1 定义版本化 Run 生命周期 `CREATED → CHARACTER_CREATION → ACTIVE → COMPLETED/ABANDONED` 及合法状态转换
- [x] 17.2 定义角色创建请求、Command、Event、玩家 Entity 和初始组件批次的 Schema、权限与幂等语义
- [x] 17.3 实现 `CharacterCreationCoordinator`，从固定世界版本读取 Profile/Definition、校验选择并提交权威创建 Command
- [x] 17.4 实现 Definition/Preset 驱动的共享角色创建 UI，支持固定角色、模板和点数模式；叙事输入只作为可选候选生成路径
- [x] 17.5 让 WorldEngine 原子生成角色创建 Events，并由 Reducer/EventLog 唯一重建初始 GameState
- [x] 17.6 持久化创建中状态与已确认 Run，覆盖退出恢复、重复确认、事件追加失败和旧存档兼容
- [x] 17.7 为两个契约世界配置不同的创建 Profile，验证 Runtime、UI 与存档均无需题材分支

## 18. 主持人 Agent 与游戏回合编排

- [x] 18.1 定义版本化 Scene、SceneParticipant、AvailableAction、TurnId、GameTurn 与主持人 Session/Profile Schema
- [x] 18.2 实现 `GmContextProjector` 和按 Run 隔离的主持人会话，只投影玩家可见事实、当前场景、公开事件、预算与动态 Tool Schema
- [x] 18.3 实现 `GameTurnOrchestrator`，支持意图理解、缺失目标澄清、受限工具循环、NPC/Behavior 前台调度和事实一致叙述
- [x] 18.4 将所有客观变化限制为 Tool Gateway → GameCommand → CommandValidator → WorldEngine → GameEvent，并实现场景进入/退出/参与者变更的类型化事件
- [x] 18.5 实现稳定主持人身份、回合幂等、取消、超时、最大步骤、费用预算、循环检测和部分事实已提交后的可恢复失败
- [x] 18.6 重构主游戏页为叙事记录、当前场景、行动输入和可选行动组成的最小游玩界面
- [x] 18.7 添加 Fake Agent 集成测试，覆盖意图澄清、动态工具、场景节奏、多步裁决、NPC/Behavior 聚合、非法工具、重复回合、断线恢复和叙述一致性

## 19. 时间、活动与旅行

- [x] 19.1 实现可选时间模块的 Definition、Command、Event、Reducer、Tool 和投影，时间推进必须显式且可审计
- [x] 19.2 实现可选活动模块，支持耗时、资源代价、前置条件、成功/失败结果和中断策略
- [x] 19.3 实现可选旅行模块，使用地点 Definition 与连接关系表达出发、到达、耗时、风险和场景切换
- [x] 19.4 实现基于世界时间与已提交事件的计划触发器，不读取系统时钟决定游戏事实
- [x] 19.5 将所有随机结果写入 Random Record，并验证恢复和重放不重新掷骰
- [x] 19.6 为等待、休息、搜寻、治疗和旅行配置题材无关示例，并通过 PresentationDefinition 显示结果
- [x] 19.7 添加边界、并发、失败推进、跨日触发、随机审计和两个契约世界确定性测试

## 20. 冒险状态规则模块

- [x] 20.1 在 manifest 与 Module Registry 中定义库存、状态、关系、任务和进度钟模块的版本、依赖、Schema 与能力注册边界
- [x] 20.2 实现库存模块的动态物品组件、获取/失去/使用 Command/Event、容量约束和只读投影
- [x] 20.3 实现状态模块的 Definition 驱动 Condition、叠加/消退规则、持续时间和表现绑定，不增加固定题材枚举
- [x] 20.4 实现关系模块的角色间 TypedValue、可见性规则和修改 Command/Event，禁止向无权角色泄露私有关系事实
- [x] 20.5 实现任务与进度钟模块的阶段、目标、推进、失败和完成事件，使结局条件可引用稳定投影
- [x] 20.6 为各模块注册最小权限 Tool，并让 UI 仅通过 PresentationDefinition 与模块投影展示状态
- [x] 20.7 添加组合、权限、边界、序列化、迁移、重放与 `war-survival`/`station-ai` 跨题材契约测试

## 21. Behavior 推进冒险

- [x] 21.1 实现 post-commit Event Dispatcher 与按稳定键排序的可恢复 Behavior 执行队列
- [x] 21.2 从固定世界包构建已验证 Behavior Registry，只注册 manifest 启用且版本兼容的 Behavior 与 Command 白名单
- [x] 21.3 基于最新 GameState 和冻结触发上下文求值 guard，并将每个 effect 重新提交到 CommandValidator/WorldEngine
- [x] 21.4 记录 root/parent event、behaviorId、因果深度、触发次数和派生 Command，支持诊断与审计
- [x] 21.5 实现因果深度、每链触发数、重复签名和派生 Command 数限制，失败时暂停相关链而不破坏已提交事实
- [x] 21.6 扩展重放验证，校验 Behavior 派生事件顺序、参数与随机记录，而不重新执行 Agent 或重新掷骰
- [x] 21.7 用黄金世界覆盖场景解锁、任务推进、定时事件、失败分支和结局触发，并补充递归/竞态/重启恢复测试

## 22. NPC 场景参与

- [ ] 22.1 实现 `NpcContextProjector`，仅投影该 NPC 在当前场景可感知、已知且获准使用的事实与记忆
- [ ] 22.2 从已提交的场景、对话、时间、任务与世界 Events 生成稳定 `NpcTrigger`，并持久化可恢复幂等队列
- [ ] 22.3 扩展 NPC Scheduler，限制每场景并发、每事件唤醒数、Token、费用、超时和 Provider 前台优先级
- [ ] 22.4 让 NPC 的公开发言与行动通过其身份对应的 Tool Gateway/Command/Event 提交，私有反思只写入独立记忆分区
- [ ] 22.5 实现主持 Agent 对 NPC 公开结果的聚合与 Presentation，禁止暴露原始模型正文、私有信念和未感知秘密
- [ ] 22.6 为稳定角色 ID、会话隔离、重复触发、预算耗尽、工具拒绝和多 NPC 场景添加 Fake Agent 集成测试
- [ ] 22.7 在两个契约世界加入最小 NPC 场景，验证相同 Runtime 可完成唤醒、行动、保存、恢复和重放

## 23. 完成内置战争生存短剧本

- [ ] 23.1 为内置 `war-survival` 固定内容版本、模块清单、角色创建 Profile、初始场景、目标与结束条件
- [ ] 23.2 编写约 45–90 分钟的完整短冒险，包含 7–14 个关键场景或阶段、至少 2 名可交互 NPC 和至少 3 条有意义的行动路线
- [ ] 23.3 配置地点、旅行、活动、物品、资源、状态、关系、任务、进度钟与 Behavior，使剧情推进完全来自声明式内容和通用模块
- [ ] 23.4 配置至少 3 个可达结局以及失败后的替代推进路径，避免单次失败导致无反馈死局
- [ ] 23.5 完成 PresentationDefinition、场景叙述素材、可选行动、状态摘要和结局总结，不在通用 UI 中读取世界私有键
- [ ] 23.6 完成 NPC 知识、秘密、目标、关系与感知边界配置，并验证未揭示事实不进入玩家上下文或公开重放
- [ ] 23.7 建立成功、代价成功和失败结局的黄金路线，以及资源耗尽、非法行动、存档恢复和确定性重放测试
- [ ] 23.8 将该世界设为内置试玩入口，同时运行 `station-ai` 契约以确认新增能力没有引入战争题材分支

## 24. 游玩界面、存档与重放

- [ ] 24.1 调整共享 UI 信息架构，围绕开始游戏、继续游戏、当前场景、叙事时间线、行动和角色/目标状态组织页面
- [ ] 24.2 实现 Run 目录投影与 `SaveCoordinator`，支持创建、列出、重命名、归档、继续和完成多个内置世界 Run
- [ ] 24.3 在继续游戏前校验固定世界版本、事件连续性、Snapshot 兼容和 Reducer 重建，并提供坏 Snapshot 回退与 EventLog 损坏诊断
- [ ] 24.4 实现虚拟化叙事时间线和事件详情投影，关联行动、判定、Random Record、Behavior 因果、NPC 公开动作和玩家可见叙述
- [ ] 24.5 实现 Definition 驱动的角色状态、库存、关系、任务和进度钟卡片，并支持窄屏、桌面与减少动态效果
- [ ] 24.6 提供应用内公开重放与离线一致性校验，默认排除密钥、模型正文、NPC 私有记忆和未揭示秘密
- [ ] 24.7 添加长时间线分页、跨 Run 隔离、篡改检测、恢复后继续游玩、隐私和三端 UI/性能防回归测试

## 25. 封闭 Alpha 加固

- [ ] 25.1 建立内置剧本关键旅程系统测试：启动 → 创建角色 → 多轮行动 → 时间/活动/旅行 → Behavior/NPC → 保存恢复 → 结局 → 重放
- [ ] 25.2 建立故障注入矩阵，覆盖 Provider 断网/限流、工具失败、EventStore 事务失败、应用终止、磁盘不足和坏 Snapshot 恢复
- [ ] 25.3 以 Fake Agent 黄金路线作为发布权威，并在可选开发配置下完成真实 Provider 冒烟且不保存密钥、完整正文或私有上下文
- [ ] 25.4 在目标最低设备测量启动、前台回合、长时间线、后台 NPC 竞争、内存和 30 FPS 基线，修复发布阻断退化
- [ ] 25.5 运行秘密、日志、回放、工具权限、世界包 Schema、Agent 隔离和题材硬编码审计，并把发现转为回归测试
- [ ] 25.6 组织多轮人工试玩，记录路线覆盖、卡死点、叙事一致性和资源平衡问题，并只调整声明式世界内容或通用能力
- [ ] 25.7 配置可重复的 Android 与 Desktop 发行构建、版本/Schema 清单和 artifact hash，并准备 iOS Xcode build handoff
- [ ] 25.8 更新 README、DESIGN、AGENTS、已知限制和 Alpha 验收证据；明确 TXT/EPUB 导入、自动世界生成与世界工坊从第 26 轮以后再启动
- [ ] 25.9 运行全仓 check、数据库迁移、相关模块测试、Android 构建、Desktop 冒烟和 iOS Simulator Kotlin 编译，所有门禁通过后才标记 Alpha ready
