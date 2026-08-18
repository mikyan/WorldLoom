# Worldloom 五轮迭代执行与验收记录

文档状态：Implemented 1.0<br>
更新日期：2026-08-18

## 1. 目的与完成标准

本记录把项目初始化后的五个增量收敛为可重复验收的工程基线。每轮必须提供真实端到端行为、防回归测试和与风险相称的平台编译，不能以空模块或未调用接口代替完成。

所有世界事实变化继续遵守：

```text
Intent / Agent Tool
→ typed GameSessionCommand
→ CommandValidator
→ WorldEngine
→ GameEvent
→ SQLDelight EventLog / Reducer
→ GameState
→ Presentation
```

## 2. 迭代结果

| 迭代 | 交付结果 | 关键验收 |
|---|---|---|
| 1. Manifest 与规则模块 | 增加版本化世界 manifest、`rule-module-api`、Registry、模块依赖/参数/能力校验；两个契约世界只注册显式启用能力 | 合法/非法 manifest、版本、依赖、参数和跨题材模块注册测试 |
| 2. 确定性判定 | 增加通用 Check Profile、确定性与随机规则、可审计 `RandomRecord`；回放复用已记录随机事实 | 同输入确定性、边界、随机记录、篡改拒绝和双契约世界判定测试 |
| 3. 持久化 | 使用 SQLDelight 保存 Run、Event 与 Snapshot；追加事务、恢复、快照校验和 Schema 迁移 | Desktop 数据库重启恢复、损坏/不连续事件拒绝、迁移验证与三端驱动编译 |
| 4. Agent 权威竖切 | 增加 Provider-neutral API、私有 Agent Session、预算/超时/循环检测、manifest/权限/Schema 约束的 Tool Gateway | Fake Provider 完成 Tool→Command→Event→Presentation；会话隔离、失败原子性、预算和跨题材契约测试 |
| 5. 真实 Provider 与凭据边界 | 增加 OpenAI Chat Completions SSE Adapter、自然语言 Compose 面板、三端 HTTP 引擎与 OS 凭据保险箱 | 模拟协议响应完成 Provider→Agent→Tool→Event→UI；密钥不进入正文；Android/Desktop/iOS 编译 |

## 3. 第五轮安全与协议约束

- OpenAI Adapter 只依赖 `provider-api` 中的中立消息、工具、用量和能力模型，供应商 JSON 不跨越适配层；
- 流式解析支持多段 SSE、文本增量、分片工具 ID/名称/参数、最终 Usage 和强制 `[DONE]`；
- `parallel_tool_calls` 关闭，模型工具参数仍由 Tool Gateway 根据当前 manifest、权限、Schema、允许 ID 与数值类型重新验证；
- Agent Turn 限制最大步骤、工具次数、超时、输入/输出 Token、费用与重复工具签名；
- Android 使用 Keystore，iOS 使用 Keychain，Windows 使用用户级 DPAPI；公开 UI 状态不携带 Secret；
- 测试中的凭据只存在于内存夹具，HTTP 请求正文断言不含凭据，错误映射不回传上游正文。

协议实现参考：[OpenAI Function Calling](https://developers.openai.com/api/docs/guides/function-calling)、[Chat Completions API Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) 和 [Ktor Client SSE](https://ktor.io/docs/client-server-sent-events.html)。

## 4. 稳定验收命令

Windows 开发机需要 JDK 17 或更高版本及 Android API 36 SDK：

```powershell
./gradlew.bat check
./gradlew.bat :shared:persistence:verifyCommonMainWorldloomDatabaseMigration
./gradlew.bat :shared:provider-openai:desktopTest
./gradlew.bat :shared:ui-game:compileKotlinIosSimulatorArm64
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

### 4.1 本轮实际结果

- 迭代 1～4 均在各自完成时执行模块测试与 `check + Android Debug + Desktop classes`，结果通过；
- 迭代 5 的 Vault、Agent、Provider 协议与端到端测试通过，Android、Desktop 与 iOS Simulator Kotlin 目标编译通过；
- 最终合并验收执行了上面的全量 Gradle 任务组合，结果为 `BUILD SUCCESSFUL`，共 449 个任务，其中 206 个实际执行、1 个来自缓存、242 个已是最新；
- `git diff --check`、本地 Markdown 链接/代码围栏、JSON/XML、秘密模式与生产 Runtime 题材硬编码扫描通过；
- 当前机器为 Windows，未执行 `xcodebuild`，因此不声称 iOS 宿主或 Keychain 真机运行已经验证。

## 5. 已知限制与下一入口

- 当前只实现 OpenAI Chat Completions Adapter；Anthropic、模型发现、连接测试和运行时模型切换尚未实现；
- Agent Session 当前是进程内私有存储，尚未进入持久化记忆和异步压缩；
- iOS Keychain 与 Android Keystore 已完成目标源码编译，但仍需要相应系统/真机集成测试；Windows DPAPI 已有本机往返测试；
- macOS Desktop Keychain 与 Linux Secret Service 尚未实现，当前安全回退只在会话内保存；
- `.worldloom` ZIP/签名、Behavior AST、完整规则模块、内容生成与语音仍按设计文档后续推进。

下一条竖切应优先实现可恢复的持久化 Agent Session 与上下文检查点，或先补齐 Provider/模型设置与连接测试；两者都不得扩大世界事实写入边界。
