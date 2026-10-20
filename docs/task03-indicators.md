# TASK-03 指标计算与持久化

日期：2026-10-20。结论：已完成MA5/10/20/60、DIF/DEA/MACD柱体、量能比、固定计算版本及预热校验，并通过真实Java→本地Python→PostgreSQL固定样例集成测试。没有调用真实行情、模型或企微。

## 背景与目标

Python计算指标，Java读取已入库日线、组织计算及落库，支持按股票/日期/版本/复权方式查询。
本阶段不实现TASK-04策略引擎，不生成交易信号，也不自动调用模型。

## 修改范围

| 位置 | 实现 |
|---|---|
| quant-service/app/indicators/calculate.py:10 | 固定版本及120条预热约定 |
| quant-service/app/indicators/calculate.py:48 | Decimal指标计算、输入序列校验、输出精度 |
| quant-service/app/api/indicator.py:8 | Python内部计算API与明确错误码 |
| quant-service/app/main.py | 注册新增路由，保留原健康检查及行情接口 |
| backend/src/main/java/com/stocksentinel/indicator/IndicatorContracts.java:11 | Java/Python请求、响应和版本契约 |
| backend/src/main/java/com/stocksentinel/indicator/PythonIndicatorClient.java:13 | 本地HTTP客户端，ISO日期及精确十进制字符串传输 |
| backend/src/main/java/com/stocksentinel/indicator/IndicatorService.java:23 | 完整历史前缀、输入与响应校验、数据不足拦截 |
| backend/src/main/java/com/stocksentinel/indicator/IndicatorMapper.java:13 | 日线读取和指标查询 |
| backend/src/main/java/com/stocksentinel/indicator/IndicatorMapper.java:30 | 按现有唯一键UPSERT |
| backend/src/main/java/com/stocksentinel/indicator/IndicatorWriter.java:12 | 500条一批，整个写入调用在事务内 |
| backend/src/main/java/com/stocksentinel/indicator/IndicatorController.java:21 | 手动计算及查询API、ProblemDetail错误码 |
| backend/src/test/java/com/stocksentinel/ApplicationHealthTest.java | 隔离数据库的原有健康测试补充新Mapper模拟依赖 |

复用market_indicator_daily及其唯一约束，不修改V1/V2迁移、行情采集逻辑、股票状态、RAG表或已有模型逻辑。

## 固定计算口径

计算版本：`ma-macd-vol-v1`。一条输入序列只能包含同一股票、同一复权方式、同一来源。

| 项目 | 定义 |
|---|---|
| MA5/10/20/60 | 含当日在内最近N条完成日线的收盘价算术平均 |
| EMA12/EMA26 | alpha=2/(N+1)，首值取第一条输入的收盘价，后续递推 EMA[t]=EMA[t-1]+alpha×(close[t]-EMA[t-1]) |
| DIF | EMA12−EMA26 |
| DEA | 对DIF做EMA9；首个DIF为0，DEA初值为该DIF |
| MACD柱体 | 2×(DIF−DEA)，使用未提前舍入的DIF/DEA计算 |
| 量能比 | 当日成交量÷此前5条日线平均成交量，不含当日 |
| 零基准均量 | volume_ratio=null，Java结果undefinedVolumeRatios计数；不伪造0或无穷值 |
| 当日成交量为0、此前均量>0 | volume_ratio=0 |

使用50位Decimal上下文递推，中间过程不按数据库精度截断。最终MA和量能比保留6位，DIF/DEA/柱体保留8位，ROUND_HALF_UP。
Java请求中的BigDecimal以字符串传输，避免Python JSON解析经过浮点而损失精度；日期使用ISO文本。Python Decimal响应同样以字符串输出。
超过数据库NUMERIC(18,6)/(18,8)可表示范围返回INDICATOR_NUMERIC_OVERFLOW，不写入截断结果。

## 预热、缺失及顺序

- 不足120条拒绝计算，HTTP422，错误码INSUFFICIENT_HISTORY，包含required与received信息。
- 第1至119条只用于初始化/预热，第120条才输出第一组指标。130条输入输出11组。
- 判断金叉需要前后两组已预热指标，因此后续策略至少要有121条有效历史；本模块不自行产生信号。
- 拒绝混合股票/复权/来源、重复日期、逆序、未完成日线、当天或未来日线；不排序掩盖输入错误，不补缺失值。
- 复用既有DailyBar正价格、非负成交量及OHLC检查，NaN/缺失字段在API层返回422。
- 上限10000条，超过上限显式拒绝，不截取最近一段计算。
- 交易日历和停牌造成的缺日判定仍属TASK-06；本模块按传入完成日线条数计算，不声称已经验证交易日连续性。

Java的asOf是必填历史日期，必须早于上海当地当天。读取该股票、该复权方式从数据库最早记录到asOf的全部日线，避免任意start参数改变EMA锚点。
版本定义固定了首价初始化规则；新增更早历史或历史复权价格修订后，应重新运行该完整前缀。当前提供手动重算，不改动行情写入顺序，也不添加自动重算任务。

## 接口

### Python

`POST /internal/indicators/calculate`

输入字段为symbol、adjustment、calculation_version（默认当前版本）和bars（既有DailyBar契约）。
输出包括symbol、adjustment、calculation_version、history_size、warmup_period、indicators。
每组包含trade_date、ma5/ma10/ma20/ma60、dif、dea、macd_hist、volume_ratio。

内部计算不访问数据库或行情源，MARKET_PROVIDER_ENABLED=false时仍可使用。未知计算版本返回422。

### Java

```http
POST /api/v1/indicators/600487/calculate?asOf=2026-10-19&adjustment=QFQ
GET /api/v1/indicators/600487?asOf=2026-10-19&adjustment=QFQ&calculationVersion=ma-macd-vol-v1
```

POST仅读取已存日线，返回版本、历史条数、UPSERT受影响条数和未定义量能比数量；不拉行情。
GET返回指定版本/复权方式、截至asOf最近两组指标，日期倒序；没有结果返回空数组。
QFQ为默认复权类型，不与NONE/HFQ混算。错误参数400、历史不足或历史不合法422、Python不可用/响应不符502。
返回ProblemDetail的code/detail字段，避免只有HTTP状态无法识别历史不足。

当前真实600487日线仍未从上游成功获取，因此用它直接调用POST会得到历史不足，GET返回空数组；这与固定样例计算通过并不矛盾。

## 落库与幂等

复用唯一键(symbol,trade_date,calculation_version,adjustment)。重复计算更新指标值及calculated_at，不增加相同键的记录；不同版本和复权类型独立保存。
网络计算不持有写事务；响应版本、历史条数、预热条数、输出日期及数量全部验证后，再在独立写服务中开启事务分批写入。
本模块是人工触发的历史快照计算；与并发回补/修订数据之间没有实现跨服务任务锁，调度与自动重算将在TASK-06处理，不宣称已有自动一致性闭环。

查询SQL：

```sql
SELECT symbol, trade_date, adjustment, calculation_version,
       ma5, ma10, ma20, ma60, dif, dea, macd_hist, volume_ratio
FROM public.market_indicator_daily
WHERE symbol = '600487'
  AND adjustment = 'QFQ'
  AND calculation_version = 'ma-macd-vol-v1'
ORDER BY trade_date DESC
LIMIT 2;
```

## 验证结果

- Java：26项常规测试、4项原生PostgreSQL集成测试通过，共30项，0失败、0错误、0跳过；JAR打包成功。
- Python：38项pytest通过，compileall通过。一个依赖TestClient弃用提示不影响结果。
- 独立参考一：常数序列的MA=常数、MACD=0；线性序列用闭式EMA和独立加权求和DEA验证8位结果。
- 独立参考二：非线性序列与pandas rolling/ewm(adjust=False)逐行对照，不以生产递推函数自证。
- 130条线性样例最后MA5=227、MA10=224.5、MA20=219.5、MA60=199.5；量能比2290/2260=1.013274，验证不包含当日均量。
- Native测试使用999998固定样例：真实Java客户端→临时本地Python→PostgreSQL，验证11组指标、重复写入、NONE/QFQ分离、版本隔离、历史不足不落库。
- 所有测试样例事务回滚；只读复查真实股票目录5575条，999998/999999样例股票、日线和指标均0条，指标表总行数0。
- Flyway保持V1基线和V2，两条均成功，无新DDL。
- 临时Python进程及子进程已清理。本阶段没有真实行情、LLM或企微调用。

证据：task03-local-integration.log、task03-python-test.log、task03-python-integration.log。
跨语言测试曾实际发现日期数组导致422，已固定ISO日期传输，并添加真实HTTP请求形状和Decimal字符串保真测试。

## 无Docker复测命令

项目根目录执行：

```powershell
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q -p no:cacheprovider
.\.venv\Scripts\python.exe -m compileall -q quant-service/app quant-service/tests

$env:STOCKSENTINEL_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:STOCKSENTINEL_TEST_DB_USER = 'myrag_user'
$env:STOCKSENTINEL_TEST_DB_PASSWORD = Read-Host 'Test database password' -MaskInput
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=LocalDatabaseIT,IndicatorLocalDatabaseIT' verify
Remove-Item Env:STOCKSENTINEL_TEST_DB_PASSWORD
```

测试只在配置上述DB环境变量时启用原生集成部分，样例ID已存在则拒绝执行；不要指向生产库。序列分配值不随事务回滚。
Java启动、Python启动和数据库连接参数沿用README；调用本模块不需要开启MARKET_ENABLED，也无需Docker。

## 后续任务与回滚

TASK-03验收完成。TASK-04本地规则接口、规则执行、信号生成及后置模型自动调用尚未实现。
本次无数据库迁移需要回滚。停止调用新增指标API即可停止计算，已存指标按版本保留，不自动删除或覆盖其他版本。