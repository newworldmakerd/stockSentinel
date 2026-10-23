# 选股结果解释

2026-10-21。新增只读API和群助手工具，不修改筛选、排序、通知或策略参数。无新表，Flyway仍为V15。

## 群聊使用

在已配置群里@机器人：

- 山东路桥为什么入选缩量回踩？
- 亨通光电为什么没入选？哪些条件不满足？
- 四川美丰的缩量条件实际是多少，阈值是多少？

股票名称不能唯一匹配时提供六位代码。新增受控工具 `get_selection_explanation(stock_query)`，群和用户身份仍由服务端回调绑定。模型必须读取本轮工具事实，展示中文名称、代码、扫描日期、日线依据日、条件实际值和阈值。

## 查询接口

```http
GET /api/v1/screening/explanations/000498
GET /api/v1/screening/explanations/600487?tradeDate=2026-10-21
```

省略tradeDate使用上海时区今天；不接受未来日期。查询已在本地目录中的股票，不要求先关注。股票格式错误400、无目录记录404；缺少扫描、逐股任务待处理或数据不足返回200及明确业务状态。

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/screening/explanations/000498' | ConvertTo-Json -Depth 12
```

返回核心字段：

- `symbol/name/scanDate/dataDate/scanStatus/coverage`：股票与扫描依据、实际覆盖。
- `status/code/eligibility`：逐股处理状态、排除或失败原因、已存20日均成交额与1亿元门槛。
- `swing`：扫描当时保存的日线买入观察规则快照，每项含passed、ruleCode、observedValue、thresholdValue、explanation。
- `pullback`：保存的回踩候选快照，或明确标注当前本地历史只读复算。条件合格与候选已经保存必须区分。
- `rank/inTopFive/minuteMonitoringEligible`：按依据日当前未转强候选、20日均成交额及代码排序；前5名推荐与前poolLimit分钟监控是不同上限。排名不代表历史发送记录，`currentDigestUsesThisBasis`说明是否为当前10分钟候选汇总所用依据日。
- `triggeredAt`：已经转强则不再解释为待确认观察；没有触发记录不补造实时突破。

## 数据与边界

1. 日线买入观察直接读取 `market_screen_item.details`，缩量回踩已入池则读取 `pullback_candidate.details`，原始快照优先，不调用模型重新决定合格与否。
2. 未入池股票仅在扫描已完成该股技术评估时，复用 `StrategyContextReader` 的来源、复权、对齐、历史及指标新鲜度校验；按原筛选一年窗口读取QFQ日线，调用原 `PullbackRules.evaluate`，使用当前回踩参数并明确标注复算。不会取行情、计算指标或写入候选。
3. 回调天数不在范围时，保留原规则短路，只解释已计算的天数，其他回踩条件显示未评估。数据不足不作为某条件失败。
4. `EXCLUDED/FAILED/PENDING/NOT_STARTED/NOT_IN_SCAN`明确展示，不能宣称所有技术条件不符合。报价缺失不等于停牌，本地历史不足不等于新股上市时间不足。
5. 不能用该接口证明盈利、基本面质量、当前实时价格或实际成交。查询失败只返回安全状态，不返回原始异常或凭据。

## 修改与验证

实现位于 `report/SelectionExplanationMapper/Service/Controller`；群工具接入位于 `GroupAssistantTools` 和 `DeepSeekGroupAssistantClient`。使用原有本机模拟HTTP模型、独立PostgreSQL测试schema及模拟机器人，未为测试调用真实行情、DeepSeek或企微。

验证涵盖：原始快照、买入观察不匹配但回踩匹配、排名第6未进前5、已转强、回调天数短路、复算合格但未保存、缺失/过期指标、快照损坏、未扫描、排除、未来日期、群白名单和伪造身份拒绝、查询不新增关注/候选，以及实际本地HTTP工具结果回传模型。


最终验证：127项普通测试、16项相关本机数据库测试全部通过，编译打包成功。测试日志 `.runtime/selection-explanation-verified.log`、`.runtime/selection-explanation-package.log`。普通测试命令沿用README；本次相关数据库命令：

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=ReportsLocalDatabaseIT,GroupAssistantLocalDatabaseIT' '-Dspring.test.context.cache.maxSize=2' '-Dspring.datasource.hikari.maximum-pool-size=3' test test-compile failsafe:integration-test failsafe:verify
```

测试DB通过STOCKSENTINEL_TEST_DB_URL/USER/PASSWORD注入，使用自动创建及清理的独立schema。

14:09已部署，Java健康UP、机器人AUTHENTICATED，Python原进程保持。运行目录 `.runtime/20261021-140848-345363`，backend PID 81072。本地只读API验收：

- 山东路桥000498：2026-10-20日线，保存的回踩候选快照；前20日涨幅32.5301%、回调2天、回撤6.4327%、量比0.5674，六项条件均满足；日线买入观察未全部满足，两者明确区分。
- 四川美丰000731：本地只读复算，前20日涨幅-2.5840%、回撤12.3501%、MA20未向上、收盘低于MA20；缩量0.6694虽满足，但其他条件失败。
- 亨通光电600487：连续回调0天，不满足2～5天；保留原规则短路，其余回踩指标未计算。

实际数据结果仅为当时查询快照，记录在 `.runtime/selection-explanation-live-readonly.json`。未额外调用真实模型或发送群测试消息；群自然语言完整链路使用本地模拟模型验证。
