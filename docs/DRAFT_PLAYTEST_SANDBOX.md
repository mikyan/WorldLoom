# 世界草稿试玩沙箱

文档状态：Draft playtest schema v1<br>
适用阶段：第 35 轮

## 1. 目标与进入条件

草稿试玩用于在安装前验证一个固定内容版本能否复用正式游玩管线。它不是任意内容执行环境，也不会因为来源识别完成而自动开放。唯一入口是 `DraftPlayabilityValidator`：

```text
识别或手写草稿
→ .worldloom 容器与 Manifest/Definition Schema
→ 模块版本、引用闭包与表现绑定
→ Behavior AST 与 Command 白名单
→ 失败推进、黄金路线与全部结局覆盖
→ 可选来源片段、字符范围和置信诊断
→ 固定版本 Sandbox Run
```

任一问题都会返回稳定错误类别、路径和安全说明，并在创建 Run、调用 Provider 或执行 Behavior 前停止。JavaScript、Kotlin、Lua、Wasm、原生库等可执行条目始终禁止。

## 2. 与正式游玩的关系

验证成功后，`HostedDraftSandboxManager` 从草稿的声明式权威条目建立临时目录，并复用 `DefaultGameSession`、`DefaultGameAgentController`、NPC 调度、Behavior、Command/Event、Reducer、存档投影和公开回放。因此沙箱能发现真实主持链路中的内容问题，而不是运行一套弱化模拟器。

隔离边界如下：

- SandboxId、RunId 和 Agent SessionId 使用 `sandbox.*` 前缀；
- EventStore、GM/NPC Session、GM/NPC Memory、Turn 与 NPC Work Store 都由沙箱独占；
- 沙箱不写正式 Run 目录，也不读取或更新正式 EventLog；
- 重置先成功创建下一 generation，再删除旧 generation；失败时保留原沙箱；
- 删除只移除该沙箱目录和内存引用，不触碰已安装世界或正式存档。

当前实现提供进程内沙箱 Store，适合快速试玩与自动回归；跨进程恢复沙箱尚未作为产品承诺。正式 Run 的 SQLDelight 持久化和恢复行为仍由同一 Session 测试覆盖。

## 3. 原子安装

`DraftInstaller` 在发布前再次运行完整验证，并从已验证包中只复制以下权威声明式资源：

- `manifest.json` 与 World Definition；
- `playable-world/v1`；
- Character Creation Profile；
- 合同明确引用且已验证的 Behavior AST。

来源全文、识别中间文件、Provider/Agent 数据、凭据、生成诊断和 Sandbox EventLog 不进入安装包。清理后的包再次验证并以 SHA-256 形成内容地址；目录发布使用期望旧地址进行比较交换。复制、最终验证、并发冲突或发布失败时，目录继续引用上一有效版本，不产生半安装记录。

## 4. 候选门禁

Windows 候选验证命令为：

```powershell
./gradlew.bat round35CandidateGate --no-configuration-cache
```

该任务聚合全仓 `check`、数据库迁移、两个内置世界黄金路线、来源映射草稿、恶意 Behavior、不可达结局、沙箱隔离/重置、安装失败、秘密与题材审计、Android/Desktop Release、发行哈希、Desktop 五秒启动存活冒烟，以及 iOS Simulator Kotlin 编译。

门禁通过仍只表示 **Alpha candidate**。Android/iOS 真机资源与帧时间、macOS/Xcode 宿主、真实 Provider 可选冒烟和多轮真人试玩没有完成前，不标记为 Alpha ready。
