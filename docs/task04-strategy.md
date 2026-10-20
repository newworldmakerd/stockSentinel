# TASK-04 自选股与 Java 策略引擎

日期：2026-10-20。当前任务已完成；不包含 TASK-05 通知和 TASK-06 交易日调度。

## 已确认的规则

默认策略 `SWING_TREND_V1`，版本 `1.0.0`。每组条件全部满足才产生对应信号；三组独立评估，可同时命中，不增加优先级或评分，score 保持空值。

| 信号 | 条件 |
|---|---|
| BUY_WATCH | MACD 金叉，收盘价严格高于 MA20，MA20 严格上升，当日成交量达到前五日均量的 1.2 倍 |
| SELL_WATCH | MACD 死叉且收盘价严格低于 MA20 |
| RISK_ALERT | 前一日收盘价大于等于前一日 MA60，当日收盘价严格低于当日 MA60 |

金叉：前一日 DIF−DEA≤0、当日>0；死叉相反（前一日≥0、当日<0）。持续低于 MA60 不重复触发风险预警。用户确认本阶段不实现未定义的趋势破坏组合；持仓止损留待持仓模块。
量能使用原始数值比较 `当日量×5 >= 前五日量合计×倍数`，避免已舍入量能比误触边界；前五日总量为零时买入量能条件失败，不阻断卖出与风险规则。

## 数据与事务边界

- 默认使用 QFQ、`ma-macd-vol-v1`，价格与均线始终同一复权序列。至少 121 条历史以支持两个连续指标结果。
- 当前日线必须对应请求交易日，两个指标必须对齐最近两个日线日期；拒绝混合来源、无效历史、缺失或过期指标。指标计算时间不得早于历史最新采集时间。
- 同日同源 NONE 收盘价用作信号参考价；缺失时跳过，不以复权价冒充可交易参考价。
- 信号和命中明细同事务写入；details 保存参数、历史状态、当前与前一日日线/指标、原始参考价和实际命中规则。写入前复核配置与数据时间戳，变化返回 409，调用方重新准备数据后再评估。
- 唯一键为股票、交易日、策略代码、策略版本、信号类型。重复和并发调用不会覆盖历史快照、重复写明细或重复触发模型。
- 仅新信号事务提交后触发模型；监听器使用 AFTER_COMMIT 与 NOT_SUPPORTED，调用模型时不持有数据库事务。默认 LLM_ENABLED=false。模型失败保留已提交规则信号，模型结果独立保存。
- 提交后监听器为进程内同步执行，未实现持久任务队列、自动重试或崩溃恢复；提交后进程退出可能需要手动分析已有信号。数据复核不等于跨服务的分布式事务。
- 当前手动评估接受已同步股票，不要求加入自选股。定时扫描启用自选股、交易日历、收盘确认和任务协调属于 TASK-06；本阶段不会自动采集、重算指标或发送企微。

## 本地接口

当前本地接口无应用层认证，按 README 绑定 127.0.0.1 使用。请求和响应为 JSON。

| 方法与路径 | 输入及行为 |
|---|---|
| GET /api/v1/stocks | query 默认空，代码前缀/名称匹配；limit 默认50、最大200；offset 默认0 |
| GET /api/v1/watchlist | 查询自选股 |
| POST /api/v1/watchlist | symbol 为六位代码，remark 最长500字符；股票须已存在；重复添加保留原状态及备注；成功200 |
| PUT /api/v1/watchlist/{symbol} | enabled 必填，remark 可空；完整更新启用状态和备注；成功200 |
| DELETE /api/v1/watchlist/{symbol} | 幂等删除自选记录，204；保留股票、日线及历史信号 |
| GET /api/v1/strategies | 当前策略及 JSON 参数 |
| PUT /api/v1/strategies/{code} | version、enabled、parameters；本阶段仅支持 SWING_TREND_V1；成功200 |
| GET /api/v1/strategies/{code}/revisions | 历史参数修订 |
| POST /api/v1/strategies/{code}/evaluate | symbol、tradeDate（ISO日期）；只读取已有数据，不调用行情服务 |
| GET /api/v1/signals | 可选 symbol/from/to/type；limit 默认100、最大200；offset 默认0；返回数组 |
| GET /api/v1/signals/{id} | 返回 signal（details 为对象）及 ruleHits；不存在404 |

评估响应包含 status、reason、strategyCode、strategyVersion、evaluations、signals。status 为 GENERATED、ALREADY_EXISTS、NO_SIGNAL 或 SKIPPED；数据不足返回200与 SKIPPED/INSUFFICIENT_HISTORY，区别于规则未命中。非法参数400，未知股票/策略404，参数版本冲突或写入前数据变化409。模型失败不撤销规则评估结果。

默认参数：

```json
{"volumeMultiplier":1.2,"adjustment":"QFQ","calculationVersion":"ma-macd-vol-v1","riskMode":"CROSS_BELOW_MA60","sellMode":"DEAD_CROSS_BELOW_MA20"}
```

修改参数必须给出新版本；同版本不同参数返回409。已登记历史版本参数不可改变，可用原参数重新启用历史版本。仅改变 enabled 不要求新版本。拒绝未知字段、未知计算版本及未实现规则模式。

启动 Java/Python 见 README（无需 Docker）。Java 启动后可手动执行以下本地请求；若启用模型，生成新信号会触发真实模型调用，应先确认授权：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/strategies'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/watchlist' -Method Post -ContentType 'application/json' -Body '{"symbol":"600487","remark":"daily observation"}'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/strategies/SWING_TREND_V1/evaluate' -Method Post -ContentType 'application/json' -Body '{"symbol":"600487","tradeDate":"2026-10-19"}'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/signals?symbol=600487&limit=20'
```

## 迁移、验证及实际限制

V3 新增 strategy_revision 并幂等初始化默认策略；保留既有策略配置。V1/V2 未修改。myrag.public 已成功应用 V3：5575 条股票、1 条策略、1 条修订，自选股/日线/指标/信号/命中/模型建议均为0；临时测试 schema 残留为0。只读结果见 task04-verification.json，未修改 RAG 表。

Java 42 项常规测试与14项本机 PostgreSQL 集成测试通过，共56项；Python38项回归通过。构建成功，证据 task04-full-verification.log、task04-python-test.log。
策略集成测试在随机独立 schema 中实际提交，覆盖接口、并发幂等、事务回滚、过期指标、NONE参考价缺失、版本不可变和模型失败。模型使用固定模拟客户端，确认提交后调用且调用期间无数据库事务；测试结束清理自己的 schema。测试角色需要 CREATE SCHEMA 权限。其他原生集成测试回滚样例数据。Mockito 显式 javaagent 仅用于 Maven 测试，避免 JDK21 动态自附加失败。

```powershell
$env:STOCKSENTINEL_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:STOCKSENTINEL_TEST_DB_USER = 'myrag_user'
# PowerShell 7；密码仅存在环境变量
$env:STOCKSENTINEL_TEST_DB_PASSWORD = Read-Host 'Test database password' -MaskInput
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=LocalDatabaseIT,IndicatorLocalDatabaseIT,StrategyLocalDatabaseIT' verify
Remove-Item Env:STOCKSENTINEL_TEST_DB_PASSWORD
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q
```

只读核对 SQL：

```sql
SELECT version, type, success FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT code, version, enabled, parameters FROM public.strategy_definition;
SELECT strategy_code, version, parameters FROM public.strategy_revision;
SELECT id, symbol, trade_date, signal_type, strategy_version FROM public.signal_event ORDER BY id DESC LIMIT 20;
```

真实日线仍因 TASK-02 上游连接中断而未入库，现有600487评估会跳过；本次没有调用真实行情、模型或企微，不能视为真实交易日闭环验收。TASK-05通知、TASK-06调度与真实模型兼容性验证未完成。没有自动下单。

## 实现定位

- 自选股：backend/src/main/java/com/stocksentinel/stock/WatchlistController.java:9。
- 参数版本：backend/src/main/java/com/stocksentinel/strategy/StrategyDefinitionService.java:35。
- 数据门禁：backend/src/main/java/com/stocksentinel/strategy/StrategyContextReader.java:28。
- 规则组合：backend/src/main/java/com/stocksentinel/strategy/engine/SwingTrendStrategy.java:10。
- 评估入口：backend/src/main/java/com/stocksentinel/strategy/StrategyEvaluationService.java:22。
- 原子落库：backend/src/main/java/com/stocksentinel/signal/SignalWriter.java:33。
- 提交后分析：backend/src/main/java/com/stocksentinel/signal/StrategyAdvisoryListener.java:17。
- 迁移：backend/src/main/resources/db/migration/V3__strategy_revisions_and_swing_default.sql:1。
