# StockSentinel

2026-10-23新增[扫描覆盖与定向补跑](docs/scan-coverage-and-recovery.md)及[筹码集中候选策略](docs/chip-concentration.md)。筹码策略已完成模拟验证，默认关闭；使用V24独立迁移，真实源能力核验和启用尚未完成。所有本机密钥通过环境变量注入，不提交运行数据或日志。

新增企微群工具对话、我的/本群关注列表及关注人记录，使用说明见[群对话助手](docs/group-assistant.md)。

2026-10-21新增强势股缩量回踩两阶段策略、腾讯日线后备和日报规则依据；通知支持中文名称及信号模型分析。最新运行说明见 [两阶段策略](docs/strong-pullback.md)，下文各任务实测记录保留为历史阶段状态。

已新增 [交易日日报与全市场技术候选](docs/daily-report-screening.md)：15:30 自动推送，首份真实日报已获企微成功 ACK。大盘及关注股报价可用；全市场技术筛选目前因日线源故障覆盖不足，日报会明确披露，未强行推荐。

最新更新见 [腾讯分钟后备接口](docs/tencent-backup.md)：保留东方财富为主源，失败时使用腾讯，两只股票的真实后备请求均已成功，交易时段推送仍待观察。群内加关注用法见 [群监控启用记录](docs/group-monitoring.md)。下文各任务章节保留其阶段性记录。

A股智能监控系统基础工程。当前已完成 TASK-01 初始化、TASK-02 行情与持久化、TASK-03 指标计算、TASK-04 自选股与规则引擎、TASK-05 企微通知、TASK-06 收盘扫描、追加盘中短线监控及本机数据库集成验证，以及追加的大模型辅助分析模块。
Java 管业务、调度及数据写入；Python 管行情与指标。保留根目录原始技术方案，无自动下单功能。

## 目录

```text
backend/          JDK21、Spring Boot 3.x、MyBatis-Plus、Flyway、JUnit5
quant-service/    Python 3.11+、FastAPI、AKShare依赖、行情契约、pytest
database/        迁移说明（唯一SQL源在 backend/src/main/resources/db/migration）
deploy/           Docker Compose（PostgreSQL18、Java、Python）
docs/             架构计划、大模型补充方案、验证结果
.env.example      非敏感配置模板；实际密钥通过环境变量注入
```

本机无需Docker：PostgreSQL已接入myrag，复用现有10张核心表，登记Flyway V1基线并执行V2、V3、V4、V5、V6。详见 [本机数据库记录](docs/local-database.md)、[TASK-02实现与调用](docs/task02-market.md)。真实行情开关默认关闭；已授权联调并同步5575条真实股票目录，600487日线请求被上游断开，尚未完成真实日线验收。

TASK-03已完成MA5/10/20/60、MACD、量能比及版本化落库；Java30项、Python38项测试通过，详见 [指标口径与接口](docs/task03-indicators.md)。TASK-04现已完成：自选股、策略版本、三类信号及提交后模型分析；Java56项、Python38项测试通过，详见 [规则与接口](docs/task04-strategy.md)。

详见 [架构与开发计划](docs/architecture.md)、[大模型模块](docs/llm-advisory.md)、[验证记录](docs/verification.md)。

## 本地环境

已检测 JDK 21.0.10、Maven 3.8.8、PostgreSQL 18.3 CLI；系统没有可直接使用的 python 命令，也没有 Docker。
本次通过 Codex 随附 Python 3.12.14 创建了项目 `.venv`，未安装或改动全局环境。

新机器准备 JDK21、Maven、Python3.11+（建议3.12）、Docker Compose v2。
requirements.lock 是本次 Python3.12 验证过的依赖快照，容器使用 Python3.12。

## 编译与测试（在项目根目录）

```powershell
mvn -B -s maven-settings.xml "-Dmaven.repo.local=.maven-repository" -f backend/pom.xml clean package

# 新机器先创建虚拟环境；本机已创建，无需重复执行
# py -3.12 -m venv .venv
$env:PIP_CACHE_DIR = Join-Path (Get-Location) '.pip-cache'
.\.venv\Scripts\python.exe -m pip install -r quant-service/requirements.lock
.\.venv\Scripts\python.exe -m pip install --no-deps -e './quant-service[test]'
.\.venv\Scripts\python.exe -m compileall -q quant-service/app quant-service/tests
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q
.\.venv\Scripts\python.exe -m pip check
```

项目内 Maven 设置绕过本机失效缓存路径，使用 Maven Central，不改全局 settings.xml。
`package` 执行无需数据库的测试及真实本地HTTP健康检查；测试中数据库依赖被隔离，不能替代迁移验收。

安装并启动 Docker 后执行完整 PostgreSQL 集成测试：

```powershell
mvn -B -s maven-settings.xml "-Dmaven.repo.local=.maven-repository" -f backend/pom.xml verify
```

DatabaseIT 由 Testcontainers 创建一次性 PostgreSQL18，验证六版迁移、核心表及日线唯一约束。
无 Docker 时该命令会失败，不会静默跳过数据库检查。

## 无Docker数据库集成测试

本机使用LocalDatabaseIT测试入口，环境变量及命令见 [本机数据库记录](docs/local-database.md)。不运行依赖Docker的DatabaseIT；按 `-Dit.test=LocalDatabaseIT,IndicatorLocalDatabaseIT,StrategyLocalDatabaseIT,NotificationLocalDatabaseIT,DailyScanLocalDatabaseIT,IntradayLocalDatabaseIT` 选择全部原生测试，完整命令见 [TASK-04](docs/task04-strategy.md)。

## Docker Compose 启动

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
# PowerShell7：交互输入，不写入文件，也不出现在命令历史中
$env:POSTGRES_PASSWORD = Read-Host 'Local PostgreSQL password' -MaskInput

docker compose --env-file .env -f deploy/docker-compose.yml config --quiet
docker compose --env-file .env -f deploy/docker-compose.yml up --build -d
```

不要输出完整 compose config，以免把环境变量中的凭据打印出来。
Compose 默认仅绑定本机127.0.0.1；数据库密码不能为空；数据保存在命名卷，Flyway由Java启动时执行。
PostgreSQL18 使用 `/var/lib/postgresql` 卷路径。启动后检查：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8000/health
```

Java `/api/v1/health` 是进程探活，`/actuator/health` 包含数据库健康。

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml down
```

停止命令保留数据卷。当前环境缺少Docker，尚未实际构建镜像或启动Compose。

## 不使用 Docker 的启动方式

Python无需数据库：

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir quant-service --host 127.0.0.1 --port 8000
```

本机已核对并接管myrag中的StockSentinel表，可使用下列连接。其他新机器请先准备专用空库，启动会执行DDL，不要直接指向未经核对的已有业务库：

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:POSTGRES_USER = 'myrag_user'
$env:POSTGRES_PASSWORD = Read-Host 'Dedicated database password' -MaskInput
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1
```

## 大模型模块

默认 LLM_ENABLED=false，所有测试均为模拟，不发生真实费用。
启用前设置 LLM_BASE_URL（包含供应商版本路径）、LLM_MODEL、LLM_API_KEY。
API key 仅在shell/secret manager注入，不写入.env或其他文件。

```powershell
$env:LLM_API_KEY = Read-Host 'LLM API key' -MaskInput
# 在.env设置非敏感的地址、模型名及LLM_ENABLED=true后重新创建backend容器
# 此后手动调用分析接口将发起真实请求并产生供应商费用
```

入口 `POST /api/v1/signals/{id}/advice` 只接受已落库规则信号ID。
模型输出条件式交易建议、依据、风险和失效条件，存入 signal_advice，不改写原始信号。
TASK-04已接入：新规则信号事务提交后才触发模型；默认关闭，重复评估不重复调用。TASK-06已实现默认关闭的15:15和手动扫描，需日历与当日源确认，详见docs/task06-daily-scan.md。

## 企微智能机器人

已实现 Bot ID + Secret 长连接通知、消息模板、持久任务、并发幂等和有限退避重试；默认关闭。
环境变量名：WECOM_ENABLED、WECOM_MODE=SMART_BOT、WECOM_BOT_ID、WECOM_BOT_SECRET、WECOM_CHAT_ID。
项目文件不保存真实凭据。已通过本地WebSocket协议与数据库测试；尚缺目标群chatid及真实连接/发送授权，未连接真实企微或发送消息。详见 [TASK-05接口与运行](docs/task05-notifications.md)。Java81项、Python38项测试通过，V4已应用。

## 尚未完成

- Docker镜像/Compose启动尚未验证；本机PostgreSQL迁移和无Docker集成测试已通过。
- TASK-02适配与入库代码已完成，真实目录已同步，日线供应商连接中断导致联调未完成；指标计算已在TASK-03完成；自选股与规则引擎已完成，交易日调度已在TASK-06完成，真实扫描未执行。
- 大模型真实供应商联调仍待配置并授权；规则提交后的调用链已通过模拟验证。
- 企微真实群鉴权与消息送达验收；客户端、投递及有限重试已完成。
## 2026-10-20 日线重试更新

600487已成功获得东方财富真实日线：2025-01-02至2026-10-19，QFQ和NONE各432条。原AKShare接口间歇性RemoteDisconnected，成功获取后保存真实标准化快照，再经本地只读快照服务向原Java导入接口提供同一数据；Java负责校验与写入，未改供应商适配或切换入库来源。两种序列分别入库432条，共864条；重复导入后各仍432条，日期无重复。证据：docs/daily-snapshot-import-result.json；快照：local-data/daily-600487-20261020-130450。直接联网Java导入重试仍502，不能声明联网获取已稳定。未计算指标，未生成信号，未调用模型/企微；临时服务已停止。此前“日线0条”的记录为历史阶段状态。

## 获取群chatid

已提供独立只接收模式，无需预先配置chatid，也不启动数据库或发送消息：

```powershell
$env:WECOM_BOT_ID = Read-Host 'Bot ID' -MaskInput
$env:WECOM_BOT_SECRET = Read-Host 'Bot Secret' -MaskInput
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --discover-wecom-chatid
```

鉴权成功后在目标群 @机器人，控制台显示chatid后退出。真实连接前需获授权，并先停止同一机器人的其他连接实例。完整说明见 [chatid发现模式](docs/wecom-chatid-discovery.md)。

## TASK-06 收盘任务

已实现交易日历导入、人工收盘确认、15:15扫描、手动补跑、数据库互斥、逐股阶段日志及遗留任务标记。默认JOBS_ENABLED=false、DAILY_SCAN_SCHEDULE_ENABLED=false；不自动调用真实服务。人工确认仅收盘后有效，未确认当日或缺日历时跳过；不以工作日推断交易日。Java100项、Python39项通过，V5已应用。真实行情到通知扫描尚未执行，当前日历为空。接口、启用方式和完整测试命令见 [TASK-06](docs/task06-daily-scan.md)。

## 盘中短线监控

用户追加的沪深自选股分钟监控已实现：每分钟尝试，五分钟涨跌±2%、突破此前20分钟高点且量能≥1.5倍，以及独立配置的止盈止损价格；同类冷却15分钟。独立分钟表和signalAt，不改变原日线规则。午休、休市、缺日历和过期数据跳过。INTRADAY_ENABLED和INTRADAY_SCHEDULE_ENABLED默认false，真实扫描未启用；需先配置监控股票与价位。Java118项、Python49项通过，V6已应用。完整API、边界及启用命令见 [盘中监控](docs/intraday-monitoring.md)。

## 中长线趋势跟踪（本机已启用）

中长线趋势跟踪已于2026-10-22获授权后本机启用，运行库V22；全市场底座持续分批准备，指数缺失严格暂停新增观察。包含趋势/突破、指数过滤、分批成交、ATR止损和仓位试算，用法见[趋势跟踪](docs/trend-following.md)和[部署记录](docs/trend-deployment-20261022.md)。

## 每日股票智能表格（待启用）

新增机器人文档授权MCP接入：新建智能表格、每日公开关注/候选/公共信号快照归档、逐日去重及不明写入停止重试。默认关闭，V21尚未部署，需在企微授权后将敏感入口URL保存到操作系统环境变量；不使用机器人Secret代替文档授权。配置、接口、测试命令及7天授权限制见[每日股票智能表格](docs/wecom-stock-document.md)。
