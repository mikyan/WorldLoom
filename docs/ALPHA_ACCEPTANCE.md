# Worldloom 封闭 Alpha 验收

文档状态：Alpha release<br>
版本：`0.0.1`<br>
更新日期：2026-08-31

## 1. 本轮产品范围

封闭 Alpha 的主路径是“选择任一内置短篇 → 建角 → 由主持人推进 → NPC/Behavior 响应 → 保存恢复 → 抵达结局 → 校验公开回放”。Fake Agent 是发布权威，真实 Provider 只作为开发者自带凭据后的可选协议冒烟，不参与确定性发布判定。

TXT/EPUB 识别与草稿沙箱已有实验性竖切，但不进入本轮普通玩家主路径，也不是 Alpha ready 的前置条件。它们只能处理通过聚合可玩性门禁的固定草稿版本，并与正式 Run/Agent/存档隔离。

## 2. 主持人关键旅程

`AlphaJourneySystemTest` 使用 SQLDelight EventLog/Snapshot 和持久化 GM Turn，完成以下单一旅程：

```text
加载内置世界 → 固定角色建角 → 显式等待 → 搜寻活动
→ Behavior 推进任务 → 两名 NPC 受限公开回应 → 风险旅行
→ Fake GM 按动态 Tool Schema 多轮主持 → 进程重建/存档恢复
→ 继续主持至可达结局 → EventLog 重放 → 离线公开回放校验
```

测试不直接修改 `GameState`。主持人、NPC 与 Behavior 的客观变化都重新经过 Tool/Command、Validator、WorldEngine、EventLog 和 Reducer。该旅程在加固中发现并修复了 NPC 公开事件字段与多态 JSON 判别字段冲突的问题；回归测试现在要求 NPC 公开事件可以原子写入 SQL、恢复并进入公开回放。

## 3. 故障注入矩阵

| 故障 | 期望 | 自动证据 |
|---|---|---|
| Provider 断网/不可用 | 工具调用前不改变世界，可重试 | `AgentRuntimeTest.providerOutageRateLimitAndTimeoutBeforeToolsLeaveTheWorldUntouched` |
| Provider 限流 | 与断网相同，不伪造叙事事实 | 同上，覆盖 `RATE_LIMITED` |
| 工具参数、权限或白名单失败 | 在 Command 前拒绝，不追加 Event | `AgentRuntimeTest.invalidArgumentsAreRejectedBeforeAnyEventIsAppended`、`permissionAndManifestBothConstrainToolCalls` |
| EventStore 事务失败/磁盘不足等价注入 | 批次原子失败，状态不变，重试不重掷 | `TemporalContractGameSessionTest.diskFullLikeAtomicAppendFailureDoesNotRerollOnRetry` |
| 应用在 GM、Behavior 或 NPC 工作窗口终止 | 已提交事实保留，工作项幂等恢复或明确中断 | `GameTurnOrchestratorContractTest`、`BehaviorProgressionContractTest`、`NpcSceneOrchestratorContractTest` |
| 进程退出后继续游戏 | Snapshot + 尾部 Event 重建同一状态后继续 | `AlphaJourneySystemTest`、`PersistentGameSessionTest` |
| Snapshot JSON/Schema/身份损坏 | 丢弃缓存并从完整 EventLog 重建，给出诊断 | `SqlDelightEventStoreTest.corruptSnapshotFallsBackToCompleteEventLogWithDiagnostic` |
| EventLog 损坏或不连续 | 拒绝继续，不以默认值掩盖 | `SqlDelightEventStoreTest.corruptStoredEventIsReportedWithoutSilentDefaults` |
| 草稿含脚本、未授权 Behavior 或不可达结局 | 在 Run/Provider 前拒绝并报告路径 | `DraftPlaytestGateTest.forbiddenBehaviorCommandAndUnreachableEndingAreRejectedWithPaths` |
| 沙箱重置/删除或安装发布失败 | 正式 EventLog 不变；旧安装版本继续有效 | `HostedDraftSandboxTest.validatedDraftUsesHostedPipelineWhileResetAndDeleteStayIsolated`、`DraftPlaytestGateTest.atomicInstallStripsSourcesAndSandboxFactsWhileFailureKeepsPreviousRecord` |

## 4. Provider 与隐私

- 发布权威：Fake GM/NPC，根据当前 Tool Schema 选择行动，完整走过权威管线；
- 协议冒烟：`OpenAiAgentVerticalSliceTest` 用本地模拟 SSE/Tool 协议验证 OpenAI Adapter，不接触外网和真实密钥；
- 可选真实冒烟：开发者可在应用的 Provider 设置页把密钥写入平台 Vault 后执行连接测试和一个新 Run 回合。此操作不得把密钥、Authorization Header、完整模型正文或 NPC 私有上下文写入日志、存档、EventLog 或测试产物；
- 真实 Provider 结果不作为发布黄金结果，因为网络、模型版本和采样都不具备确定性。

## 5. 性能与体验基线

自动门禁采用以下可重复预算：

- 内置世界目录加载、Run 创建和角色确认在 Desktop 测试进程内不超过 10 秒；
- Fake GM 前台回合不超过 10 秒，实际网络 Provider 仍由超时和预算限制；
- Presentation 常驻时间线最多 200 项，较早事件按最多 200 项分页读取；
- NPC 每场景并发、每事件唤醒数和每次调度回合数均有硬上限，前台主持回合优先；
- 共享 UI 使用 Lazy 列表/卡片并提供减少动态效果路径，以 30 FPS（33.3 ms 帧预算）作为平台验收下限。

本轮自动测试与 Windows Desktop 发行构建在 Windows 11、Intel Core Ultra X7 358H、32 GB RAM 开发机完成。Android 10/4 GB、iPhone SE 2 或同级设备以及 Windows 11/8 GB 的真机帧时间和常驻内存仍需在封闭测试设备池采样；这属于分发前的设备验收，不应以开发机结果冒充最低设备数据。

## 6. 路线与试玩记录

内置内容固定三条脚本化试玩路线：

| 路线 | 覆盖 | 结果 |
|---|---|---|
| 完整成功 | 药房、玛拉、避难所、交涉、车队 | `war.ending.hopeful` |
| 代价成功 | 药房、托马斯、水塔、涵洞、车队 | `war.ending.costly` |
| 连续失败 | 火力压制、拘留、逃脱失败 | `war.ending.captured` |

三条路线都由声明式内容驱动并验证确定性重放。失败路线没有无反馈死路；库存越界和场景外行动会被拒绝且不写事实。人工视觉复核应继续关注文字溢出、窄屏操作、叙事重复和资源平衡，发现内容问题时优先修改世界包，不在 Runtime 中加入战争题材分支。

## 7. 发行与哈希

版本和 Schema 清单位于 `release/alpha-manifest.json`。可重复发行命令：

```powershell
./gradlew.bat alphaRelease
```

任务构建未签名 Android Release APK 与当前操作系统 Desktop Release 可运行 Uber JAR，并写出：

```text
build/alpha/artifact-hashes.sha256
```

完整仓库门禁使用：

```powershell
./gradlew.bat round35CandidateGate --no-configuration-cache
./tools/alpha-audit.ps1
```

2026-08-19 的 Windows 仓库候选门禁执行成功，共完成或复用 635 个 Gradle 任务；覆盖全仓检查、数据库迁移、两个内置世界和来源草稿、秘密/题材审计、Android/Desktop Release、artifact hash、Desktop 5 秒启动存活与 iOS Simulator Kotlin 编译。该结果只证明仓库候选版本可构建、可启动并完成确定性的主持/沙箱旅程，不替代下述真机与人工验收。

iOS 交接使用 `apps/iosApp/iosApp.xcodeproj`；Windows 门禁编译 iOS Simulator Kotlin 目标，最终宿主构建必须在 macOS/Xcode 执行：

```bash
xcodebuild \
  -project apps/iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Release \
  -sdk iphonesimulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## 8. 已知限制

- Android Alpha APK 未签名，分发时需要单独的受控签名流程；
- Desktop Alpha 当前产出可运行 Uber JAR；生成 MSI/DMG/DEB 安装包还要求构建机提供带 `jpackage` 的完整 JDK；
- Windows 不能完成 iOS Xcode 宿主构建、签名或真机测试；
- 实时 Provider 冒烟需要开发者主动提供自己的 Vault 凭据，本仓库和发布门禁不会读取或保存该密钥；
- 两个内置短剧本已经完整；草稿 Sandbox/InstalledWorld Store 当前仍是进程内实现，尚未提供跨进程沙箱恢复或平台文件目录安装；
- 最低设备的帧时间与常驻内存需要在封闭测试设备池补录。

因此当前状态保持为 **Alpha candidate**。在真实 Provider 可选冒烟、最低设备采样、多轮人工试玩与 macOS/iOS 宿主构建完成前，不把版本标记为 Alpha ready。
