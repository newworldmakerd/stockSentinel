# 多群聊支持

2026-10-21。沿用全局共享关注名单与公共监控参数，不迁移或删除已有数据。

## 行为与影响

- 同一个机器人、同一条长连接接收多个已配置群的消息；机器人须已加入每个目标群。
- 对话回复只回到来源群；未配置群的回调不会进入模型、工具或回复流程。
- 公开规则信号、日报、10分钟回踩候选和扫描异常/恢复分别向全部配置群入队。
- 股票规则、行情和模型分析共用；通知去重、ACK、重试和UNKNOWN状态按目标群隔离。
- 个人止盈止损仍按群摘要+真实回调userid+股票隔离，只投递到设置时所在群。同一人跨群设置互不覆盖。
- 关注名单共享；关注人、自设昵称和短时对话上下文保留原有群隔离。公开名单可见全局股票，关注人显示本群关系。
- 各通知工作器轮换起始群，避免前一个群积压导致后面的群持续饿死。最多20个唯一目标群。

## 配置与运行

保留 `WECOM_CHAT_ID`；新增 `WECOM_CHAT_IDS`，英文逗号分隔。两者合并、去首尾空白、去重；旧单群配置继续有效。可只设置多群变量。示例均为占位符：

```powershell
[Environment]::SetEnvironmentVariable('WECOM_CHAT_IDS', '群A的chatid,群B的chatid', 'User')
```

变量变更后需要重启Java进程；无需重启Python。不要为每个群分别启动一个相同Bot ID的Java实例，否则长连接会互相踢下线。启动脚本从环境读取变量，不保存密钥。

`GET /api/v1/monitoring/status` 新增 `configuredGroupCount` 与 `watchlistScope=SHARED`，不返回群ID或密钥。日报原有HTTP查询/人工生成返回主群记录；生成动作会为所有配置群补齐记录。

移除配置群后，不再消费该群待投递记录，也不再扫描该群专属个人价位。重新加入仍沿用原有幂等记录；不会自动重发SUCCESS或UNKNOWN。新增群不自动回放历史信号，后续产生的公开信号才向其入队；日报人工生成可补齐当日报告。

## 修改点

- NotificationSettings：配置合并、群白名单、稳定目标摘要与轮换。
- GroupMonitorCommands / GroupAssistantTools：来源群校验及原群回复。
- NotificationService / DailyReportService / PullbackDigestService：按群持久化和投递。
- PersonalPriceService / PersonalPriceDelivery：允许群集合、行情去重复用、按群消费。
- ScanFaultService / ScanFaultDelivery：故障状态和恢复提醒按群独立处理。
- 已有队列表均有目标群或摘要键，无需新增数据库迁移，Flyway仍为V15。

## 验证

使用本机PostgreSQL独立临时schema及本地WebSocket模拟机器人，不调用真实行情、模型或企微。覆盖来源群回复、跨群消息去重、公开通知双群幂等、UNKNOWN不重发且不阻塞其他群、个人设置隔离和单连接双目标ACK。测试命令与最终结果见本次交付说明。


最终验证：119项普通测试、89项相关本机数据库测试通过。日志 `.runtime/multi-chat-package.log` 与 `.runtime/multi-chat-focused-native.log`。数据库测试命令：

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=GroupCommandsLocalDatabaseIT,GroupAssistantLocalDatabaseIT,NotificationLocalDatabaseIT,ReportsLocalDatabaseIT,ScanFaultLocalDatabaseIT,IntradayLocalDatabaseIT' '-Dspring.test.context.cache.maxSize=2' '-Dspring.datasource.hikari.maximum-pool-size=3' '-Dspring.datasource.hikari.minimum-idle=0' test-compile failsafe:integration-test failsafe:verify
```

测试数据库连接通过 `STOCKSENTINEL_TEST_DB_URL/USER/PASSWORD` 环境变量注入，自动建立和清理独立临时schema。未修改public业务数据。

全量历史集成入口另有已存在限制：DatabaseIT要求Docker；LocalDatabaseIT固定断言13个迁移但当前已有V15；全量上下文缓存可占满数据库连接。此次针对受影响六组本机测试限制连接池与上下文缓存后89项全部通过，未扩大修改这些旧测试断言。

运行验收：2026-10-21 13:59 Java已重启加载新版本，机器人AUTHENTICATED，Java/Python健康。运行目录 `.runtime/20261021-135853-169365`；backend PID 76120，Python原进程保留。配置目标群数为1，新增实际群ID后需再次重启Java；尚未进行真实双群发送验收。
