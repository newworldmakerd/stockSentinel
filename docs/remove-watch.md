# 全局删除关注

2026-10-21。用户确认：取消某只股票的全局公共关注并停止所有群公共监控，个人止盈止损保留。

## 群聊使用

在配置群@机器人，单独发送明确命令：

- 取消关注四川美丰
- 删除关注600487
- 把亨通光电取消关注

本条必须明确单只股票名称或六位代码。会影响所有群的公共关注及关注人归属，不是只取消本人的关注。群助手使用 `remove_stock_monitor(stock_query)`，不能由模型传入群、用户、SQL或URL。

问句、否定、批量、代他人和省略股票的消息均拒绝执行，例如“可以取消关注亨通光电吗？”、“不要删除关注亨通光电”、“取消关注它”、“取消关注所有股票”。每条消息仍只允许一次写操作，重复回调由原消息摘要去重。

## API

```http
DELETE /api/v1/intraday/subscriptions/600487
```

与新增订阅API配套，响应状态 `REMOVED` 或 `ALREADY_REMOVED`，包含名称、代码、移除关注人数、取消待发通知数。无目录股票404，无效代码400。此为本机运营入口，不允许暴露给未受信任网络。

不要为验收对实际关注股执行上述DELETE；测试使用独立临时schema。

## 事务与影响

- 使用原有股票级事务锁串行化群订阅的新增与移除。
- 锁住盘中配置，停用公共监控并增加配置修订；保留原参数，以便重新关注时恢复。
- 删除stock_watchlist中的目标股票和全群group_watch_owner关系，历史行情、指标、信号、规则明细、通知记录保留。
- 取消该股SWING_TREND_V1、INTRADAY_V1、INTRADAY_MA60_V1的PENDING/RETRY通知，标记CANCELLED/WATCH_REMOVED；SUCCESS/UNKNOWN/SENDING不改写。
- 日线定时任务写信号前新增共享锁复核。取消提交后，已开始任务不能再写该股的公共日线信号；原手动策略评估入口不要求关注，行为保持。
- 盘中写入沿用既有监控修订与关注状态锁校验，防止旧扫描快照写新信号。
- 所有用户的个人止盈止损配置和队列保留；若所在群仍在允许集合，仍按原规则运行。
- 全市场选股、缩量回踩观察池及其独立确认不依赖关注名单；取消关注不会把该股票加入全市场黑名单，之后仍可能出现在全市场候选推荐中。
- 已发送或正在发送的通知无法撤回；重新关注不恢复旧关注人关系，不重发已取消的历史通知。

## 修改点

CommandMapper、MonitorSubscriptionService/Controller提供事务性移除。RemoveWatchIntent验证完整明确意图；GroupAssistantTools和DeepSeekGroupAssistantClient接入受控工具。SignalWriter.saveWatched与日线调度路径增加删除后的提交保护，不修改技术指标或策略条件。无数据库结构变更，Flyway仍为V15。

## 验证

普通规则与意图测试、本机模拟模型/机器人、独立PostgreSQL schema验证：全局多用户归属移除、个人设置与历史保留、待发消息取消、UNKNOWN/SENDING保留、重复回调去重、重复HTTP删除幂等、回滚、并发新增/删除一致性、已删除股票阻止旧日线任务提交、否定/问句/伪造身份/批量拒绝。

只修改实现，不删除当前真实关注股票，也不发送真实群测试消息。部署加载功能时仅重启本项目Java，Python保持运行。


最终验证：129项普通测试、62项本机数据库测试通过，编译打包成功。记录 `.runtime/remove-watch-final-checked.log`、`.runtime/remove-watch-package.log`。测试命令：

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=GroupAssistantLocalDatabaseIT,DailyScanLocalDatabaseIT,IntradayLocalDatabaseIT' '-Dspring.test.context.cache.maxSize=2' '-Dspring.datasource.hikari.maximum-pool-size=3' test test-compile failsafe:integration-test failsafe:verify
```

测试库通过STOCKSENTINEL_TEST_DB_URL/USER/PASSWORD环境变量注入，测试仅处理独立临时schema。

2026-10-21 14:46已加载部署；运行目录 `.runtime/20261021-144606-360088`，backend PID 77344，Python原进程保留。Java/Python健康UP、机器人AUTHENTICATED；真实关注表部署前后均9条，未执行任何真实股票移除或真实群测试消息。全链路删除与回滚已通过本地HTTP和模拟模型、独立测试数据库验证。
