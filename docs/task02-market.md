# TASK-02 行情采集与持久化

## 背景与目标

Java 从 Python 获取标准化股票与日线数据并负责入库；Python 通过 AKShare 提供行情，不持有业务数据库凭据。本阶段不启用定时扫描、策略评估、模型或企微发送。

## 修改范围与证据

| 位置 | 作用 |
|---|---|
| quant-service/app/providers/akshare_provider.py:14 | AKShare适配，按交易所来源识别股票，行情单位标准化，串行限速 |
| quant-service/app/api/market.py:19 | 真实行情调用开关；默认关闭 |
| quant-service/app/api/market.py:44 | 日线内部API、日期范围、结构化响应 |
| backend/src/main/java/com/stocksentinel/market/MarketContracts.java:10 | 跨服务数据契约与基础校验 |
| backend/src/main/java/com/stocksentinel/market/MarketCollectionService.java:15 | 整批响应验证、仅收取历史完成行、120条数量提示 |
| backend/src/main/java/com/stocksentinel/market/MarketWriter.java:12 | 每批500条，单次同步事务写入 |
| backend/src/main/java/com/stocksentinel/market/MarketMapper.java:21 | 股票UPSERT，仅更新名称、交易所、更新时间 |
| backend/src/main/java/com/stocksentinel/market/MarketMapper.java:37 | 日线按股票、日期、复权方式UPSERT |
| backend/src/main/java/com/stocksentinel/market/MarketController.java:17 | 股票同步、日线导入、已存日线查询 |

本任务没有修改已有迁移，也没有更改规则/通知/模型业务逻辑。

## 行情口径

依据 [AKShare官方历史行情文档](https://akshare.akfamily.xyz/data/stock/stock.html) 的 stock_zh_a_hist 输出定义：

- 成交量由手乘100转换为股；成交额保持人民币元；换手率由百分数除100转为比例（0.0125表示1.25%）。
- NONE/QFQ/HFQ 分别映射 SDK 的空字符串/qfq/hfq，分开存储。
- 价格使用Decimal/BigDecimal，拒绝非数值、非正价、负成交量、不合理OHLC、重复日期、代码或复权类型不匹配。
- 现有DailyBar契约要求正价格；官方说明长历史QFQ可能出现负价，目前会显式失败，不擅自改变原契约，也不会丢行后假装成功。
- 股份市场来自SH/SZ/BJ各自股票列表，不通过代码前缀猜测交易所。
- 当日日线始终 is_final=false，Java跳过入库；早于上海当地当天的日期视为历史行。这是保守的历史日期隔离，并不等于供应商提供了最终更新标识。
- 完整交易日历、缺日/停牌判定、收盘15:15数据确认仍属于TASK-06，不会凭本次行数生成信号。

## API

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | Python /internal/stocks | 标准化股票列表 |
| GET | Python /internal/market/daily | 指定股票、日期范围、复权类型日线 |
| POST | Java /api/v1/stocks/sync | 同步基础股票目录 |
| POST | Java /api/v1/market/{symbol}/daily/import | 导入已同步股票的日线 |
| GET | Java /api/v1/market/{symbol}/daily | 查询数据库日线，不访问供应商 |

日线参数：symbol、start、end、adjustment；Java的symbol在路径中。日期格式yyyy-MM-dd，最大3660天，默认NONE。
先同步股票再导入；股票缺失返回409，关闭返回503，上游失败或数据异常返回502，参数不合法返回400/422。

导入响应包括 received、written、skipped、historySufficient。written是本次UPSERT受影响行数，包含更新，不等于新增数。
historySufficient仅表示本次响应的完成行至少120条，不证明交易日连续、预热期完整或可以产生建议。
空响应得到0条和false，不虚构数据，不生成信号。

## 事务与保留逻辑

网络调用不在写事务内。完成整批校验后，交由独立Spring服务开启事务，500条一批；任一写入失败回滚该次同步事务。
股票同步保留status、industry、listing_date等原字段，不根据目录缺失自动退市或删除自选股。
日线仅更新同symbol/trade_date/adjustment的行情字段与来源、采集时间，不覆盖其他复权序列。

可直接使用的查询：

```sql
SELECT symbol, trade_date, adjustment, close_price, volume, amount, turnover_rate, source
FROM public.market_daily_bar
WHERE symbol = '600487' AND adjustment = 'QFQ'
ORDER BY trade_date;
```

## 无Docker启动与调用准备

以下为人工重现命令。用户已授权并执行一次真实联调，结果见文末；重跑仍会实际调用供应商并写库。

Python窗口：

```powershell
$env:MARKET_PROVIDER_ENABLED = 'true'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir quant-service --host 127.0.0.1 --port 8000
```

Java窗口：

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:POSTGRES_USER = 'myrag_user'
$env:POSTGRES_PASSWORD = Read-Host 'Database password' -MaskInput
$env:PYTHON_URL = 'http://127.0.0.1:8000'
$env:MARKET_ENABLED = 'true'
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1
```

人工触发：

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/stocks/sync
Invoke-RestMethod -Method Post 'http://localhost:8080/api/v1/market/600487/daily/import?start=2025-01-01&end=2026-10-19&adjustment=QFQ'
Invoke-RestMethod 'http://localhost:8080/api/v1/market/600487/daily?start=2025-01-01&end=2026-10-19&adjustment=QFQ'
```

## 验证与边界

- Java package：17项单元/应用/HTTP测试通过。真实Java客户端通过本地HTTP服务器读取固定Python契约响应，验证日期、Decimal及is_final映射。
- Python pytest：18项通过，覆盖量额/换手率转换、复权、当天未完成、重复/越界/代码错误、NaN/负值、股票交易所、API关闭和错误脱敏。
- 本机没有Postman CLI，参考API Simulation技能采用本地JDK HTTP服务器固定响应；没有调用真实供应商验证。本地响应由当前跨服务契约生成。
- PostgreSQL本机集成验证与基线说明见local-database.md；样例测试不能当作真实600487行情验收。
- AKShare的日线请求设置15秒超时；Java连接5秒、读取90秒。SH/BJ股票列表在当前SDK实现中部分请求未暴露超时，Java超时后Python SDK请求可能仍占用工作线程；大规模同步前需进一步处理。
- 适配器单实例串行访问，调用间至少1秒；不自动重试，失败不伪装为空结果。
- QFQ历史重定价会更新本次请求区间；跨区间一致性和指标历史重算须在后续指标/任务阶段实现，不宣称已有复权批次闭环。

## 回滚与待验收

关闭MARKET_ENABLED与MARKET_PROVIDER_ENABLED即可停止真实采集。没有自动定时任务，不会因服务启动直接调用供应商。
真实联调已按授权尝试，目录同步成功，600487日线因上游连接中断未完成；详见下文。
## 本阶段最终验收

- Java package 17项测试通过，原生PostgreSQL LocalDatabaseIT 2项通过，共19项。
- Python 18项通过，compileall通过。
- myrag.public已登记V1基线并成功执行V2；真实目录同步前，测试样例已回滚。
- 测试130条日线为固定样例，已回滚，没有冒充真实600487行情。
- 本阶段未启用模型或企微。

## 已授权真实联调结果

- Java和Python临时本机服务启动，股票目录通过真实AKShare接口获取并成功UPSERT 5575条。
- 600487 / 2025-01-01至2026-10-19 / QFQ请求返回502；沙箱外直接调用同一适配器复现ConnectionError，底层为RemoteDisconnected。
- 本轮没有获取到有效日线，写入0条；不声明真实120交易日日线验收成功，不切换来源、不填充模拟行情。
- 临时服务已停止；模型和企微未调用。脱敏结果见task02-live-result.json。
- 原生集成测试采用999999固定样例代码并检查其不存在后执行，避免与真实600487目录数据冲突；样例事务回滚。
