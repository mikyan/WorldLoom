# ADR-0002：世界配置与程序代码边界

状态：Accepted<br>
日期：2026-08-17

## 背景

Worldloom 的首个内置世界是战争生存题材，包含五项属性、2d6、饥饿、伤势、补给、日历和隐藏战争结束日。Runtime 需要继续承载规则、角色形态和时间体系完全不同的世界，因此首个世界的 DefinitionId、字段和模块不能进入通用程序模型。

## 决策

系统采用四层能力边界：

1. 引擎基础设施实现类型系统、Command/Event、验证、事件溯源、随机审计、Agent Loop、DSL 解释和持久化；
2. 可选规则模块实现可复用算法及其 Schema、Tool、Command、Event、Reducer 和投影；
3. 世界包选择模块，并提供属性、技能、资源、状态机、判定、行为和表现定义；
4. 具体剧本只在世界包中保存人物、地点、目标、事件、变量和结局。

Runtime 使用命名空间 `DefinitionId`、`TypedValue`、动态实体组件、世界变量和模块状态保存世界数据。注册模块可以使用类型安全的 Kotlin 类型实现复杂算法，但不得把某个世界的 DefinitionId 编译进模块。

`manifest.json` 声明启用模块及版本。只有启用模块可以注册对应 Tool、Command、Event、Reducer、投影和 UI 能力。世界包不需要为未启用模块提供数据文件。

UI 通过 `PresentationDefinition` 绑定 DefinitionId 和模块投影。共享 UI 代码不能直接读取特定属性或资源键。

## 禁止的实现

- 固定的 Attribute、Skill、Resource、Condition 或 LifeState 内容枚举；
- `PlayerState.health`、`CharacterState.hunger`、`WorldState.warEndDay` 等题材字段；
- 根据 worldId、题材名称或首个世界 DefinitionId 分支的 Runtime 代码；
- 启动时无条件注册背包、战斗、任务、线索、日历等所有模块；
- UI 直接读取 `war.health`、`war.hunger` 或其他世界资源；
- 世界包携带或执行任意 Kotlin、JavaScript、Lua 等代码。

## 契约测试

工程至少维护两个共享 Runtime 的契约世界：

- `war-survival`：五属性、2d6、生存资源、日历、背包、旅行和遭遇；
- `station-ai`：无肉体玩家，使用运算、完整性、影响力、能源、带宽、访问权限和离散 Tick，不启用生命、饥饿、背包、装备、休息、日历和战斗模块。

两者必须在不修改 Runtime 代码的情况下完成加载、角色创建、Tool 注册、状态更新、存档、回放和 UI 投影。

## 结果

该边界增加 Definition 引用、模块依赖、Schema 验证和动态 UI 的实现复杂度，但可以阻止首个世界塑造通用数据模型，并允许后续世界复用 Runtime 而不增加题材分支。
