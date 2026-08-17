# Worldloom / 织境

Worldloom 是一款面向 Android、iOS 与桌面端的单人叙事卡牌 RPG。玩家通过自然语言和行动卡影响一个持续演化的世界；Agent 负责理解、扮演和叙述，确定性的世界引擎负责规则裁决与事实更新。

> 项目当前处于设计与工程准备阶段。仓库暂时只保存设计基线和架构决策，不包含可运行 Demo 或正式游戏代码。

## 产品方向

- 自然语言行动与卡牌操作并存；
- 世界状态、规则与骰子由本地确定性引擎维护；
- 主持人、普通 NPC 和关键 NPC 使用不同层级的 Agent 能力；
- 卡牌、动效和声音把世界事件转化为清晰的游戏反馈；
- 同一 Runtime 可以加载不同题材的 `.worldloom` 世界包。

## 文档

- [项目设计文档](docs/DESIGN.md)
- [ADR-0001：选择 Kotlin 与 Compose Multiplatform](docs/decisions/0001-compose-multiplatform.md)

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
    └── 0001-compose-multiplatform.md
```

正式工程骨架将在设计基线确认后加入。
