# 盘中扫描异常提醒

## 1. 背景与目标

2026-10-21补齐主动企微异常提醒，基于已完成扫描的持久记录判断故障；不改变股票规则或通知信号。默认连续3轮同类故障报警，同类15分钟冷却，完整成功扫描后恢复通知一次。发送到当前配置的企微群，不调用大模型。

## 2. 现状与影响点

上次可靠性修复只完成了错误码和日志，本次才加入主动通知。

- `backend/src/main/java/com/stocksentinel/intraday/IntradayScanService.java`：任务结果写入后调用观察器；观察器失败只记录脱敏日志，不改写已完成的扫描结果。
- `backend/src/main/java/com/stocksentinel/monitoring/ScanFaultService.java`：读取本轮结果，按目标群与故障类别串行更新状态，原子生成通知队列。
- `backend/src/main/java/com/stocksentinel/monitoring/ScanFaultMapper.java`：状态锁、队列防重、并发领取和租约处理。
- `backend/src/main/java/com/stocksentinel/monitoring/ScanFaultDelivery.java`：独立调度线程领取队列，复用原有企微智能机器人客户端。
- `backend/src/main/resources/db/migration/V14__scan_fault_alerts.sql`：仅新增 `scan_fault_state`、`scan_fault_event` 和相关索引；不修改历史迁移或业务表。
- `MonitoringStatusController`：原 `/api/v1/monitoring/status` 增加 `scanAlertsEnabled`、`scanAlertsWorkerEnabled` 两个布尔字段，其余契约保留。

范围是**盘中扫描**，尚未扩展到每日收盘扫描、全市场历史筛选、进程退出或数据库整体不可用的独立外部监控。数据库不可用时，本进程可能无法记录或发送持久告警；机器人断连时也不能承诺消息及时送达。

## 3. 实现与运行规则

### 故障判断

| 类别 | 依据 |
|---|---|
| 分钟行情获取 | MINUTE_* 获取、HTTP、连接、超时和响应解码错误 |
| 分钟行情有效性 | STALE_MINUTES、NO_MINUTE_DATA、INVALID_MINUTE_RESPONSE、INVALID_MINUTE_BARS、INVALID_MINUTE_METRICS、CROSS_SESSION_METRICS |
| 个人价位处理 | PERSONAL_PRICE_* 失败 |
| 盘中信号处理或写库 | INTRADAY_WRITE_* 失败 |
| 回踩转强确认 | PULLBACK_CONFIRM_* 失败 |
| 扫描任务 | 整轮失败、其他未分类失败，或交易时段的CALENDAR_MISSING |

每一类别在一轮中最多计数一次，多只股票同轮失败不会直接凑满3轮。相同处理阶段的不同错误码合并计数，但消息保留本轮具体代码和最多5只股票的中文名及代码。

只有新完成的扫描触发观察，不追发部署前的历史失败。状态和冷却保存在数据库，按机器人与目标群的摘要隔离；重复观察同一任务不重复计数或入队。

正常休市、午休、没有当前成交、没有监控股票和扫描重叠等跳过不报警，也不能作为恢复依据。正常跳过对连续计数中性；跨上午/下午、跨交易日或超过5分钟没有可计数扫描时，重新累计失败轮次。缺失交易日历属于异常，不按正常休市处理。

当某类别在部分失败的轮次中没有再次出现时，连续计数归零；已有事件仍等待完整成功扫描确认恢复，避免其他环节仍失败时误报恢复。

### 通知、冷却与恢复

- 第3轮同类异常生成一条故障提醒；持续异常每15分钟最多追加一条同类提醒。新故障周期也遵守同类冷却。
- 故障消息包含阶段、轮次、任务ID、影响股票和安全错误码，明确缺消息不代表无风险。
- 后续一轮 `SUCCESS` 且实际处理股票数大于零，才生成该故障周期的一次恢复事件。
- 故障若在发送前恢复，取消尚未发送或待重试的故障消息。恢复消息仅在同周期有故障投递 `SUCCESS` 或 `UNKNOWN` 后才能领取，避免群里只收到一条没有上下文的恢复提示。
- 独立队列最多3次确定可重试投递，间隔30秒；未知回执或发送抛异常标为 `UNKNOWN`，不自动重试该条消息。
- 领取租约120秒，过期发送中事件变为UNKNOWN；未发送事件15分钟过期。领取令牌限制旧worker回写，并发不会重复领取同一条。

注意：独立线程和队列避免告警入队/重试阻塞股票扫描；发送仍复用同一个企微客户端，和其他消息共享底层连接。

### 配置

```text
SCAN_ALERTS_ENABLED=false
SCAN_ALERTS_WORKER_ENABLED=true
SCAN_ALERTS_FAILURE_THRESHOLD=3
SCAN_ALERTS_COOLDOWN_MINUTES=15
```

缺省关闭；本机 `scripts/start_live_local.py` 已显式启用。所有真实凭据仍仅从环境读取。`SCAN_ALERTS_ENABLED=false` 同时关闭新观察和队列投递；如需只暂停投递可设置worker开关为false。

只读检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/monitoring/status
```

```sql
SELECT failure_kind, streak, active, last_job_id, last_scan_at, last_alert_at
FROM scan_fault_state ORDER BY failure_kind;

SELECT id, failure_kind, event_type, job_id, status, attempts, created_at, last_error
FROM scan_fault_event ORDER BY id DESC LIMIT 30;
```

群消息示例（演示文字，不是已发送消息）：

```text
【StockSentinel】扫描异常提醒
类别：分钟行情获取
连续异常：3轮；任务 #123
本轮受影响股票：1只（最多展示5只）
亨通光电 600487：MINUTE_HTTP_TIMEOUT
受影响数据或处理结果可能缺失，请勿将未收到股票信号理解为无风险。
```

## 4. 必须保留的拦截与校验

- 不改变行情校验、交易时段、手工收盘确认、策略阈值、冷却、信号与通知幂等。
- 不生成股票信号，不调用模型，不自动关注或下单。
- 告警正文只使用固定类别、安全错误码及清洗后的股票名称，不包含凭据、群原始消息或上游异常正文。
- 不改变既有单目标群配置，不自动迁移个人提醒到新群；其他目标群摘要的待发告警不会被当前目标群worker领取。

## 5. 待确认或后续范围

本次按已接受的3轮/15分钟/恢复一次口径实现，无需另行选择频率。后续若要覆盖日线或全市场筛选，应分别定义它们的运行周期及故障阈值，不能直接把每日任务套用每分钟的连续3轮规则。

## 6. 验证与回滚

Java常规测试109项通过；盘中回归27项与新增告警集成15项，共42项隔离PostgreSQL集成测试通过。新增测试验证：阈值、同轮多股聚合、持久冷却、防重、一次恢复、正常跳过、行情过期、午休边界、缺日历、发送前恢复取消、并发锁与领取、UNKNOWN隔离、重试上限及TTL。

本机模拟WebSocket测试走实际智能机器人客户端鉴权、消息编码和ACK处理，确认故障与恢复各投递一次。所有验收只使用本机模拟机器人，没有发送真实群测试消息。Python本次没有修改，不重复执行其回归。

测试证据：`.runtime/scan-alert-unit-verified.log`、`.runtime/scan-alert-native-verified.log`。数据库集成测试使用随机独立schema，结束后清理，未写public业务样本。

本机已部署到 `.runtime/20261021-125822-351230/`。Java打包成功，public的Flyway V14执行成功；状态接口确认 `scanAlertsEnabled=true`、`scanAlertsWorkerEnabled=true`、企微 `AUTHENTICATED`，Postman只读断言通过。部署检查时告警队列0条，没有伪造故障或发送群验收消息；真实故障满足阈值后才会发送。当前群目标仍由既有环境配置决定。

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test
# 当前进程通过环境变量设置隔离测试连接；密码只从已有环境读取
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=ScanFaultLocalDatabaseIT,IntradayLocalDatabaseIT' test-compile failsafe:integration-test failsafe:verify
```

回滚优先关闭 `SCAN_ALERTS_ENABLED` 并重启；保留新增表和已记录事件，不删除迁移、不回滚业务信号。不应通过修改已执行V14来撤销功能。

本次沿用 `postman:api-engineer`、`postman:api-simulation`、`postman:api-testing` 的契约和故障验证方式，不发布共享资源。
