# 扫描覆盖与定向补跑

本轮完善趋势全市场报价轮转、趋势历史准备和技术选股的覆盖诊断。策略阈值、交易时段、大盘过滤、行情有效期及通知幂等规则沿用原逻辑。代码完成后仍需授权部署 V23；未部署时运行中的服务不具备本文新增接口。

## 1. 行为与边界

- 报价优先处理沪深300、有效个人持仓、趋势持仓和启用的公开关注股。趋势历史及技术选股同样优先处理持仓、关注股；定向补跑排在普通历史任务之前。
- 报价按每批最多100个、原有每轮45秒启动预算轮转。单次已经发出的请求仍受客户端超时约束，45秒并非强制中断时刻。
- 整批请求失败、信封无效、缺报价也记录每个预期标的并推进普通股票游标，下一轮继续未尝试部分。轮转状态入库，可在进程重启后继续。
- `quoteCycle`表示跨多次调度的一轮股票池覆盖；`lastScan.checked`表示本次返回的不同预期标的数量，`scheduled`表示本次计划数量，`requested`表示完整池大小，不能将三者混为一谈。
- `attemptCoverageComplete=true`只表示已尝试完整池；`allQuotesValid=true`才表示这一轮所有最新报价通过基础校验。行情有效仍可能因大盘、历史、ST、涨停或规则条件被跳过。
- 同一交易日、阶段、报价代码只保留最新逐股状态和累计次数。运行快照保留当前轮、最近完成或被替代的一轮；不是无限历史轮次账本。
- 过期、未来、身份错误、重复或缺失报价不能生成股票信号。普通股无成交沿用原跳过行为。已有日内信号及通知唯一键保留，补跑不清空、不重发历史信号。
- `last_success_at`对报价表示通过基础时间/身份/价格/成交校验，对历史表示准备成功，对技术选股表示完成规则评估（含未命中）。它不表示买入命中或通知已送达。
- 旧记录的成功时间不回填猜测。`last_error_code`保留最近一次失败，即使随后恢复也保留；是否仍失败看当前`status/code`。
- 本轮没有修改做T、分钟监控、每日收盘任务的策略或重试参数；它们继续使用已有任务诊断。

## 2. 只读接口

基地址：`http://127.0.0.1:8080/api/v1/monitoring/coverage`。GET只查询数据库，不触发行情、模型或企微。

| 请求 | 含义 |
| --- | --- |
| `GET /?tradeDate=2026-10-23` | 交易日轮转、逐原因汇总、前一开市日日线覆盖及补跑状态 |
| `GET /stocks/000731?tradeDate=2026-10-23` | 中文名称、代码、逐阶段报价结果、历史/技术选股尝试和成功时间 |
| `GET /failures?pipeline=TREND_QUOTES&date=2026-10-23&limit=50&offset=0` | 分页查看报价失败 |
| `GET /failures?pipeline=TREND_HISTORY&date=2026-10-22` | 查看指定日线依据日的历史失败 |
| `GET /failures?pipeline=SCREENING&date=2026-10-23` | 查看技术选股失败 |
| `GET /retries/{requestId}` | 查看指定请求的入队前快照、逐股结果、补跑前后尝试次数 |

省略日期：概览/个股/报价/选股使用当日；历史失败使用前一开市日。`limit`范围1～100，`offset`范围0～100000。未知股票/补跑请求返回404，参数无效返回400安全码，不返回原始异常、用户或群身份。

报价原因包括`QUOTE_SOURCE_UNAVAILABLE`、`QUOTE_ENVELOPE_INVALID`、`QUOTE_MISSING`、`QUOTE_DUPLICATE`、`QUOTE_STALE_OR_FUTURE`、`QUOTE_IDENTITY_OR_PRICE_INVALID`、`QUOTE_INVALID`、`TREND_EVALUATION_FAILED`；业务跳过另记大盘阻断、历史未就绪、复权依据不一致、价格限制及收盘价格不一致等。

技术选股新增分阶段失败码：`SCREEN_RAW_HISTORY_FAILED`、`SCREEN_QFQ_HISTORY_FAILED`、`SCREEN_INDICATOR_FAILED`、`SCREEN_CONTEXT_FAILED`、`SCREEN_PULLBACK_FAILED`、`SCREEN_RULES_FAILED`。既有历史失败码仍能解释，不据失败推断规则不匹配。

## 3. 定向补跑契约

`POST /retries`，默认`dryRun=true`：

```json
{
  "pipeline": "TREND_HISTORY",
  "date": "2026-10-22",
  "symbols": ["000731", "600487"],
  "requestId": "history-20261022-01",
  "dryRun": true
}
```

1. 先预览。最多20个不重复六位代码，日期必须为已登记开市日；只有`FAILED`可以入队，不重置成功/排除记录。`requestId`实际入队时必填，限1～64位字母、数字、`_`、`-`。
2. 明确执行时才将`dryRun`设为`false`。这是数据库写入，后续会调用真实行情；部署环境中须先获操作授权。预览不会写审计或请求行情。
3. 同一请求ID、相同范围重复提交返回原审计；同ID不同范围返回409。另一ID不能重复入队同一条尚未完成的失败记录。
4. `TREND_HISTORY`保留自动最多3次、15分钟间隔，人工补跑额外允许一次，累计次数不清零。今日历史仍要求人工收盘确认。已启用的正常历史调度分批消费队列；调度关闭时不会自行执行，可在授权后使用原`POST /api/v1/trend/prepare`处理对应依据日。
5. `SCREENING`只接受当日已有扫描；保留原自动补跑入口行为。新入口仅重置选定失败项为待处理，再恢复正常扫描。任务繁忙返回409，功能关闭返回503。运行中进程中断时队列保留，结果仍为`QUEUED`，不能当作成功。
6. 查询`GET /retries/{requestId}`，逐股最终为`SUCCESS/FAILED/SKIPPED`，尚未处理为`QUEUED`。无符合条件的失败项时`items=[]`，不会为了空请求重新打开扫描。
7. 历史准备结果、待初始化趋势持仓和补跑成功审计同事务提交，绑定失败则回滚并记录失败。通知仍走原有唯一键与原子发件箱。

## 4. 代码定位

- `backend/src/main/java/com/stocksentinel/trend/TrendService.java:62`：原策略评估顺序、优先报价、轮转及逐标的结果。
- `backend/src/main/java/com/stocksentinel/monitoring/ScanCoverageService.java:17`：持久化轮转与游标。
- `TrendMapper.item`、`ReportMapper.item`：逐股状态与补跑审计原子更新。
- `backend/src/main/java/com/stocksentinel/monitoring/ScanRecoveryService.java:24`：范围限制、幂等ID和定向入队。
- `backend/src/main/java/com/stocksentinel/report/ScreeningService.java:67`：保留原数据库调度锁，繁忙时拒绝定向重置。
- `backend/src/main/java/com/stocksentinel/trend/TrendPreparationWriter.java:11`：准备结果与持仓初始化事务边界。
- `V23__scan_coverage_and_recovery.sql`：新增运维表及元数据列；不改持仓数量、成本、已有信号与旧迁移。

## 5. 校验与运行

基础测试沿用README命令。隔离数据库测试必须使用随机测试schema，禁止用`LocalDatabaseIT`的旧公开schema流程替代：

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test
# 先在当前进程设置 STOCKSENTINEL_TEST_DB_URL / USER / PASSWORD，密码仅环境变量。
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml failsafe:integration-test failsafe:verify '-Dit.test=TrendLocalDatabaseIT,ReportsLocalDatabaseIT'
.venv/Scripts/python.exe postman/simulations/run_scan_coverage.py
```

Postman模拟仅访问本机：实际Java客户端向依赖发送两批请求并遭遇503 HTML响应，验证游标继续、199个不同标的有失败记录、无股票信号；不会访问真实行情或企微，日志不上传云端。
