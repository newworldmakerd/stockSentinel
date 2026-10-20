# 数据库迁移

唯一迁移源：`backend/src/main/resources/db/migration/`，随 Java JAR 一起打包，由 Flyway 启动时执行。

- V1：整体技术方案 §4.2 的十张核心表，原样保留业务字段和唯一约束。
- V2：新增 signal_advice，关联 signal_event，保存模型建议和输入快照。
- V3：新增 strategy_revision，初始化 SWING_TREND_V1 策略及参数修订。
- V4：notify_record增加目标、冻结正文、认领令牌、租约及到期查询索引。

不要再手动执行相同建表 SQL，也不要将这些迁移挂载到 PostgreSQL initdb，以免重复建表。
本机myrag已登记V1基线并执行V2、V3、V4、V5、V6，详见docs/local-database.md。已执行迁移不得修改；新增变更使用V7及后续版本。
- V5：新增market_close_confirmation、job_execution_item及任务日期/触发方式/跳过统计。

- V6：新增分钟监控、分钟行情、游标和冷却状态；signal_event新增signal_at，日线/分钟分别使用部分唯一索引。V6后需配套使用更新后的日线ON CONFLICT谓词，不能直接回退旧JAR。
