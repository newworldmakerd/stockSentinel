# 本机 PostgreSQL 接入记录

日期：2026-10-20。用户确认可执行SQL建表操作；不使用Docker。

## 已核对的目标

- localhost:5432，数据库myrag，角色myrag_user，PostgreSQL18.3。
- public原有3张RAG表和10张StockSentinel核心表。没有Flyway历史表和signal_advice。
- 初始只读确认stock_info、market_daily_bar、signal_event均为0行。
- 对比91个字段的类型/长度/精度/可空性/默认值/identity、23个主键/唯一键/外键约束和3个普通索引，现有10张核心表与V1一致。
- 证据为database-public-inventory.json及database-v1-comparison.txt；只有结构信息，没有密码和业务行数据。
- 可用scripts/compare_v1_schema.py离线重现结构对比；该脚本不连接数据库、不执行DDL。

## 已执行的迁移

经用户授权，使用Java内置Flyway执行一次性baseline-on-migrate=true、baseline-version=1：

1. 创建public.flyway_schema_history，登记V1 BASELINE；已有10张表不重建。
2. 执行V2__signal_advice.sql，创建public.signal_advice及约束、注释。
3. 成功迁移到v2，Java初始化成功后退出，没有启用HTTP服务、定时采集、模型或企微。

日志：task02-database-migration.log。此次没有对rag_document、rag_document_chunk、rag_knowledge_base执行任何修改。
基线选项只用于本次已完整核对的旧表接管，没有写入默认配置；新空库启动仍正常从V1迁移，禁止对其他未知库盲目baseline。

只读验证SQL：

```sql
SELECT installed_rank, version, type, description, success
FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT to_regclass('public.signal_advice');
```

## 无Docker集成测试

LocalDatabaseIT支持原生PostgreSQL。密码仅通过环境变量注入，不写文件；测试按事务回滚样例行，但序列分配值不回退。
测试入口只在STOCKSENTINEL_TEST_DB_URL存在时启用。后续优先使用独立测试数据库，不把测试指向生产库。

```powershell
$env:STOCKSENTINEL_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:STOCKSENTINEL_TEST_DB_USER = 'myrag_user'
$env:STOCKSENTINEL_TEST_DB_PASSWORD = Read-Host 'Test database password' -MaskInput
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=LocalDatabaseIT' verify
Remove-Item Env:STOCKSENTINEL_TEST_DB_PASSWORD
```

测试验证130条固定样例日线、重复导入不新增、复权序列隔离、同日价格修订和股票业务字段保留。
本次对已有本机库的测试已经用户授权；不会把样例数据当成真实行情长期保留。
测试结果和最终数据行数在task02-local-database-test.log及本阶段验收记录中核对。

## 后续启动

使用README中的原生Java/Python命令，将DB_URL和POSTGRES_USER设为上面的非敏感连接参数。密码通过Read-Host -MaskInput注入。
默认关闭真实行情、模型和通知，仍需要手动确认实际外部调用。
## 真实行情联调前的核对

LocalDatabaseIT：2项通过，0失败、0错误、0跳过。
只读复查Flyway版本1为BASELINE、版本2为SQL，两条均success=true。
stock_info、market_daily_bar、signal_event、signal_advice均0行，样例已回滚。

## 后续真实联调

用户进一步明确授权真实AKShare目录及600487前复权日线联调。股票目录写入5575条。日线请求远端断开，未写入日线。
当前stock_info有真实目录数据；market_daily_bar、signal_event、signal_advice未由本轮联调增加数据。
测试固定代码为999999，开始前检查不存在，结束后事务回滚，不修改真实600487记录。

最终复测：原生verify成功，Java17项常规测试+2项原生数据库测试全部通过。
真实股票目录：SH2318、SZ2906、BJ351，共5575；600487为亨通光电，交易所SH。
最终只读确认999999样例行0条、日线0条、信号0条、模型建议0条。

## TASK-03追加验证

IndicatorLocalDatabaseIT在同一开发库验证Java→本地Python→PostgreSQL指标链路，配合原LocalDatabaseIT共4项原生集成测试通过。
新增样例代码999998，保留原999999测试；仅在样例股票不存在时执行，每个测试事务回滚。
最新只读确认股票目录5575条、999998/999999样例股票/日线/指标0条，market_indicator_daily总行数0，Flyway仍为版本1基线及版本2。
没有对RAG表执行修改，也没有新DDL或真实行情、模型、企微调用。指标口径详见task03-indicators.md。

## TASK-04 更新

V3已成功应用，新增strategy_revision及默认策略；V1/V2不变。当前股票5575条，策略和修订各1条，日线/指标/自选股/信号/规则命中/建议均0条，临时策略schema残留0。未修改RAG表。策略集成测试使用随机独立schema并清理，需角色具备CREATE SCHEMA权限；完整原生测试命令见 [TASK-04](task04-strategy.md)。只读验收结果见task04-verification.json。

## 2026-10-20 日线重试更新

600487已成功获得东方财富真实日线：2025-01-02至2026-10-19，QFQ和NONE各432条。原AKShare接口间歇性RemoteDisconnected，成功获取后保存真实标准化快照，再经本地只读快照服务向原Java导入接口提供同一数据；Java负责校验与写入，未改供应商适配或切换入库来源。两种序列分别入库432条，共864条；重复导入后各仍432条，日期无重复。证据：docs/daily-snapshot-import-result.json；快照：local-data/daily-600487-20261020-130450。直接联网Java导入重试仍502，不能声明联网获取已稳定。未计算指标，未生成信号，未调用模型/企微；临时服务已停止。此前“日线0条”的记录为历史阶段状态。

## TASK-05更新

V4已应用，只增加通知投递字段及索引，V1/V2/V3不变。600487真实日线前复权/不复权各432条保留，指标/信号/模型建议/通知均0条，临时测试schema残留0；RAG表未修改。Java81项、Python38项通过。完整命令及状态见 [TASK-05](task05-notifications.md)，只读验收见task05-verification.json。

## TASK-06更新

V5已应用，新增收盘确认和任务明细，既有迁移不变。真实日线864条保留；公共库日历、确认、任务、指标、信号、通知及建议均0条。Java100项、Python39项通过，真实扫描未执行。任务测试仅在独立随机schema使用固定样例，详见task06-verification.json和 [TASK-06](task06-daily-scan.md)。

## 盘中扩展更新

V6已应用：新增分钟/监控/游标/冷却状态及signal_at，日线原有唯一维度保留在部分唯一索引，分钟单独去重。公共库原日线864条保留，分钟/监控/信号/通知0条，临时测试schema无残留；RAG表未修改。Java118项、Python49项通过，真实盘中未执行。见intraday-verification.json和 [盘中监控](intraday-monitoring.md)。
