# Worldloom Agent 工作指南

本文件适用于整个仓库。若子目录以后包含更具体的 `AGENTS.md`，以离目标文件最近的规则为准。

## 项目与当前阶段

Worldloom（织境）是一款面向 Android、iOS 与 Desktop、由 Agent 主持的单人数字跑团 RPG。自然语言模型负责理解、扮演和叙述；确定性的本地世界引擎负责规则裁决、随机审计和事实更新。

仓库目前处于“两个内置剧本可完整游玩、受控草稿试玩已接入、继续做候选验收”阶段，已经加入 Gradle/Kotlin Multiplatform 工程骨架、三端入口、规则模块、可审计判定、SQLDelight EventLog、受限 Agent Runtime、OpenAI Provider、平台凭据保险箱、可恢复识别工作区和隔离草稿沙箱。因此：

- 先用 `settings.gradle.kts` 和模块构建文件确认模块与任务是否已经存在，不要假定完整目标架构已经落地；
- 新增模块、平台入口或稳定任务时应同步更新本文件中的目录和验证命令；
- 实现范围不明确时，优先完成最小的端到端竖切，不提前铺开全部规划模块。

## 事实来源与决策优先级

开始工作前先阅读与任务相关的文档。发生冲突时按以下顺序处理：

1. `docs/decisions/` 中状态为 Accepted 的 ADR；
2. `docs/DESIGN.md`；
3. `README.md`；
4. 代码中的局部说明和实现细节。

当前关键文档：

- `docs/DESIGN.md`：产品、运行管线、目标架构、世界包、安全、性能和测试基线；
- `docs/decisions/0001-compose-multiplatform.md`：Kotlin Multiplatform 与 Compose Multiplatform 技术决策；
- `docs/decisions/0002-world-configuration-boundary.md`：Runtime、规则模块、世界配置和剧本内容的边界。

不要在普通实现中静默推翻 Accepted ADR。若确需改变架构决策，先新增或修订 ADR，写明背景、选择、代价和迁移影响，再修改设计文档与代码。设计仍标记为 Draft；对未定细节采用可替换、最小化的实现，并在文档中记录假设。

## 核心架构

### 权威运行管线

玩家操作遵循：

```text
Intent → Command → Event → Presentation
```

所有客观事实变化遵循：

```text
GameCommand → CommandValidator → WorldEngine → GameEvent → EventStore/Reducer → GameState
```

- `EventLog` 是已发生事实的真相历史，`GameState` 只是它的当前投影；
- LLM、Agent Runtime、UI、Behavior Runtime 都不能直接修改世界状态；
- Agent 只能通过 Tool Gateway 调用受 Schema 和权限约束的能力，写操作最终必须生成类型化 `GameCommand`；
- RandomService 创建可审计的 Random Record。回放复用已发生的随机事实，不重新掷骰；
- 给定相同状态、规则版本和随机记录，RuleEngine 与 Reducer 必须产生相同结果；
- PresentationMapper 只解释事件、Definition 和模块投影，不能成为事实来源。

### 四层配置边界

严格区分以下四层：

1. **引擎基础设施**：类型系统、Command/Event 信封、验证、事件溯源、随机审计、Agent Loop、DSL 解释和持久化；
2. **可选规则模块**：可复用算法及其 Schema、Tool、Command、Event、Reducer、投影和 UI 能力；
3. **世界包规则配置**：选择模块并定义属性、资源、状态、公式、状态机和表现绑定；
4. **具体剧本内容**：某一世界独有的人物、地点、目标、事件、变量和结局。

实现时必须满足：

- 使用带命名空间的 `DefinitionId`、`TypedValue`、动态实体组件、世界变量和模块状态；
- 不创建固定的 `Attribute`、`Skill`、`Resource`、`Condition` 或 `LifeState` 内容枚举；
- 不向通用 `PlayerState`、`CharacterState` 或 `WorldState` 添加 `health`、`hunger`、`warEndDay` 等题材字段；
- 不按 `worldId`、题材名称或首个世界的 DefinitionId 在 Runtime 中分支；
- UI 通过 `PresentationDefinition` 和模块投影读取数据，不直接读取 `war.health` 等世界键；
- Module Registry 只注册 `manifest.json` 明确启用且版本兼容的模块能力；
- 世界包只能包含声明式配置和经验证的 Behavior AST，不能携带或执行 Kotlin、JavaScript、Lua 等任意代码；
- 复杂、可复用的确定性算法放入带版本的规则模块，世界包只负责选择、参数化和组合。

`war-survival` 与 `station-ai` 是跨题材契约世界，应只存在于世界包或测试夹具中。两者必须无需修改 Runtime 即可完成加载、角色创建、Tool 注册、状态更新、存档、回放和 UI 投影。

### Agent、信息与 Provider 边界

- `WorldState + EventLog` 保存客观事实；Agent 记忆只保存该角色感知到的经历、信念、目标和关系；
- 每个 NPC 使用稳定角色 ID、独立 Session、私有记忆、感知投影和工具权限，禁止跨角色泄露上下文；
- Agent Loop、工具循环、重试、预算、上下文构建和压缩属于 Worldloom Runtime，不依赖供应商 Agent 框架；
- Provider Adapter 统一转换供应商协议，并显式声明 streaming、tool calling、structured output 等能力；不要假定所有供应商功能相同；
- 上下文压缩是增量、异步、可恢复的后台工作。候选检查点必须校验后原子发布，不能覆盖 EventLog 或阻塞正常回合。

## 目标工程结构与依赖方向

计划技术栈为 Kotlin Multiplatform、Compose Multiplatform、Coroutines/Flow、kotlinx.serialization、Ktor Client 和 SQLDelight。目标目录为：

```text
apps/       # androidApp、iosApp、desktopApp；组装与平台入口
shared/     # UI、application、definition/domain、规则模块、Agent、内容、持久化、Provider API
platform/   # 凭据、文件、音频/触觉、生命周期与窗口等平台实现
docs/       # 设计文档和 ADR
```

规划中的共享模块职责以 `docs/DESIGN.md` 第 7 节为准。新增依赖时遵循：

- 领域与 Runtime 核心保持纯 Kotlin，不依赖 Compose 或具体平台 API；
- UI 通过 application/use-case 与只读投影交互，不绕过应用层直接改变引擎状态；
- 平台能力由共享接口或 `expect/actual` 隔离，Android Context、JVM 文件 API 和 Apple Framework 不进入 `commonMain`；
- Provider 具体实现依赖 `provider-api`，不要让领域模型依赖供应商 DTO；
- 可选规则模块依赖稳定的 `rule-module-api`，不得依赖具体世界内容；
- 模块间只公开必要 API，避免循环依赖和跨模块读取内部实现。

实现 KMP 骨架后按 `commonMain`、`commonTest`、`androidMain`、`iosMain`、`desktopMain` 等 source set 放置代码；优先共享领域逻辑和主要 UI，只把系统集成留在平台层。

## 编码与数据约定

- 优先不可变数据、显式状态转换和小而清晰的类型；副作用集中在边界组件；
- 异步逻辑使用结构化并发。Agent、网络、存档、长文本导入和上下文压缩不得运行在 UI 线程；
- Flow/协程任务应传播取消，后台导入和压缩需要可恢复、幂等且可观察进度；
- 序列化的 Command、Event、Definition、存档和世界包结构必须带可迁移的版本语义；改 Schema 时同时考虑旧存档、旧 EventLog 和旧世界包；
- 对外部输入做 Schema、类型、引用、权限和数值边界验证；解析错误应包含可定位的上下文，不以静默默认值掩盖非法内容；
- 随机性只能通过显式 RandomService 请求进入事件记录；不要使用系统时间、隐式随机或迭代顺序影响确定性结果；
- 日志不得输出密钥、完整模型正文、NPC 私有上下文或未揭示世界秘密；
- 命名遵循 Kotlin 惯例：类型使用 `PascalCase`，函数/属性使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`；Definition/Tool/Command/Event ID 使用稳定、带命名空间的字符串；
- 注释解释约束、权威边界和“为什么”，不要复述代码。公共 API 和不直观的 Schema 字段应有 KDoc 或等价说明；
- 不进行与当前任务无关的大规模重命名、格式化或依赖升级。

## 安全与性能底线

- API Key 只进入平台凭据保险箱，不得进入 Agent 上下文、世界包、存档、EventLog、崩溃报告、HTTP 正文日志或 Git；
- Agent Runtime 必须有工具权限、参数 Schema、最大步骤数、超时、费用预算和循环检测；
- 公开回放与 Agent 私有记忆分区存储，普通导出默认排除未揭示秘密和 NPC 私有推理；
- 长列表分页或虚拟化，昂贵 Compose 绘制应缓存，不可见动效应停止；
- 新功能需要尊重 `docs/DESIGN.md` 第 10 节的平台内存与 30 FPS 基线，并保留减少动态效果和省电路径。

## 测试与验证

每次变更至少验证受影响的最小范围，并优先补充防回归测试。测试层次包括：

- 纯 Kotlin 单元测试：Definition/TypedValue、序列化、Command 权限、RuleEngine、RandomService、Reducer、Behavior AST 与迁移；
- Fake Agent 集成测试：Tool Schema/权限、完整 Tool→Command→Event 链、Provider Adapter、Session 隔离、预算与失败恢复；
- 跨题材契约测试：`war-survival` 与 `station-ai` 共用 Runtime 且不出现题材分支；
- UI/性能测试：多尺寸、减少动态效果、长列表、后台任务竞争和目标最低设备；
- 内容生成测试：TXT/EPUB 解析、来源映射、边界输入、取消/恢复、验证和快速试玩。

当前稳定命令如下，要求 JDK 17 或更高版本；Android 构建还需要 API 36 SDK：

```text
./gradlew.bat check
./gradlew.bat :shared:domain-world:desktopTest
./gradlew.bat :shared:world-package:desktopTest
./gradlew.bat :shared:behavior-runtime:desktopTest
./gradlew.bat :shared:content-generation:desktopTest
./gradlew.bat :shared:persistence:verifyCommonMainWorldloomDatabaseMigration
./gradlew.bat :shared:provider-openai:desktopTest
./gradlew.bat :shared:ui-game:compileKotlinIosSimulatorArm64
./gradlew.bat :apps:androidApp:assembleDebug
./gradlew.bat :apps:desktopApp:run
./gradlew.bat alphaGate
./gradlew.bat alphaRelease
./gradlew.bat round35CandidateGate --no-configuration-cache
./tools/alpha-audit.ps1
```

Unix 与 macOS 将 `./gradlew.bat` 替换为 `./gradlew`。iOS 宿主通过 `apps/iosApp/iosApp.xcodeproj` 在 macOS/Xcode 中构建。

- 只使用仓库提供的 Gradle Wrapper；
- 新增稳定的格式化、静态检查、单元测试和平台构建任务后在此处记录；
- 提交前运行与改动相称的检查，并在交付说明中列出实际运行的命令与未验证项；
- 文档改动至少检查 Markdown 链接、代码块和 Mermaid 语法是否完整。

不要为了让测试通过而削弱 Schema、权限、确定性或信息隔离约束。

## Git 提交规范

沿用仓库现有的英文 Conventional Commits 风格：

```text
<type>[(scope)][!]: <imperative summary>
```

允许的常用类型：

- `feat`：新增用户可见能力；
- `fix`：修复缺陷；
- `docs`：仅文档；
- `refactor`：不改变行为的重构；
- `test`：新增或调整测试；
- `perf`：性能改进；
- `build`：构建系统或依赖；
- `ci`：CI 配置；
- `chore`：不属于上述类型的维护工作。

提交要求：

- 摘要使用英文祈使语气、小写开头、末尾不加句号，建议不超过 72 个字符；
- scope 可选，使用稳定的模块或领域名，如 `world-engine`、`agent-runtime`、`world-package`、`ui-game`；
- 一个提交只包含一个逻辑变更；不要混入无关格式化、临时文件、构建产物或本地配置；
- 正文说明“为什么”和重要取舍，并记录 Schema、存档、事件或世界包的兼容/迁移影响；
- 破坏性变更使用 `!`，并在正文加入 `BREAKING CHANGE:`；
- 关联任务可在 footer 使用 `Refs:` 或 `Closes:`；
- 示例：`docs: define configurable Worldloom architecture`、`feat(world-engine): add auditable random records`、`fix(agent-runtime): isolate NPC tool context`；
- 除非用户或任务明确要求，不自行创建提交、推送分支、改写历史或操作远端。

## 工作与交付流程

1. 检查 `git status`，保护用户已有改动；
2. 阅读相关设计和 ADR，确认变更属于哪个权威组件与配置层；
3. 做最小、内聚的实现，保持公共 Schema 和事件兼容；
4. 添加或更新相应测试、夹具与文档；
5. 运行可用验证，复查 diff 是否包含秘密、题材硬编码和无关改动；
6. 交付时简述结果、验证命令、已知限制和必要的后续事项。

新增重要架构决策、世界包格式、公共 Schema、平台最低要求或安全边界时，必须同步更新 `docs/DESIGN.md`，并在需要时新增 ADR。README 只保留面向项目入口的概览，不复制全部设计细节。
