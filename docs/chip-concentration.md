# 筹码集中候选策略

状态：已开发，完成模拟验证；默认关闭。V24仅用于隔离测试，未应用到本机运行库。2026-10-23已重新部署的是扫描可靠性V23，筹码功能尚未上线，也未向群发送测试消息。

## 规则和展示

策略标识`CHIP_CONCENTRATION_V1`，独立于趋势、回踩、日线买卖和做T策略。沪深活跃股票目录排除ST，日线有效记录不少于120条，依据日有成交；20日平均成交额不低于1亿元。同时满足：

- 90%筹码集中度≤0.10（10%）。
- 70%筹码集中度≤0.06（6%）。

两项均为包含边界的初始研究参数，尚未回测。按90%集中度升序、70%升序、20日均成交额降序、代码升序排序，最多5只。仅为观察候选，不自动加入关注、不产生直接买卖指令、不调用模型、不下单。

群消息以`🔎【选股候选】🧩 筹码集中候选`开头，建议操作在前，随后展示中文名称和代码、70%/90%集中度及成本区间、估算成本、获利比例、依据日期和失败覆盖数。

筹码集中只是成交和换手模型的估算，不能证明主力吸筹、真实持股集中或后续上涨；集中套牢筹码同样可能满足规则。

## 数据与算法

- Python固定请求东方财富不复权日K，`klt=101,fqt=0,lmt=210,end=依据日`，最长210条；手转换成股、成交额沿用人民币元，换手率以源的百分数输入原算法。
- 复用已安装AKShare `stock_cyq_em`的本地JavaScript计算部分，不执行该SDK函数内部未设置超时的联网请求。只计算所需末日，避免每股重复计算90日所有结果。
- `(成本上沿-下沿)/(上沿+下沿)`为集中度比例，越小越集中；与“区间宽度/均价”不是同一倍数，不能混用阈值。成本区间是源算法的中间70%/90%分位范围。
- `average_cost`沿用SDK字段命名；其实现取成本分布50%分位，展示为“估算成本”，不视作个人成本或真实机构成本。
- 保存算法脚本SHA-256、输入窗口SHA-256、来源、版本`akshare-cyq-210-v1`、复权类型、历史条数和采集时间，支持追溯。不会由Python写业务数据库。
- 请求超时为连接3秒/读取8秒，进程内串行、至少间隔1秒；JS解析/计算分别限时2秒/3秒。Java客户端读取限时18秒。
- 验证代码、市场、日期、顺序、末日、OHLC、数值有限性和区间。未来行、缺失依据日、重复日期、NaN、历史不足分别拒绝，不截取错误窗口冒充成功。

主依据：[AKShare官方股票文档](https://akshare.akfamily.xyz/data/stock/stock.html)、[AKShare官方算法源码](https://github.com/akfamily/akshare/blob/master/akshare/stock_feature/stock_cyq_em.py)。当前仅核验文档、安装包源码及模拟协议；尚未验证真实东方财富可用性。按用户“先模拟验证”要求，没有执行新数据源真实调用；也未执行Orbit等新增外部API查询。

## 调度、覆盖与通知

未来启用后，开市日15:30～23:00分批准备，最多每批20只，单轮启动预算45秒、批次间隔30秒。持仓/关注股优先。预算只控制下一请求是否启动，不强制中断已发出的请求。单源全市场扫描可能持续数小时，不能承诺15:30即得到完整结果。

今日只在15:00之后且存在`CHIP_EASTMONEY`来源的人工收盘确认时采集；其他来源的确认不代替本来源。无确认时返回`CHIP_CLOSE_CONFIRMATION_REQUIRED`，不取今日盘中数据充当最终筹码。历史开市日可通过明确请求分批准备。

V24独立表`chip_scan_run/chip_scan_item`冻结首次扫描参数及股票池，保存逐股状态、失败码、尝试次数及时间。重复扫描不重复处理已成功股票；失败最多3次、15分钟后重试，连续3只失败整批退避15分钟。任务重启后按数据库进度继续。

全部待处理和可重试项耗尽后，生成一次最终汇总：无失败为`COMPLETED`，有失败为`PARTIAL`。未完成范围、失败和无候选分开说明。23:00尚未完成则仍保留`RUNNING`，不会把未完成当作完整无候选。首版没有增加跨日自动补扫或人工重置已耗尽重试的入口。

运行状态及所有群消息入队同事务提交，各群以日期和目标摘要幂等；最多3次明确失败重试，不明送达`UNKNOWN`不盲重发，过期未发消息8小时后作废。只发候选信息，不携带个人仓位或身份。不会改动原日报或其他策略的推送。

## 配置与接口

以下仅为配置说明，当前不启用：

```dotenv
CHIPS_ENABLED=false
CHIPS_SCHEDULE_ENABLED=false
CHIPS_CONCENTRATION90=0.10
CHIPS_CONCENTRATION70=0.06
CHIPS_MINIMUM_AMOUNT=100000000
MARKET_CHIP_ENABLED=false
```

Java前两个开关控制业务和自动调度，Python末个开关单独控制新增源。与其他策略开关互不代替。修改阈值只影响尚未开始的日期，已开始扫描沿用冻结参数。

| 接口 | 行为 |
| --- | --- |
| `GET /api/v1/chips?asOf=2026-10-22` | 只读状态、参数、覆盖、前5候选；关闭时返回DISABLED |
| `GET /api/v1/chips/items?asOf=2026-10-22&limit=50&offset=0` | 逐股只读记录；limit为1～100，offset为0～100000 |
| `POST /api/v1/chips/scan` | 默认只预览；`{"asOf":"2026-10-22","dryRun":false}`才真实分批采集/写库，完成后可能入通知队列 |
| `GET /internal/chips/snapshot?symbol=000731&as_of=2026-10-22&current_day_confirmed=false` | Python固定来源行情和筹码计算；默认503关闭 |

GET不会触发行情或模型。实际扫描会写库，完成时可能发群通知，必须在得到真实调用及运维授权后操作。返回候选不表示已送达。

## 校验与定位

- `backend/src/main/java/com/stocksentinel/chips/ChipRules.java`：固定规则、比例和字段验证。
- `ChipService.java`：开市日/收盘确认、分批、重试退避及候选消息。
- `ChipWriter.java`：建池和通知事务。
- `quant-service/app/providers/chips.py`：限速请求及本地SDK算法。
- `backend/src/main/resources/db/migration/V24__chip_concentration.sql`：仅新增独立表。
- `quant-service/tests/test_chips.py`：真实SDK算法、单位和坏数据测试。
- `postman/simulations/run_chips.py`：实际Java/Python HTTP客户端对本地Postman依赖，验证200候选与503 HTML错误，无真实行情或企微调用。

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test
$env:PYTHONUTF8='1'
.venv/Scripts/python.exe -m pytest quant-service/tests -q -p no:cacheprovider
# 先在进程环境设置隔离测试DB的URL/USER/PASSWORD，密码不落文件。
.venv/Scripts/python.exe postman/simulations/run_chips.py
```

真实源能力核验、V24部署及正式启用仍待后续明确授权；现有V23服务保持运行。
