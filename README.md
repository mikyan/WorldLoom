# Worldloom / 织境

Worldloom 是一款面向 Android、iOS 与桌面端、由 AI 主持的单人数字跑团 RPG。玩家像桌面跑团中一样用自然语言声明行动，影响一个持续演化的世界；Agent 负责理解、扮演和叙述，确定性的世界引擎负责规则裁决与事实更新。

卡片、面板和时间线用于展示世界包定义的角色状态、世界信息、模块内容与判定结果；自然语言行动、规则判定和持续演化的世界共同构成游戏体验。

> 项目当前处于设计与工程准备阶段，仓库内容聚焦产品设计基线和架构决策。

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

## 仓库结构

```text
docs/
├── DESIGN.md
└── decisions/
    ├── 0001-compose-multiplatform.md
    └── 0002-world-configuration-boundary.md
```

正式工程骨架将在设计基线确认后加入。
