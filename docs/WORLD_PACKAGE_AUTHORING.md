# Worldloom 世界包创作指南

本文说明人工编写的世界包如何声明并通过 `worldloom.playable-world/v1`。自动识别、TXT/EPUB 导入和世界生成要在同一契约稳定且内置短篇完成试玩后再继续扩展。

## 最小文件

当前契约夹具采用以下布局：

```text
manifest.json
world.json
playable-world.json
character-profile.json   # 使用角色创建 Profile 时需要
behaviors/               # 契约引用 Behavior 时按需提供
```

`manifest.json` 使用可选字段声明契约路径：

```json
{
  "schemaVersion": 1,
  "runtimeApiVersion": 1,
  "worldId": "example.world",
  "worldDefinitionPath": "world.json",
  "modules": [],
  "playableContractPath": "playable-world.json"
}
```

没有 `playableContractPath` 的旧包仍可作为兼容夹具加载，但不能通过“可完整游玩”验收。

## playable-world/v1

契约必须包含：

- `character`：`profilePath` 或 `prebuiltPlayerEntityId` 二选一；使用 Profile 时还必须提供指向 `initialEntities` 模板的稳定 `playerEntityId`；
- `initialSceneId`：唯一开局场景；
- `requiredModuleIds`：路线实际依赖且已在 manifest 启用的模块；
- `scenes`：场景 ID、玩家标签和当前可用 Action；
- `actions`：所属场景、可选 CheckProfile、所有结果档位及其推进；
- `objectives`：至少一个玩家可以理解的进展目标；
- `endings`：从开局图可达的终局；
- `presentationIds`：关键状态或判定的表现绑定；
- `behaviors`：BehaviorId 与包内 AST 路径，可为空；
- `goldenRoutes`：至少一条从初始场景走到终局的确定性路线。

行动结果的 `progression` 至少要包含下一场景、目标推进、结局或明确允许重试中的一项。含 CheckProfile 的行动必须覆盖该检查的全部结果档位，并至少把一个档位标记为 `FAILURE`；失败仍要产生新局面、代价、可见进展、重试机会或终局。

## NPC 知识与公开揭示

`playableNpcs[].knowledge` 中的每项知识必须使用包内全局唯一的稳定 DefinitionId。`privateText` 只进入该 NPC 的私有上下文；需要允许角色公开时，同时提供 `publicSummary` 并设置 `revealable: true`。公开摘要应写成可直接进入玩家时间线的既成事实，不要依赖模型改写，也不要包含作者仍想保密的尾注。

Runtime 只把当前 NPC 自身可揭示的 ID 放入 `npc.speak.revealKnowledgeIds`。提交后，Validator 会再次核对 NPC、Entity、Scene、知识 ID 和固定摘要；公开 Event 只保存 `publicSummary`。旧包中的 `privateKnowledge` 仍可读取，但不会自动变成可揭示知识，作者应显式迁移后再开放。

## 随机与黄金路线

RANDOM Check 的路线步骤必须记录与骰子表达式数量和面数一致的 `randomValues`。验证器使用这些数值计算结果档位；不得在测试或回放时重新掷骰。DETERMINISTIC Check 不得提供随机值；没有 CheckProfile 的选择需要显式提供 `selectedOutcomeId`。

`GoldenRouteRunner` 只提供 Fake Agent 的固定选择。实际 application driver 每一步必须经过受限 Tool、类型化 GameCommand、CommandValidator、WorldEngine 和 EventStore，并返回：

- ToolId；
- CommandId；
- 一个或多个已提交 EventId；
- 递增的最后事件序号；
- 最终结局（若该步终止 Run）。

路线完成后还必须从 EventLog replay，并得到相同的最后序号与结局。

## 验证规则

世界包加载会在创建 Run 前拒绝以下问题：

- 缺少角色入口、契约文件或 Profile 文件；
- manifest 未启用契约要求的模块；
- Scene、Action、CheckProfile、Objective、Ending、Presentation 或 Behavior 悬空；
- 同类 ID 或引用重复；
- 行动没有失败推进或结果档位不完整；
- 可达场景没有后续行动；
- 声明的结局不可达；
- 黄金路线使用了当前场景不存在的行动、非法随机记录或错误结局；
- Behavior 文件非法、ID 不一致或越过命令白名单。

战争生存与空间站 AI 夹具必须通过同一个加载器和验证器。Runtime、共享 UI 和规则模块不得按 WorldId、题材名称或 `war.*`/`station.*` DefinitionId 分支。

## 当前验收命令

JDK 17 或更高版本下运行：

```text
./gradlew.bat :shared:world-package:desktopTest
```

Unix 与 macOS 使用 `./gradlew`。当前两个内置世界已经共用角色创建、主持回合、定向 NPC 对话、知识揭示和权威事件管线；新增世界仍须通过相同契约与跨题材测试。
