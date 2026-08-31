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
- `guidance`：可选的版本化首次教程、场景提示及其 Action/Activity/Travel 目标；
- `goldenRoutes`：至少一条从初始场景走到终局的确定性路线。

行动结果的 `progression` 至少要包含下一场景、目标推进、结局或明确允许重试中的一项。含 CheckProfile 的行动必须覆盖该检查的全部结果档位，并至少把一个档位标记为 `FAILURE`；失败仍要产生新局面、代价、可见进展、重试机会或终局。

## NPC 知识与公开揭示

`playableNpcs[].knowledge` 中的每项知识必须使用包内全局唯一的稳定 DefinitionId。`privateText` 只进入该 NPC 的私有上下文；需要允许角色公开时，同时提供 `publicSummary` 并设置 `revealable: true`。公开摘要应写成可直接进入玩家时间线的既成事实，不要依赖模型改写，也不要包含作者仍想保密的尾注。

NPC 可通过可选的 `avatarAssetId` 引用 UI 提供的头像资产。该值必须是小写稳定标识符；未知标识符会回退为姓名首字头像，不能作为 Runtime 逻辑或信息权限的判断依据。`publicIntroduction` 必须保持 spoiler-safe，玩家点击成员头像时会展示该介绍。

Runtime 只把当前 NPC 自身可揭示的 ID 放入 `npc.speak.revealKnowledgeIds`。提交后，Validator 会再次核对 NPC、Entity、Scene、知识 ID 和固定摘要；公开 Event 只保存 `publicSummary`。旧包中的 `privateKnowledge` 仍可读取，但不会自动变成可揭示知识，作者应显式迁移后再开放。

## 附近角色与远程通讯

场景的 `participantEntityIds` 定义玩家进入该场景时的初始附近角色。运行中 PM 可以通过受限工具让已配置 NPC 加入或离开附近清单；作者不能用叙述文字代替这一事实变化。

`remoteCommunicationMethods` 可声明题材无关的远程通讯手段：

```json
{
  "remoteCommunicationMethods": [
    {
      "id": "example.communication.radio",
      "label": "便携电台",
      "participantEntityIds": ["player", "npc-guide"]
    }
  ]
}
```

每种方式需要稳定 DefinitionId、面向玩家的非空标签，以及至少两个不同且已初始化的 Entity。只有玩家 Entity 与目标 NPC Entity 同时出现在同一方式中，目标不在身边时 UI 和 Tool Gateway 才允许 `#角色` 私聊。`@角色` 始终要求目标在玩家身边，并向 PM 与全部附近 NPC 可见；私聊不会进入普通公开回放。

## 教程、场景提示与脱困出口

`guidance.schemaVersion` 当前为 `1`。教程使用 `RUN_START` 或 `SCENE_ENTER` 触发；`RUN_START` 固定对应 `initialSceneId`，`SCENE_ENTER` 必须显式填写 `sceneId`。提示始终绑定一个场景。教程和提示的 `target` 可引用以下通用目标：

- `ACTION`：目标 Action 必须属于该场景；教程引用的 Action 还必须无任务条件，保证首次展示时真实可用；
- `ACTIVITY`：目标 Activity 必须在该场景可用；
- `TRAVEL`：目标 Route 的 `fromSceneId` 必须是该场景。

示例：

```json
{
  "guidance": {
    "schemaVersion": 1,
    "tutorials": [
      {
        "id": "example.tutorial.first-choice",
        "trigger": "RUN_START",
        "text": "先观察公开线索，再告诉主持人你准备怎么做。",
        "target": {"kind": "ACTION", "id": "example.action.observe"}
      }
    ],
    "hints": [
      {
        "id": "example.hint.rest",
        "sceneId": "example.scene.shelter",
        "text": "资源不足时可以先休整。",
        "target": {"kind": "ACTIVITY", "id": "example.activity.rest"}
      }
    ]
  }
}
```

每个可达场景都必须至少有一个无条件 Action、当前场景 Activity 或出发 Route，避免玩家进入没有合法推进方式的动态死路。UI 只把建议转换为可编辑的自然语言草稿；它不会自动调用 Tool、执行行动或写入 EventLog。作者不能依靠教程文字补造未公开事实。

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
- Guidance 版本错误、目标悬空、场景外不可见或可达场景形成动态死路；
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
