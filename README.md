# Worldloom / 织境

Worldloom 是一款面向 Android、iOS 与桌面端、由 AI 主持的单人数字跑团 RPG。玩家像桌面跑团中一样用自然语言声明行动，影响一个持续演化的世界；Agent 负责理解、扮演和叙述，确定性的世界引擎负责规则裁决与事实更新。

卡片、面板和时间线用于展示世界包定义的角色状态、世界信息、模块内容与判定结果；自然语言行动、规则判定和持续演化的世界共同构成游戏体验。

> 项目已经完成十六轮工程迭代：在可运行的 KMP/Compose 竖切上，继续落地 Provider 配置、持久化 Agent 会话与结构化记忆、异步上下文压缩、NPC Agent、`.worldloom` v1、Behavior AST、角色/规则配置、内容生成基础，以及用于内置剧本验收的 `playable-world/v1` 可玩世界契约。

## 产品方向

- 自然语言自由行动为主，情境化快捷建议为辅；
- 用自然语言生成完整剧本和可玩的世界；
- 将 TXT、EPUB 等长篇复杂剧情资料转化为结构化游戏内容；
- 后续接入语音转写与 TTS 模型，支持全程语音游玩；
- 属性、技能、资源、角色创建与领域规则由剧本生成，世界包按需启用规则模块，经 Schema 验证后加载；
- 世界状态和规则由本地权威引擎维护，掷骰通过可审计的 Agent Tool 执行并写入事件记录；
- 主持人与每个 NPC 都拥有独立 Agent 上下文，NPC 按事件和场景动态唤醒；
- Agent 上下文按使用量主动、增量、异步压缩，不因记忆整理阻塞玩家操作；
- 角色面板、世界信息卡片、动效和声音把世界事件转化为清晰反馈；
- 同一 Runtime 可以加载不同题材的 `.worldloom` 世界包。

首个内置短篇世界采用战争生存题材：一名 13 岁少年需要在一场持续 300～1000 个世界日、结束时间未知的战争中活下去。

## 文档

- [项目设计文档](docs/DESIGN.md)
- [项目初始化设计](docs/PROJECT_INITIALIZATION.md)
- [十五轮迭代执行与验收记录](docs/ITERATION_EXECUTION.md)
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

内容侧已经能够校验并装载安全的 `.worldloom` v1 容器，通过白名单 Behavior AST 提交类型化命令，并把短提示词、TXT 或 EPUB 资料依次转换为大纲、结构化草稿、快速模拟和可重新加载的世界包。生成任务保留阶段检查点、来源定位和人工复核问题。

BYOK 密钥由平台凭据保险箱保存：Android 使用 Keystore，iOS 使用 Keychain，Windows 使用用户级 DPAPI 加密。已保存密钥不会回显，也不会写入模型正文、世界包、存档或 EventLog。

## 开发与验证

需要 JDK 17 或更高版本；Android 构建还需要安装 API 36 SDK。只使用仓库提供的 Gradle Wrapper：

```powershell
./gradlew.bat check
./gradlew.bat :shared:content-generation:desktopTest
./gradlew.bat :apps:androidApp:assembleDebug
./gradlew.bat :apps:desktopApp:run
./gradlew.bat :shared:ui-game:compileKotlinIosSimulatorArm64
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
