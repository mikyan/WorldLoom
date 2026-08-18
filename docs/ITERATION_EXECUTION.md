# Worldloom 十六轮迭代执行与验收记录

文档状态：Implemented 2.1<br>
更新日期：2026-08-18

## 1. 目的与完成标准

本记录把项目初始化后的十六个增量收敛为可重复验收的工程基线。每轮必须提供真实端到端行为、防回归测试和与风险相称的平台编译，不能以空模块或未调用接口代替完成。

所有世界事实变化继续遵守：

```text
Intent / Agent Tool / Behavior
→ typed GameSessionCommand
→ CommandValidator
→ WorldEngine
→ GameEvent
→ SQLDelight EventLog / Reducer
→ GameState
→ Presentation
```

Provider、Agent 记忆和内容生成元数据可以各自持久化，但都不能绕过上述管线修改客观世界事实。

## 2. 迭代结果

| 迭代 | 交付结果 | 关键验收 |
|---|---|---|
| 1. Manifest 与规则模块 | 增加版本化世界 manifest、`rule-module-api`、Registry、模块依赖/参数/能力校验；两个契约世界只注册显式启用能力 | 合法/非法 manifest、版本、依赖、参数和跨题材模块注册测试 |
| 2. 确定性判定 | 增加通用 Check Profile、确定性与随机规则、可审计 `RandomRecord`；回放复用已记录随机事实 | 同输入确定性、边界、随机记录、篡改拒绝和双契约世界判定测试 |
| 3. 持久化 | 使用 SQLDelight 保存 Run、Event 与 Snapshot；追加事务、恢复、快照校验和 Schema 迁移 | Desktop 数据库重启恢复、损坏/不连续事件拒绝、迁移验证与三端驱动编译 |
| 4. Agent 权威竖切 | 增加 Provider-neutral API、私有 Agent Session、预算/超时/循环检测、manifest/权限/Schema 约束的 Tool Gateway | Fake Provider 完成 Tool→Command→Event→Presentation；会话隔离、失败原子性、预算和跨题材契约测试 |
| 5. 真实 Provider 与凭据边界 | 增加 OpenAI Chat Completions SSE Adapter、自然语言 Compose 面板、三端 HTTP 引擎与 OS 凭据保险箱 | 模拟协议响应完成 Provider→Agent→Tool→Event→UI；密钥不进入正文；Android/Desktop/iOS 编译 |
| 6. Provider 设置中心 | 增加供应商中立配置、SQLDelight 非秘密设置、运行时路由、OpenAI 连接测试和模型发现 UI | 默认选择与重启恢复、HTTPS/本地传输约束、协议错误和动态选择测试 |
| 7. 持久化 Agent Session | Agent 消息按 Run/Agent 隔离并持久化，使用修订号避免并发覆盖；应用三端组装使用 SQL Store | 进程内数据库重建、所有权隔离、修订冲突和完整 Agent 回合测试 |
| 8. 结构化记忆与上下文 | 增加 Turn、情景记忆、信念、目标、关系、来源 Event 链接和相关性召回 | 数据库往返、角色隔离、显著性/置信度/实体/标签/新近度排序和硬水位裁剪测试 |
| 9. 异步上下文压缩 | 在 50%/75%、回合数与显式请求阈值启动冻结范围压缩，候选校验后原子发布检查点 | 重叠请求合并、幂等键、非法候选回退、最近原文保留和非阻塞调度测试 |
| 10. NPC Agent 竖切 | 增加稳定 NPC Profile/Session、私有感知和记忆、独立 Tool 权限及确定性事件唤醒调度 | 多 NPC 上下文不串线、隐藏信息不泄露、静态/动态唤醒与世界变化结果测试 |
| 11. `.worldloom` v1 | 增加纯 Kotlin STORED ZIP 编解码、manifest/Definition/模块装载和包内安全检查 | 两个契约世界往返；CRC、重复项、路径穿越、大小限制、篡改和不支持压缩方法拒绝测试 |
| 12. Behavior AST v1 | 增加版本化类型 AST、表达式校验/求值、Command 白名单和权威 Command Sink | 非法路径/类型/命令拒绝；无循环、I/O、时间或隐式随机；确定性命令提交测试 |
| 13. 角色与规则配置 | 增加固定、模板、点数、叙述四种 CharacterCreationProfile 模式及 RuleProfile | DefinitionId/TypedValue 类型、预算/边界、模块版本与 Check 引用验证和规范 JSON 往返 |
| 14. Brief-to-World | 将 5000 字以内提示词分阶段转换为大纲、结构化草稿、校验、快速模拟和可加载世界包 | 5000/5001 字边界、来源映射、复核问题、取消/恢复和发布包重载测试 |
| 15. Corpus-to-World | 增加 TXT/EPUB 解析、章节/分块/来源定位、50 万字符限制和可恢复生成管线 | UTF-8/BOM/UTF-16/GB18030、压缩 EPUB spine 顺序、取消/恢复、可选原文归档和进度测试 |
| 16. 可玩世界契约 | 增加 `worldloom.playable-world/v1`、静态引用/模块/失败推进/可达性校验、确定性黄金路线和 application driver 审计接口；战争与空间站夹具共用加载路径 | 缺失入口、悬空引用、不可达结局、缺少失败、未启用模块、未记录随机事实、跨题材加载和 Tool/Command/Event/replay 回执测试 |

## 3. 安全、确定性与兼容约束

- OpenAI Adapter 只依赖 `provider-api` 中的中立消息、工具、用量和能力模型，供应商 JSON 不跨越适配层；
- Provider Configuration 只保存非秘密字段和 Vault Key 引用，密钥仍只在请求边界内进入 Authorization Header；
- Agent Session、记忆、检查点按 Run/Agent 分区，压缩结果不能覆盖 EventLog，也不能把某个 NPC 的私有上下文提供给其他 Agent；
- Tool Gateway 与 Behavior Runtime 都只能提交白名单内的类型化 Command，写操作仍由 CommandValidator 和 WorldEngine 裁决；
- `.worldloom` v1 拒绝绝对路径、路径穿越、重复项、CRC 不一致、超限内容和当前不支持的压缩方法；世界包不能携带任意脚本；
- 内容生成先校验 Definition、角色配置、规则配置、Behavior 和来源引用，再创建初始状态做快速模拟，最后重新装载生成包；
- 声明 `playableContractPath` 的世界必须在创建 Run 前通过角色入口、Scene/Action、目标、结局、表现、Behavior、模块和黄金路线验证；旧包不声明时保持兼容但不能标记为可玩；
- Command、Event 和既有世界包 Schema 未发生破坏性变更；数据库通过 `2.sqm` 从第一版迁移新增 Agent 与 Provider 表。

OpenAI 协议实现参考：[OpenAI Function Calling](https://developers.openai.com/api/docs/guides/function-calling)、[Chat Completions API Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) 和 [Ktor Client SSE](https://ktor.io/docs/client-server-sent-events.html)。

## 4. 稳定验收命令

Windows 开发机需要 JDK 17 或更高版本及 Android API 36 SDK：

```powershell
./gradlew.bat check
./gradlew.bat :shared:persistence:verifyCommonMainWorldloomDatabaseMigration
./gradlew.bat :shared:provider-openai:desktopTest
./gradlew.bat :shared:world-package:desktopTest
./gradlew.bat :shared:behavior-runtime:desktopTest
./gradlew.bat :shared:content-generation:desktopTest
./gradlew.bat :shared:ui-game:compileKotlinIosSimulatorArm64
./gradlew.bat :shared:content-generation:compileKotlinIosSimulatorArm64
./gradlew.bat :apps:androidApp:assembleDebug
./gradlew.bat :apps:desktopApp:classes
```

macOS CI 或开发机继续负责 Xcode 宿主、Security Framework 链接和 iOS Simulator 应用构建：

```bash
xcodebuild \
  -project apps/iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## 5. 已知限制与下一入口

- `.worldloom` v1 共享编解码当前只接受 STORED 条目且尚未签名；Desktop/Android 的 EPUB 平台读取器支持常见压缩条目；
- iOS 公共内容生成目标已编译，但压缩 EPUB、GB18030 与系统文件选择的 iOS 平台桥仍待接入并做真机验证；
- `GenerationTaskStore` 已提供可恢复接口和内存实现，跨进程持久化实现仍待接入；
- 当前只实现 OpenAI Chat Completions Adapter；Anthropic、语音 Provider 仍是后续增量；
- iOS Keychain 与 Android Keystore 已完成目标源码编译，但仍需要相应系统/真机集成测试；Windows DPAPI 已有本机往返测试；
- macOS Desktop Keychain 与 Linux Secret Service 尚未实现，当前安全回退只在会话内保存。

下一条竖切优先把 CharacterCreationProfile 接入显式 Run 生命周期和权威 Command/Event，使内置战争短篇能够从正式“新游戏”入口创建角色。主持人 Agent、场景、时间、冒险模块、Behavior/NPC 和完整剧本随后按可玩闭环推进；持久化生成任务与 iOS 内容导入桥延后到该闭环稳定之后。
