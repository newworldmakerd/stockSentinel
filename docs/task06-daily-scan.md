# TASK-06 收盘扫描与任务日志

日期：2026-10-20（Asia/Shanghai）。任务代码、迁移、编译和模拟端到端验收已完成；真实收盘扫描未执行。默认不启动真实行情扫描。

## 已确认的当日行为变化

用户确认采用人工收盘确认入口。原来的历史日期逻辑保留，仅新增下列当日路径：

1. 本地市场时间15:00之前不得确认当日收盘；交易日必须已有明确的CN市场开市日历记录。
2. 人工确认指定tradeDate与行情source。确认记录保存时间戳，不以机器时钟到达15:15自动推断行情源最终更新。
3. Python行情Provider仍将当日日线标记is_final=false。Java核对日期、已开市日历和精确数据源确认后，才将当日日线视为人工确认的最终数据写入。
4. Java计算指标和策略时再次校验确认条件、数据源及目标日线；发送给Python计算端的current_day_confirmed默认false，仅通过上述校验才置true。Python仍拒绝未完成、未来日期及未经显式确认的当日数据。
5. 信号写入前再次核对确认来源，并把确认日期、source、confirmedAt写进signal_event.details.closeConfirmation。

该确认是用户对数据源的人工声明，不是系统已取得供应商最终更新证明。不支持撤销或修改确认；误确认的后续处置需另行明确需求。普通历史日期接口、指标公式和TASK-04策略条件不变。

## 交易日历

使用market_calendar中明确的CN市场日历记录判断，不推断工作日、不内置节假日。缺失记录为CALENDAR_MISSING，休市为MARKET_CLOSED，均记录跳过并停止采集。

日历通过本地API导入，必须由使用者依据交易所公开日历核对CN市场整体的开闭市状态并填写source。系统不自动下载或验证交易所公告，也不把单个交易所的日历默认为全部A股日历。当前库日历为空，因此直接执行扫描将安全跳过，需先准备真实日历。

每次最多1000条，日期范围2000年至当前日期后两年；同批不得重复日期。已存在日期相同数据可重复提交，不同开市状态或来源返回409，不覆盖原记录。该限制也保护已用于收盘确认的日历。

## 扫描流程

- 15:15、Asia/Shanghai cron；每日触发后读取明确日历，休市或未知日期只记日志。
- 仅读取enabled=true的自选股，不自动增加股票或执行全市场选股。空列表和策略禁用分别记录跳过。
- 读取SWING_TREND_V1当前参数，获取策略复权序列与NONE不复权参考序列。通常刷新最近730个自然日；已有更早策略历史时从已存最早日期刷新，避免只刷新复权后缀而保留旧复权前缀。超过既有3660天导入限制时失败，不静默截断。
- 目标日必须在本轮真正写入，比较fetched_at与请求开始时间；供应商未返回目标日、目标日未获确认或只能读到旧缓存时跳过。
- 依次计算版本化指标、评估策略、原子保存信号及通知任务。新信号提交后按既有模块调用模型；通知由TASK-05持久worker异步投递。任务SUCCESS表示处理完成，不代表企微最终送达，需另查通知状态。
- 单股失败记录MARKET/INDICATORS/STRATEGY阶段及固定错误码后继续其他股票；不保存原始异常、响应或凭据。指标不足等异常记录对应阶段FAILED，不伪装成NO_SIGNAL；策略门禁跳过则记录SKIPPED及原因。
- 相同交易日允许手动补跑。信号及通知沿用已有唯一键去重，重复执行不重复调用模型或投递同一信号。行情获取失败不自动整批重试；排查后手动补跑，避免无界外部调用。

## 开关及互斥

默认JOBS_ENABLED=false、DAILY_SCAN_SCHEDULE_ENABLED=false。前者控制手动及计划扫描，后者控制15:15入口；真实运行需同时开启MARKET_ENABLED和Python的MARKET_PROVIDER_ENABLED。模型与通知各自独立控制。

扫描为同步本地API，股票较多时会运行较久。使用独立数据库连接持有PostgreSQL session advisory lock (76301,1)，保证同库一次仅执行一个扫描；外部网络调用不持有数据库事务。锁被占用时记录JOB_ALREADY_RUNNING，不执行采集；正常结束释放，进程退出后数据库释放连接锁。下次获取锁时将遗留RUNNING任务标记PREVIOUS_PROCESS_INTERRUPTED。不提供后台分布式任务队列、自动补执行错过的cron或长连接主节点选举。

## 本地API

接口按README仅绑定127.0.0.1，无应用层认证，人工确认和日历导入由本机使用者负责。

| 方法与路径 | 输入、结果 |
|---|---|
| POST /api/v1/calendar/import | 数组：tradeDate、isOpen、source；200/accepted |
| GET /api/v1/calendar?from=YYYY-MM-DD&to=YYYY-MM-DD | 最长1000天，明确日历列表 |
| POST /api/v1/market/close-confirmations | tradeDate、source；确认后200/CONFIRMED；过早、未来或缺少开市日历400 |
| GET /api/v1/market/close-confirmations?tradeDate=YYYY-MM-DD | 当日已保存的确认与时间戳 |
| POST /api/v1/jobs/daily-scan | tradeDate；200包含jobId/status/processed/success/failed/skipped/code；任务关闭503，未来日期400 |
| GET /api/v1/jobs | limit默认50、最大200；offset默认0 |
| GET /api/v1/jobs/{id} | job和items，记录股票、阶段、状态与固定错误码；不存在404 |

任务状态：RUNNING、SUCCESS、PARTIAL、FAILED、SKIPPED。每股成功项区分GENERATED、ALREADY_EXISTS、NO_SIGNAL。列表返回数据库字段名（例如trade_date、processed_count），扫描响应使用camelCase统计字段。

## 操作命令

以下为配置和使用示例，本次没有执行真实扫描。真实行情、模型和企微调用须先获授权；开启通知可能投递原有待发送任务。密钥仅使用环境变量，运行命令不包含实际密钥。Python与Java应在两个终端配置并运行。

```powershell
# Python终端（真实行情请求前需获授权）
$env:MARKET_PROVIDER_ENABLED = 'true'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir quant-service --host 127.0.0.1 --port 8000

# Java终端；先按README设置数据库URL、用户名及环境变量密码
$env:MARKET_ENABLED = 'true'
$env:JOBS_ENABLED = 'true'
$env:DAILY_SCAN_SCHEDULE_ENABLED = 'false' # 先手动验收，确认后再开启15:15调度
$env:LLM_ENABLED = 'false'
$env:WECOM_ENABLED = 'false'
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1
```

按实际核对的日历构造请求，不能直接把某个工作日当开市日。以下日期与isOpen必须自行核对后填写：

```powershell
# $calendarBody = '[{"tradeDate":"YYYY-MM-DD","isOpen":true,"source":"已核对的交易所日历来源标识"}]'
# Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/calendar/import' -ContentType 'application/json' -Body $calendarBody
# 源标识只允许字母、数字、下划线、点、冒号或连字符，最长40字符
# 当日实际收盘且人工核对源数据最终更新后：
# $confirmation = '{"tradeDate":"YYYY-MM-DD","source":"AKSHARE_EASTMONEY"}'
# Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/market/close-confirmations' -ContentType 'application/json' -Body $confirmation
# $scan = '{"tradeDate":"YYYY-MM-DD"}'
# Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/jobs/daily-scan' -ContentType 'application/json' -Body $scan
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/jobs?limit=20'
```

手动验证通过后，在15:15之前完成当日源数据确认，并设置DAILY_SCAN_SCHEDULE_ENABLED=true后重启Java；如果15:15时仍未确认，只记录跳过，可确认后手动补跑。完整企业微信通知需遵循TASK-05的Bot、chatid和单实例连接配置。此前单条真实企微联调已获成功ACK，不代表本任务真实行情到通知闭环已验收。

## 迁移与测试

V5增加收盘确认表、任务明细表，以及job_execution的trade_date/trigger_type/skipped_count；V1至V4未修改。myrag.public已应用V5；600487 QFQ/NONE各432条保留，日历/确认/任务/明细/指标/信号/建议/通知均0条。真实扫描尚未执行，未修改RAG表。只读证据见task06-verification.json。

Java64项常规测试+36项本机集成测试，共100项通过，Python39项通过；JAR打包成功。新增10项TASK-06集成测试在随机独立schema中实际提交后清理：固定行情通过真实本地HTTP进入Java，真实Python公式计算指标，随后落信号、调用模拟模型、通过真实Java WebSocket客户端向本地企微模拟器发送并确认ACK。覆盖重复执行、无信号、缺日历/休市、禁用自选股、未确认当日、来源不匹配、缺目标日、单股失败、数据库互斥、遗留任务恢复和API校验。测试未调用真实行情、模型或企微。

```powershell
$env:STOCKSENTINEL_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:STOCKSENTINEL_TEST_DB_USER = 'myrag_user'
$env:STOCKSENTINEL_TEST_DB_PASSWORD = Read-Host 'Test database password' -MaskInput
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=LocalDatabaseIT,IndicatorLocalDatabaseIT,StrategyLocalDatabaseIT,NotificationLocalDatabaseIT,DailyScanLocalDatabaseIT' verify
Remove-Item Env:STOCKSENTINEL_TEST_DB_PASSWORD
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q
```

日志为task06-full-verification.log、task06-python-test.log；业务时区测试夹具修正后追加验证见task06-timezone-regression.log。pytest有缓存目录权限与库弃用提示，测试仍全部通过，未扩展修改本机权限或依赖。

只读排查SQL：

```sql
SELECT version,success FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT trade_date,exchange,is_open,source FROM public.market_calendar ORDER BY trade_date DESC LIMIT 30;
SELECT trade_date,source,confirmed_at FROM public.market_close_confirmation ORDER BY trade_date DESC LIMIT 30;
SELECT id,trade_date,trigger_type,status,processed_count,success_count,failed_count,skipped_count,error_message
FROM public.job_execution ORDER BY id DESC LIMIT 20;
SELECT job_id,symbol,stage,status,code FROM public.job_execution_item ORDER BY id DESC LIMIT 100;
```

实现定位：

- backend/src/main/java/com/stocksentinel/jobs/TradingDatePolicy.java:11：日历与源确认政策。
- backend/src/main/java/com/stocksentinel/jobs/DailyScanService.java:34：互斥和逐股任务流程。
- backend/src/main/java/com/stocksentinel/jobs/DailyScanScheduler.java:15：北京时间15:15入口。
- backend/src/main/java/com/stocksentinel/jobs/JobController.java:19：本地API。
- backend/src/main/java/com/stocksentinel/market/MarketCollectionService.java：当日人工确认入库分支。
- backend/src/main/java/com/stocksentinel/indicator/IndicatorService.java：Java日期和来源门禁。
- quant-service/app/indicators/calculate.py：默认关闭的当日确认契约。
- backend/src/main/java/com/stocksentinel/signal/SignalWriter.java：最终校验和确认快照。
- backend/src/main/resources/db/migration/V5__daily_scan_jobs.sql:1：新增迁移。
