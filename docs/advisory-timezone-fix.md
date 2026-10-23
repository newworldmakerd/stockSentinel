# 模型建议时间展示修复（2026-10-22）

## 原因及证据

只读核查 `signal_event.id=21`：`signal_at=2026-10-22 09:35:00+08`，
`details.snapshot.bars` 最后一根 `bar_at=2026-10-22T01:35:00Z`。
两者是同一时刻；已存模型风险文案却直接显示“01:35”，遗漏时区。

## 改动与边界

- `SignalAdvisoryService` 在调用模型前，将快照内有时区的 ISO 日期时间递归转为 Asia/Shanghai，保留同一时刻及小数秒；日期、价格、无时区文本不变。
- 模型实际收到的转换后输入同时存入建议审计快照；原始规则、行情快照及历史建议不修改。
- `CompatibleAdvisoryClient` 明确要求北京时间展示，禁止将 UTC 钟面直接当北京时间。
- 保留原建议去重版本，避免历史已分析信号因本次修复重新计费。

## 验证与发布

回归覆盖 UTC→北京时间、跨午夜日期、小数秒、日期与价格不变及模型/审计输入一致。
测试命令：

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dtest=AdvisoryTest,CompatibleAdvisoryClientTest' test
```

未部署、未重启、未调用真实模型或企微；历史已发送文案不会自动更新。模型自然语言输出仍存在不遵循指令的可能，本次通过输入时间统一与明确提示降低该风险。
