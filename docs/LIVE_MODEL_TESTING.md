# 真实模型冒烟测试

`shared:provider-openai:mimoLiveTest` 使用内置 `MiMo Token Plan CN` 订阅源和固定模型
`mimo-v2.5`，分别驱动 `war-survival` 与 `station-ai` 完成 10 轮玩家交互。每轮必须得到主持人的
正常终态；第 10 轮还必须通过 Tool Gateway 提交一次权威行动。测试最终校验游戏仍处于可游玩状态、
EventLog 序列不倒退、20 条 GM Turn 均完整保留且没有损坏、失败或中断记录。

该测试会访问真实网络并消耗订阅额度，因此不包含在常规 `check` 中，只在显式运行下列任务时执行：

```powershell
./gradlew.bat :shared:provider-openai:mimoLiveTest --no-configuration-cache
```

## 本地凭据

凭据只能放在 Git 忽略的 `.worldloom-live/` 目录中，测试按以下顺序读取：

1. `.worldloom-live/mimo.env` 中的 `WORLDLOOM_MIMO_TOKEN_PLAN_API_KEY`；
2. `.worldloom-live/credentials/` 中由 Windows DPAPI 加密的 Desktop 凭据文件。

环境文件格式如下，不要提交真实值：

```dotenv
WORLDLOOM_MIMO_TOKEN_PLAN_API_KEY=your-local-token-plan-key
```

测试和 Provider 错误信息不得输出密钥，HTTP 请求正文也不包含密钥。常规提交前仍应运行仓库的秘密审计。
