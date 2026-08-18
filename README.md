# Worldloom / 织境

Worldloom 是一款面向 Android、iOS 与桌面端、由 AI 主持的单人数字跑团 RPG。玩家像桌面跑团中一样用自然语言声明行动，影响一个持续演化的世界；Agent 负责理解、扮演和叙述，确定性的世界引擎负责规则裁决与事实更新。

卡片、面板和时间线用于展示世界包定义的角色状态、世界信息、模块内容与判定结果；自然语言行动、规则判定和持续演化的世界共同构成游戏体验。

> 项目已经完成三十四轮工程迭代并处于 `0.1.0-alpha.1` 候选：两个约 60 分钟的内置短剧本已经共用主持、NPC、规则、存档和回放管线；TXT/EPUB 资料先进入可恢复、可溯源且禁止直接安装的识别工作区。

## 产品方向

- 自然语言自由行动为主，情境化快捷建议为辅；
- 先把内置剧本的主持、游玩、存档和结局体验做完整；
- 第 34 轮以后再深化 TXT/EPUB 剧情识别和受控草稿试玩；
- 后续接入语音转写与 TTS 模型，支持全程语音游玩；
- 属性、技能、资源、角色创建与领域规则由剧本生成，世界包按需启用规则模块，经 Schema 验证后加载；
- 世界状态和规则由本地权威引擎维护，掷骰通过可审计的 Agent Tool 执行并写入事件记录；
- 主持人与每个 NPC 都拥有独立 Agent 上下文，NPC 按事件和场景动态唤醒；
- Agent 上下文按使用量主动、增量、异步压缩，不因记忆整理阻塞玩家操作；
- 角色面板、世界信息卡片、动效和声音把世界事件转化为清晰反馈；
- 同一 Runtime 可以加载不同题材的 `.worldloom` 世界包。

首个内置短篇世界采用战争生存题材；第二个内置短篇《静默轨道：赫利俄斯危机》让玩家扮演空间站核心 AI，在能源、乘员安全和自身完整性之间作出选择。

## 文档

- [项目设计文档](docs/DESIGN.md)
- [项目初始化设计](docs/PROJECT_INITIALIZATION.md)
- [迭代执行与验收记录](docs/ITERATION_EXECUTION.md)
- [封闭 Alpha 验收与发行说明](docs/ALPHA_ACCEPTANCE.md)
- [内置战争生存短剧本说明](docs/BUILT_IN_WAR_SCENARIO.md)
- [内置空间站短剧本说明](docs/BUILT_IN_STATION_SCENARIO.md)
- [存档与公开回放说明](docs/SAVES_AND_PUBLIC_REPLAY.md)
- [TXT/EPUB 剧本识别工作区](docs/SOURCE_RECOGNITION_WORKSPACE.md)
- [ADR-0001：选择 Kotlin 与 Compose Multiplatform](docs/decisions/0001-compose-multiplatform.md)
- [ADR-0002：世界配置与程序代码边界](docs/decisions/0002-world-configuration-boundary.md)

## 计划技术栈

```text
Kotlin Multiplatform
Compose Multiplatform
Coroutines / Flow
kotlinx.serialization
Ktor Client
SQLDelight
```

目标工程将共享世界规则、Agent Runtime、存档模型和主要 Compose UI，并保留 Android、iOS、Desktop 各端的安全存储与系统集成层。

## 当前工程结构

```text
apps/
├── androidApp
├── desktopApp
└── iosApp

shared/
├── definition-runtime
├── domain-world
├── domain-rules
├── rule-module-api
├── rule-module-registry
├── persistence
├── provider-api
├── provider-openai
├── agent-runtime
├── world-package
├── behavior-runtime
├── content-schema
├── content-generation
├── application
└── ui-game

platform/
└── secure-vault

contract-worlds/
├── war-survival
└── station-ai

docs/
├── DESIGN.md
├── PROJECT_INITIALIZATION.md
└── decisions/
```

当前竖切从两个 JSON 契约世界加载 manifest 与动态 Definition，经模块能力、类型和引用验证后，执行 `Intent/Tool/Behavior → Command → Event → SQLDelight EventLog/Reducer → GameState`，再通过表现绑定生成共享 Compose UI。Agent Runtime 对步骤、工具、超时、Token、费用、权限和循环进行限制；OpenAI 适配器支持流式文本与工具调用，Provider 设置可在运行时切换并持久化，但供应商 DTO 不进入领域层。

选择内置世界后，新 Run 会经过 `CREATED → CHARACTER_CREATION → ACTIVE`：战争契约使用固定角色，空间站契约使用点数分配，同一共享 UI 从 Profile 生成表单。角色确认以一个原子 Event 批次创建玩家 Entity、初始组件和开局场景；未确认草稿独立持久化，可在重启后继续，且不会提前成为世界事实。

进入 `ACTIVE` 后，每个 Run 使用稳定、独立且可恢复的主持人 Session。主持人只接收玩家可见状态、当前场景、公开参与者、行动和事件摘要；自然语言意图通过当前场景动态生成的 Tool Schema 执行，行动结果必须经校验后的 Command/Event 批次推进场景、目标与结局。同一 TurnId 不会重复执行，澄清不写事实，取消、超时、预算或 Provider 故障后也会明确保留已经提交的权威事件。

主持人的公开叙事连续性也按 Run 持久化。已完成回合与其公开事件证据会修复为稳定 GM Turn 流；达到上下文阈值后，后台任务冻结旧范围、生成公开剧情检查点并原子发布，玩家可以在候选生成期间继续游玩。每次提示都明确把检查点当作非权威叙事记忆，并在其后重新提供当前 Presentation、Event 摘要与动态 Tool Schema；发生冲突时只能以当前事实为准。

主持界面还会从世界包的版本化 Guidance Definition 投影首次教程、场景提示和当前可用的行动建议。战争世界引导玩家搜索与选择路线，空间站世界则提示能源活动；这些入口只会预填自然语言输入，不会替玩家提交事实。教程可以跳过或重新查看，当前场景若没有行动、活动或旅行出口，会显示可定位的内容契约诊断。

内置世界现可声明世界时间、等待/休息/搜寻/治疗等活动、有向旅行路线和按世界时间触发的计划事件。主持人只能看到当前场景可用的时间、活动与旅行 Tool；耗时、检定、资源变化、活动中断、旅行抵达及场景切换都以一个可审计事件批次提交，恢复与回放不读取系统时间，也不会重新掷骰。

内置世界也可按 manifest 组合库存、状态、关系、任务和进度钟模块。物品容量、Condition 叠加与持续时间、关系边界、任务阶段和结局条件均来自声明式 Definition；主持人只通过细粒度权限 Tool 提交类型化 Command，玩家界面只读取过滤私有事实后的模块投影。

已提交事件会在 post-commit 阶段进入可恢复的 Behavior 队列。世界包中的已验证 Behavior 按稳定顺序读取冻结触发上下文和最新状态，派生动作仍重新经过 CommandValidator、WorldEngine、EventLog 与 Reducer；root/parent event、因果深度和派生 Command 均可审计。深度、触发数、重复签名或命令预算超限时只暂停相关链，已提交事实不回滚；恢复会补扫 EventLog，回放只校验结果而不重新运行 Behavior。

场景参与者现在可以绑定声明式 NPC Profile。每个 NPC 只获得当前场景、白名单 Presentation、自己的目标、秘密和记忆，使用稳定且独立的 Session；已提交的场景、活动、旅行、任务与公开 NPC 事件会生成幂等工作项。NPC 的公开发言和动作必须调用身份受限工具并形成 Command/Event，模型最终正文只作为私有反思；主持人仅收到显式公开结果。NPC 调度在前台主持回合内串行执行，并继续受步骤、Token、费用、超时和工具预算限制。

NPC 知识使用世界包声明的稳定 DefinitionId 区分私有正文与可选固定公开摘要。模型只会在自己的 `npc.speak` Tool Schema 中看到允许揭示的知识 ID，不能改写公开摘要、借用其他角色知识或重复制造揭示事实。玩家定向发言、NPC 公开回应与揭示摘要写入该 NPC 的公开情景记忆；主持人只从 EventLog 接收已经揭示的摘要，不接触知识私有正文。

内置试玩入口现在是约 60 分钟的《灰烬中的车队》。它用 10 个场景、12 个关键行动、2 名独立 NPC 和成功、代价成功、失败三条黄金路线，从钟楼废墟推进到撤离车队或被俘结局。主持人上下文会获得当前场景的公开叙述素材与动态行动 Schema；结局后仍保留完整时间线、状态摘要、结局总结和确定性回放，内容版本与试玩排序都来自世界包元数据。

第二个约 60 分钟的内置试玩《静默轨道：赫利俄斯危机》使用内容 v2，提供 9 个场景、17 个关键行动、莱拉与索伦两名独立 NPC，以及“黎明重启”“带伤守望”“静默坠入阴影”三条路线。三条路线均通过主持人的类型化行动从建角运行到结局，并覆盖 SQLDelight 存档恢复、公开回放、定向对话与知识揭示；Runtime 没有增加空间站题材分支。

应用现在从 SQLDelight 投影多 Run 存档目录，可创建、继续、重命名、归档和查看已完成 Run。继续游戏会核对固定内容版本、事件连续性和 Reducer 重建；损坏或不兼容的 Snapshot 会被丢弃并从 EventLog 重建，同时显示诊断。主界面把角色状态、库存、关系、任务和进度钟拆为 Definition 驱动卡片；长时间线只保留最近 200 项并按需分页，公开回放包含判定随机记录和 Behavior 因果信息，但不读取凭据、模型正文、NPC 私有记忆或未揭示秘密。

存档目录还会记录最后持久化 Event 序列、终态 GM Turn、保存状态和非权威时间戳。启动页可以快速继续最近的未归档 Run，但仍会重新校验内容版本、EventLog、Snapshot 和生命周期；最近 Run 损坏时明确拒绝，不会静默改开旧存档。Event/Turn 已落盘而目录证据更新失败时，界面显示“事实已保存、目录待修复”，修复操作只重建派生目录元数据。

内容侧已经能够校验并装载安全的 `.worldloom` v1 容器，通过白名单 Behavior AST 提交类型化命令，并把短提示词、TXT 或 EPUB 资料依次转换为大纲、结构化草稿、快速模拟和可重新加载的世界包。生成任务保留阶段检查点、来源定位和人工复核问题。

TXT/EPUB 现在还可以先进入独立的识别工作区。任务用来源 SHA-256、版本化阶段和 SQLDelight 检查点支持取消、重启恢复与来源变化诊断；角色、地点、场景、目标和候选事实都携带片段 ID、字符范围和置信说明。共享 UI 只显示选中候选的局部来源上下文，未经完整可玩性验证的草稿不能试玩或安装。

BYOK 密钥由平台凭据保险箱保存：Android 使用 Keystore，iOS 使用 Keychain，Windows 使用用户级 DPAPI 加密。已保存密钥不会回显，也不会写入模型正文、世界包、存档或 EventLog。

## 开发与验证

需要 JDK 17 或更高版本；Android 构建还需要安装 API 36 SDK。只使用仓库提供的 Gradle Wrapper：

```powershell
./gradlew.bat check
./gradlew.bat :shared:content-generation:desktopTest
./gradlew.bat :apps:androidApp:assembleDebug
./gradlew.bat :apps:desktopApp:run
./gradlew.bat :shared:ui-game:compileKotlinIosSimulatorArm64
./gradlew.bat alphaGate
./tools/alpha-audit.ps1
```

Unix 与 macOS 使用 `./gradlew`。iOS 应用需要在安装 Xcode 的 macOS 上构建：

```bash
xcodebuild \
  -project apps/iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```
