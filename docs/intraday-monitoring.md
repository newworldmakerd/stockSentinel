# 盘中短线监控（用户追加范围）

日期：2026-10-20。用户明确要求新增盘中快速拉升/跳水、突破观察、止盈止损价位提醒。本模块独立于原有日线策略；代码、迁移、编译及本地模拟闭环已完成，真实分钟源联调与长期扫描未开启。

## 已确认的规则

用户确认每分钟扫描，五分钟涨跌幅±2%，突破此前20分钟高点且量能≥1.5倍，同类提醒冷却15分钟。规则不是经过回测的收益保证。

| 类型 | 精确定义 |
|---|---|
| RAPID_RISE | 六个连续分钟收盘点，当前价格相对五分钟前上涨≥rapidPct（默认示例2%） |
| RAPID_DROP | 同窗口下跌≥rapidPct |
| BREAKOUT_WATCH | 当前完整分钟收盘价严格高于此前20个完整分钟最高价，且当前分钟成交量≥此前20分钟均量×volumeMultiplier（默认示例1.5）；前20分钟均量必须>0 |
| TAKE_PROFIT | 当前分钟收盘价≥用户配置的takeProfit |
| STOP_LOSS | 当前分钟收盘价≤用户配置的stopLoss |

止盈止损价格为人民币元、不复权价，仅表示预设价位提醒，不读取/推断持仓或成本，不下单。价位为null时对应规则不启用。第一次观察就已到达价位也会提醒；持续在阈值外不反复发送，重新回到阈值另一侧后可重新触发，仍受15分钟冷却限制。新交易日可重新提醒。冷却期间的穿越直接抑制，不排队补发。

三类短线条件（拉升、跳水、突破）允许冷却到期后在新的分钟再次命中。冷却按股票+信号类型持久化，重启不清空；不同信号类型可在同一分钟同时提醒。改配置保留上次触发时间，不能绕过冷却。配置相同的重复PUT不增加revision；变化后递增，信号保存1.0.0-r{revision}和完整参数快照。

## 数据与计算

Python通过AKShare stock_zh_a_hist_min_em(period=1,adjust='')获取当日分钟行情；按上海时区标准化，成交量手×100变为股，成交额为元。1分钟只用NONE不复权。官方依据：[AKShare分钟数据](https://akshare.akfamily.xyz/data/stock/stock.html#id32)（该源近期分钟历史有限，不能作长期回测来源）。当前接入沪深SH/SZ股票；北交所BJ未完成分钟源适配验收，配置接口明确拒绝。

Python负责五分钟变动、前20分钟最高价和前20分钟均量计算，Java负责规则、校验、调度和写库。返回最近至多21个完整分钟；当前正在更新的分钟一律排除，保守带来约一个分钟间隔的数据滞后。不会把分钟数据写到market_daily_bar或调用日线MA/MACD策略。

- 仅使用当日数据；重复、乱序、未来、错误OHLC、负成交量、零价格等拒绝。
- 五/二十分钟窗口必须同一个连续交易时段且一分钟无缺口；午休和隔夜不连接。窗口不足时只禁用对应窗口规则，已配置价格规则仍可评估。
- Java再次检查身份、NONE/SHARE/CNY单位、指标与原始数据一致性；源响应观察时间不得落后本地30秒或超前5秒，末条完整分钟不得落后120秒；当前分钟成交量为0时跳过，避免停牌/无成交数据伪装新报价。
- 一个配置版本下，同一股票同一分钟只处理一次；更早分钟忽略，同分钟后续修订不会重新触发旧信号。下一个新分钟会使用供应商最新窗口，旧信号快照保留。
- 分钟表保存观测历史；信号details保存触发时完整窗口、指标、参数、命中条件和数据源，可与后续修订区分。

免费源的可达性、延迟与实际覆盖尚未真实验收，不能把每分钟轮询理解成逐笔实时行情。

## 调度和交易日

新增独立调度，每分钟第5秒尝试一次，Asia/Shanghai。早盘09:31至11:31、午盘13:01至15:01（包含末分钟内第5秒）。11:31和15:01用于补取刚结束的最后完整分钟，之后停止；午休、夜间不扫描。09:30/13:00开始的首分钟保守等待下一分钟，不使用未结束数据。

必须有明确的CN开市日历，缺失和休市跳过；复用TASK-06日历导入，无工作日推断。盘中分钟不需要人工确认日线收盘；原有日线收盘确认和15:15任务保持。

使用独立intradayTaskScheduler线程，原日线与通知调度保持原有单线程。PostgreSQL独立session advisory lock(76301,2)防止手动与自动盘中扫描并发，原日线使用(76301,1)。配置行锁、游标与冷却状态和信号同事务更新；事务回滚时分钟、信号、通知、游标和冷却均回滚。外部行情请求、模型和企微不持有数据库事务。

每次仅扫描同时满足“启用自选股+启用盘中配置”的沪深股票。单股异常不影响其他股票，job_execution/job_execution_item记录INTRADAY_SCAN及固定错误码。若一轮运行期间进入午休/盘后，未处理股票记SESSION_ENDED，不继续请求。进程退出后的遗留RUNNING任务在下次取得锁后标记失败。

每分钟是调度尝试频率，不是吞吐承诺：供应商串行限速、超时、股票数量及已命中后的同步模型调用可延长一轮；不会叠加启动多个扫描。建议先少量自选股实测延迟，模型关闭不影响规则通知。

## 企微与模型

新分钟信号和通知任务使用既有事务事件链路，信号提交后才允许模型分析；大模型不参与每分钟全量扫描，仅新信号才调用。模型/通知各自默认关闭，未开启时不会外呼。推送标题区分五类盘中提醒，明确分钟时间、不复权分钟收盘价、规则实际值和阈值，不冒充日线收盘信号。

通知首次发送时若模型仍未完成，会标记建议暂未生成或未启用；不等待也不追加补发，沿用TASK-05既有行为。消息重试、UNKNOWN隔离和令牌幂等保持不变。模型失败不删除规则信号。

## 本地API

当前只绑定127.0.0.1使用，无应用层认证。配置不会自动添加自选股，先调用既有自选股接口。

| 方法与路径 | 行为 |
|---|---|
| GET /api/v1/intraday/monitors | 当前盘中配置 |
| PUT /api/v1/intraday/monitors/{symbol} | 全量配置enabled、rapidPct、volumeMultiplier、cooldownMinutes、takeProfit、stopLoss；未知字段400；未加入自选股409，股票不存在404，BJ不支持400 |
| POST /api/v1/intraday/scan | 手动扫描当前交易日，返回jobId、status、processed、signals、failed、skipped、code；模块或行情关闭503 |
| GET /api/v1/jobs/{id} | 复用任务与逐股日志 |
| GET /api/v1/signals?type=RAPID_RISE | 原查询新增五类类型，返回signalAt；原日线类型仍有效 |

rapidPct单位为百分数（2表示2%，不是0.02），范围(0,30]；volumeMultiplier范围(0,100]；冷却分钟1至1440，初始示例15。上述小数最多4位。两种价位正数、最多4位小数；同时配置时stopLoss必须小于takeProfit。PUT必须传全部非价位参数，价位省略/null会清空对应规则。

## 配置示例与运行命令

默认INTRADAY_ENABLED=false、INTRADAY_SCHEDULE_ENABLED=false。当前库没有盘中监控配置与交易日历。本次不自动选择股票、不代填止盈止损价格、不运行真实源。

先按README准备Java/Python和数据库；以下配置接口本身不触发真实行情。先将目标股加入自选股，然后配置：

```powershell
Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/watchlist' -ContentType 'application/json' -Body '{"symbol":"600487","remark":"intraday monitor"}'
Invoke-RestMethod -Method Put 'http://127.0.0.1:8080/api/v1/intraday/monitors/600487' -ContentType 'application/json' -Body '{"enabled":true,"rapidPct":2,"volumeMultiplier":1.5,"cooldownMinutes":15,"takeProfit":null,"stopLoss":null}'
```

上例只启用拉升、跳水和突破；请把takeProfit/stopLoss改为自己的实际价格后完整PUT，才能启用两种价位提醒，系统不会猜测。交易日历导入见TASK-06。

经授权真实外呼后，在不同终端配置启动：

```powershell
# Python终端
$env:MARKET_PROVIDER_ENABLED = 'true'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir quant-service --host 127.0.0.1 --port 8000

# Java终端；先按README配置DB_URL、POSTGRES_USER及仅环境变量中的数据库密码
$env:MARKET_ENABLED = 'true'
$env:INTRADAY_ENABLED = 'true'
$env:INTRADAY_SCHEDULE_ENABLED = 'false' # 首次手动验收，确认后再设true并重启
$env:LLM_ENABLED = 'false'
$env:WECOM_ENABLED = 'false' # 真实群投递按TASK-05配置并授权后开启
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1

# 仅在开市窗口、日历已导入且真实行情调用获授权时执行
# Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/intraday/scan'
```

开启INTRADAY_SCHEDULE_ENABLED后才会每分钟尝试；不依赖JOBS_ENABLED或DAILY_SCAN_SCHEDULE_ENABLED。停止盘中功能将两个盘中开关设false后重启，保留原日线和历史数据。停用一只股票可PUT enabled=false或关闭其自选股状态。

## 数据库与回退

V6新增intraday_monitor、market_minute_bar、intraday_cursor、intraday_alert_state，signal_event新增可空signal_at。日线仍按股票/日期/策略代码/版本/类型唯一（signal_at IS NULL）；分钟新增分钟时刻唯一索引，实现同日多次触发。

原日线Mapper的ON CONFLICT补充IS NULL谓词以匹配部分唯一索引，业务去重语义保持，完整日线回归通过。V1至V5未修改；V6已应用开发库。V6后不要直接换回不含谓词的旧JAR，旧INSERT无法匹配部分唯一索引；结构回退必须另行设计新迁移，不能修改已执行脚本。

只读验收：原600487日线864条保留；公共库分钟/监控/信号/通知均0条，临时测试schema残留0，RAG表未修改。证据intraday-verification.json。

## 测试与证据

Java70项常规+48项原生PostgreSQL集成测试，共118项；Python49项通过，JAR打包成功。新增12项盘中集成测试使用独立随机schema和loopback行情模拟器，实际运行Python分钟标准化/指标、Java规则写库、模拟模型和真实JavaWebSocket客户端到本地企微模拟ACK。未调用真实行情、模型或企微。

覆盖阈值边界、分钟成交量单位、午休和窗口预热、未来/重复/异常价格、过期与无成交数据、信号重入、15分钟冷却、价位重新激活、配置版本变化、并发重复扫描、事务回滚、源故障与任务互斥，并回归原有日线策略。推送内容含分钟价格、实际值与阈值。

```powershell
$env:STOCKSENTINEL_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:STOCKSENTINEL_TEST_DB_USER = 'myrag_user'
$env:STOCKSENTINEL_TEST_DB_PASSWORD = Read-Host 'Test database password' -MaskInput
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=LocalDatabaseIT,IndicatorLocalDatabaseIT,StrategyLocalDatabaseIT,NotificationLocalDatabaseIT,DailyScanLocalDatabaseIT,IntradayLocalDatabaseIT' verify
Remove-Item Env:STOCKSENTINEL_TEST_DB_PASSWORD
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q
```

完整日志intraday-full-verification.log、intraday-python-test.log；通知文案和逐股跨时段保护追加验证见intraday-message-verification.log。

只读排查：

```sql
SELECT symbol,enabled,rapid_pct,volume_multiplier,cooldown_minutes,take_profit,stop_loss,revision FROM public.intraday_monitor;
SELECT symbol,signal_type,active,last_trigger_at FROM public.intraday_alert_state ORDER BY symbol,signal_type;
SELECT symbol,trade_date,signal_at,signal_type,reference_price,strategy_version FROM public.signal_event WHERE signal_at IS NOT NULL ORDER BY signal_at DESC LIMIT 50;
SELECT id,trade_date,status,error_message FROM public.job_execution WHERE job_name='INTRADAY_SCAN' ORDER BY id DESC LIMIT 20;
```

关键实现：quant-service/app/api/intraday.py；backend/src/main/java/com/stocksentinel/intraday/；backend/src/main/resources/db/migration/V6__intraday_monitoring.sql。

尚未完成：真实分钟源可用性/时效验收、BJ分钟适配、用户监控股与价位配置、真实盘中到群消息联调及长期运行。没有新增持仓管理、全市场选股、回测或自动下单。
