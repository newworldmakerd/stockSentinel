# P0优化接口与工具契约

版本：2026-10-21，OPT-00—03候选版本；是否已部署以[验收记录](optimization-progress.md)为准。所有HTTP示例为本机接口，无凭据；不得把本文示例当作真实用户成交。

## 1. 扫描阶段只读诊断

`GET /api/v1/monitoring/jobs/{id}/steps`

- id为正整数；不存在返回404 `SCAN_JOB_NOT_FOUND`。
- 返回按记录顺序排列的数组，最多500项；旧任务未记录阶段时为空。当前不提供超过500项的分页，不能据响应认定超大股票池已完整返回。
- 字段白名单：`job_id,symbol,stage,status,code,source,data_observed_at,data_basis_at,started_at,finished_at,elapsed_ms,category`。
- `elapsed_ms`使用单调时钟；墙上时间不作为耗时计算依据。时间缺失保持缺失，不拿排队时间冒充行情时间。
- `status`为SUCCESS/SKIPPED/FAILED；category区分来源故障、数据不足/过期、时段外、无新事件等。无新事件不自动解释为规则不满足，具体去重和条件看所属策略状态。

```http
GET http://127.0.0.1:8080/api/v1/monitoring/jobs/77/steps
```

## 2. 通知追溯

`GET /api/v1/monitoring/notifications/{id}/trace`

- 不存在返回404 `NOTIFICATION_NOT_FOUND`。
- 返回排队、发送确认、信号记录、指标采集和依据、评估时间，以及策略版本、任务关联。
- `scanJobs`对应已有公共信号关联，做T使用`observation_job_id`。老通知无关联时为空/缺失，不按日期猜测任务。
- 不返回群ID、用户ID、正文、原始幂等键、成本或股数；SQL和控制器双重字段白名单。数据库错误文本不在安全码范围时返回`DELIVERY_FAILURE`。
- `UNKNOWN`单独显示，发送成功不代表已读，不能触发盲重发。

## 3. 原状态接口增量

`GET /api/v1/monitoring/status` 保留旧字段，增加：

- `tObservationEnabled`。
- `wecomDiagnostics`：状态、累计连接尝试次数、连续失败次数、距下次探测秒数、冷却上限和是否需要人工动作。
- 新连接状态 `RECONNECT_COOLDOWN` 表示仍会冷却探测；`AUTH_REJECTED`和`TAKEN_OVER`需要人工处理。

## 4. Python RSI历史契约

沿用 `GET /internal/indicators/t-rsi?symbol=000731`，RSI14算法、时段、阈值不变；主源改为有明确预算的东方财富兼容适配，后备为新浪5分钟原始线＋独立qfq因子整窗换算。

- source允许原`AKSHARE_EASTMONEY_5M`兼容值，以及`EASTMONEY_5M`、`SINA_QFQ_5M`。adjustment固定QFQ。
- 原响应字段保持；不同来源的真实报价和RSI可能有差异，不能宣称数值完全一致。
- 成功按股票/5分钟槽缓存；失败短时退避30秒；每股每来源连续3次失败，熔断120秒后探测。全局采集互斥忙时返回503 `RSI_COLLECTOR_BUSY`，不无限排队。
- 数据质量或预热不足返回422，来源不可用返回502；失败响应含有限枚举头 `X-RSI-Primary-Error`、`X-RSI-Backup-Error`，退避含`Retry-After`。Java只读取允许的安全码，忽略任意错误正文。
- 来源尝试的安全码与耗时记录在Python `t_rsi_source_result`日志，选中来源与依据时间进入Java扫描阶段记录。
- 后备必须通过身份、因子有效日期、锚定因子和窗口质量检查；不把SDK静默返回的未复权数据当qfq，不执行远程JavaScript。

## 5. 本人手工成交工具

身份只取企微回调，所有写工具每条消息最多执行一次。数字、价格和编号必须对应本条原文；模型不能指定user/group。

| 工具 | 参数 | 语义 |
|---|---|---|
| `record_t_fill` | stock_query, side(BUY/SELL), quantity, price | 追加一笔明确实际成交；返回eventId，重复消息不累计 |
| `list_t_trades` | stock_query, trade_date可选, offset可选 | 本人本群按日期查询审计及有效累计数量/金额，每页20条 |
| `correct_t_fill` | event_id, quantity, price | 更正本人本群当日原始成交，保留原记录和rootEventId |
| `cancel_t_fill` | event_id | 追加撤销台账事件，绝不撤销券商订单 |
| `resume_t_observation` | stock_query | 仓位/可卖与原基准及台账核对一致，明确确认后恢复；不重置去重 |

格式：

```text
记录四川美丰做T卖出成交100股，成交价8元
更正做T成交123为50股，成交价8元
撤销做T成交123
确认四川美丰仓位基准并恢复做T观察
```

123为示例，必须使用原始成交编号。成交、更正与恢复保留10:00—15:00的时段约束；分笔数量按有效事件累计，均价按数量加权，不能按笔数平均。首笔仍需相应观察，后续回补不能扩大原周期敞口；未核对的超限申报会拒绝，并不代表真实交易未发生。

公开HTTP诊断不能读取上述私人台账。台账记录不是券商同步，不自动修改个人仓位或执行交易；历史V17汇总只标记`LEGACY_AGGREGATE`，不虚构分笔。

## 6. 校验证据

HTTP白名单与404由 `ApplicationHealthTest.operationalDiagnosticsHaveHttpContractsAndDropPrivateFields` 验证；数据库真实SQL、四种时间与隐私字段由 `TObservationLocalDatabaseIT` 验证；来源使用真实本机HTTP模拟，企微使用本机WebSocket帧模拟。旧数据升级由 `TTradeMigrationLocalDatabaseIT` 先创建V17再迁移验证。测试不连接真实行情、模型或企微。
