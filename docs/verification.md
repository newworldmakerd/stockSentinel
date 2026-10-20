# 验证记录

> 最新盘中扩展：Java70项常规+48项本机集成、Python49项通过；V6已应用，原日线864条保留。新增分钟到本地企微ACK闭环模拟验收通过，真实盘中扫描未开启。见 [盘中监控](intraday-monitoring.md)、intraday-verification.json。

> 最新TASK-06：Java64项常规+36项本机集成、Python39项通过；V5已应用。固定行情通过真实HTTP与Python计算到本地WebSocket ACK的闭环通过；未执行真实扫描。见 [TASK-06](task06-daily-scan.md) 与 task06-verification.json。

> 最新TASK-05：Java55项常规+26项本机集成测试、Python38项通过；V4已应用。真实企微未连接。日线864条保留、测试schema残留0。详见 [TASK-05](task05-notifications.md) 与 task05-verification.json。

> 最新TASK-04：Java42项常规+14项本机集成测试、Python38项全部通过；V3已应用，临时schema无残留。未调用真实行情/模型/企微。见 [TASK-04](task04-strategy.md) 和 task04-verification.json。

> 最新TASK-03：Java26项常规测试+4项本机集成测试、Python38项通过，指标样例已回滚，无新迁移。详见task03-indicators.md。

> 下文是TASK-01阶段快照。最新TASK-02验证：Java17项+原生数据库2项、Python18项通过；本机Flyway迁移已成功。详见task02-market.md与local-database.md。

日期：2026-10-20（Asia/Shanghai）。范围：TASK-01 初始化 + 用户追加的大模型分析模块。

## 已执行与结果

| 检查 | 结果 | 证据 / 限制 |
|---|---|---|
| JDK / Maven | 21.0.10 / 3.8.8 可用 | 最终 Java package 为 BUILD SUCCESS |
| Java 编译、JUnit5、打包 | 10 tests，0 failures，0 errors，0 skipped | backend-build.log；backend/target/surefire-reports |
| Java 真实HTTP健康检查 | 通过 | ApplicationHealthTest 启动随机端口；/api/v1/health 和 /actuator/health 返回200；测试隔离数据库 |
| 大模型模拟测试 | 通过 | 开关关闭、未找到信号、重复分析、成功、失败、输出格式和截断；未调用真实模型 |
| Python | 随附3.12.14，项目.venv | 不依赖系统python命令，不更改全局环境 |
| pytest | 4 passed | python-test.log；健康接口与日线契约/异常价格/负成交量 |
| Python字节码编译 | 通过 | compileall app + tests |
| Python依赖一致性 | 通过 | pip check: No broken requirements found |
| AKShare导入 | 1.19.1 成功 | 仅导入，不获取真实行情 |
| Python真实HTTP健康检查 | 通过 | 临时Uvicorn进程，loopback随机端口，GET /health返回200；结束后已停止 |
| PostgreSQL工具 | psql 18.3可用 | 未连接或修改已安装数据库 |
| PostgreSQL集成测试 | 阻断，未通过 | Testcontainers: Could not find a valid Docker environment；database-test.log |
| Compose镜像/启动/迁移 | 未执行 | Docker命令及守护进程不可用 |

最终Java依赖采用 Spring Boot 3.5.16、MyBatis-Plus 3.5.9、Flyway 11.20.2，Flyway PostgreSQL模块对应18版本。
数据库迁移只有脚本和集成测试工程完成，尚不能认定真实数据库迁移验收通过。

## 关键实现位置

- Java进程探活：backend/src/main/java/com/stocksentinel/common/HealthController.java:8。
- 规则信号后置模型分析：backend/src/main/java/com/stocksentinel/advisory/SignalAdvisoryService.java:9。
- 兼容模型客户端：backend/src/main/java/com/stocksentinel/advisory/CompatibleAdvisoryClient.java:10。
- 模型分析手动入口：backend/src/main/java/com/stocksentinel/advisory/AdvisoryController.java:11。
- V1核心表：backend/src/main/resources/db/migration/V1__initial_schema.sql:1。
- V2模型建议：backend/src/main/resources/db/migration/V2__signal_advice.sql:1。
- Python行情契约：quant-service/app/providers/base.py:14。

## 未完成项

- 实际数据库迁移、数据库写入和Compose端到端验收，需安装Docker后运行README中的verify及启动命令。
- TASK-02至06的日线数据适配、指标计算、自选股/策略引擎、交易日扫描和通知。
- 规则引擎尚未生成信号，模型模块目前提供已落库信号分析入口，自动后置串联待规则引擎实现。
- 真实模型供应商地址/模型/兼容性/额度验证，当前开关关闭；未发生外部模型调用费用。
- 企微智能机器人Bot ID + Secret长连接仅预留配置，未连接、未发送消息。真实凭据未写入任何工程文件。

## 已知非阻断警告

- Mockito在JDK21使用动态代理加载提示，测试通过。
- 当前FastAPI/Starlette TestClient对httpx有弃用提示，4项测试通过，未扩大范围调整依赖架构。

## 重现

完整编译、测试与运行命令见README.md。恢复Docker环境后应重新执行mvn verify，验证迁移及唯一约束，不能将本次隔离数据库的健康检查视为数据库验收。