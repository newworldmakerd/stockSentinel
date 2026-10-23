# 沪深300日线后备源修复 · 2026-10-23

代码、模拟故障测试、真实只读核验和发布包已完成，2026-10-23 10:46获授权后部署并验证生效。本次仅处理指数基准阻塞，不调整策略阈值、持仓或其他扫描策略，不新增数据库迁移。

## 原因与改动

原实现仅请求东方财富，真实记录持续为TREND_INDEX_UNAVAILABLE，指数缓存为空，新买入观察因严格大盘过滤暂停。

- Python新增固定沪深300腾讯日线后备；东方财富失败或数据无效时，改用腾讯完整的一套不复权指数日线重新计算MA200。不拼接来源，不将个股000300当指数，不使用qfqday替代day。
- 两源都校验日期顺序、非重复、正且有限的收盘价、至少201条日线、最新日期等于请求日。腾讯请求固定sh000300，并核对响应身份。腾讯的窗口外过量返回不参与计算，前200/201条窗口均不使用as_of之后价格。
- 返回实际source、历史条数、窗口摘要、fallback_used及安全source_failures；不返回上游错误页、凭据或原始连接异常。
- Java只接受两种已知来源。当日按已人工确认的具体来源选择EASTMONEY/TENCENT/AUTO，且再次校验实际返回来源已经确认；其他股票来源的确认不能授权指数源。
- 同一交易日成功结果由Java落库并复用；双源均失败沿用15分钟重试，缺数据仍暂停新增观察。
- 独立指数HTTP读取超时25秒，容纳两个各自有(3秒连接,8秒读取)上限的源调用；报价与股票指标客户端仍12秒，不扩大其他请求等待。底座预算仍只控制是否启动下一项，非硬中断。
- 状态及群提示区分数据缺失、指数偏弱和允许观察，展示实际指数日线来源，不改变收盘过滤规则。

## 代码定位

| 位置 | 作用 |
| --- | --- |
| quant-service/app/providers/trend_index.py:36 | 腾讯固定指数及原价日线校验 |
| quant-service/app/providers/trend_index.py:63 | 完整窗口故障切换与MA200计算 |
| quant-service/app/api/trend.py:73 | API日期/收盘限制和来源选择 |
| backend/src/main/java/com/stocksentinel/trend/TrendClient.java:13 | 指数独立超时及来源参数 |
| backend/src/main/java/com/stocksentinel/trend/TrendService.java:14 | marketFilter/indexError状态 |
| backend/src/main/java/com/stocksentinel/trend/TrendService.java:26 | 按来源确认、校验、入库及恢复记录 |

## API兼容性

原路径不变：GET `/internal/trend/index?as_of=2026-10-22&current_day_confirmed=false`。

新增可选source：AUTO（默认，主源后备）、EASTMONEY（只该源）、TENCENT（只该源）。未知值422；当日未确认仍422；两源失败返回502与安全原因列表。

当日日线确认源分别为TREND_CSI300_EASTMONEY和TREND_CSI300_TENCENT。只有一个源确认时只请求该源；两者都确认才允许当日自动切换。历史日线可AUTO，仍需最新日期准确匹配。

Java GET `/api/v1/trend`新增marketFilter：ALLOW、PAUSE_MARKET_WEAK、PAUSE_DATA_UNAVAILABLE；缺指数时indexError包含安全错误码及重试时间。

## 验证结果

- Java169项单元测试通过，见`.runtime/trend-index-unit.log`。
- Python146项测试通过，见`.runtime/trend-index-python-final.log`。
- 隔离数据库85项回归通过（趋势28、群助手19、盘中38），见`.runtime/trend-index-database.log`；未修改运行库。
- 新模拟通过真实requests HTTP连接本地两个源服务，验证500错误页、200 HTML、超时、身份错误、过期、不足201、重复和NaN、双源不可用、源选择隔离及不使用窗口外价格，不以替换HTTP客户端的方法跳过运输层。
- 已从真实腾讯取得486条请求窗口内日线。自动模式实际经历东方财富失败后使用腾讯；指定腾讯模式也成功。
- 冻结发布JAR中的实际Java客户端→隔离端口的冻结Python→真实指数源全链路通过。临时Python已停止，运行端口及业务库未动。

真实证据：`.runtime/trend-index-tencent-capability-20261023.json`、`.runtime/trend-index-fallback-live-20261023.json`、`.runtime/trend-index-live-wire-20261023.json`。

截至2026-10-22的指数收盘4036.30，MA200=4670.8512，前一日MA200=4673.5106。按既定严格过滤，恢复基准后应为PAUSE_MARKET_WEAK，而非强行产生新买入观察。

公开API发现未找到合适的腾讯历史日线契约，复用了项目已安装AKShare指数实现与腾讯适配所用端点，并用真实响应核对day、qt、日期、单位及市场标识。

另确认本机Postman CLI 1.75.0可用，实际运行`postman simulation`通过：真实Python提供方代码经HTTP请求模拟东方财富得到500，再请求模拟腾讯得到200，MA200=200.5。请求事件已核对，未使用云端历史、登录或发布。模拟定义在`postman/simulations/trend-index.sim.yaml`，运行脚本`postman/simulations/run_trend_index.py`，证据在`.runtime/index-postman-20261023-104554-227436/`。这项模拟与真实源核验分别记录，不把模拟成功当作供应商可用证明。

## 发布与待办

冻结发布包`.runtime/release-index-backup-20261023-104112/`，启动脚本、JAR、Python源码及SHA256清单保存在目录内；previous-live.jar和previous-release.json用于定位上一版。

已获用户明确授权，短暂重启Java/Python并完成上线检查。进程记录`.runtime/20261023-104645-947818/processes.json`，发布包`deployment-result.json`保存检查快照。两服务UP、企微AUTHENTICATED且2个群、文档关闭、运行库仍V22、2条个人持仓摘要与部署前一致。

运行库已存在来自东方财富的有效2026-10-22指数缓存，因此新版本正确复用主源缓存，没有清空或强写腾讯数据。上线后另外只读调用已部署Python的TENCENT端点成功，收盘与MA200均与缓存一致。状态为marketFilter=PAUSE_MARKET_WEAK、indexError=null；当前暂停原因已明确为指数偏弱。未主动发测试消息；正常已启用调度可推送大盘状态。

其他已知待办仍保留：全市场扫描覆盖/历史失败恢复、已有持仓显式纳入趋势账本及历史回放，不能以本次后备修复宣称全部完成。

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test
$env:PYTHONUTF8='1'
.venv/Scripts/python.exe -m pytest quant-service/tests -q -p no:cacheprovider
# 可选：仅本地模拟，不调用真实源
.venv/Scripts/python.exe postman/simulations/run_trend_index.py
```
