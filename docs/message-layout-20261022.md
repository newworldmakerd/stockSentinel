# 群提醒内容精简（2026-10-22）

## 范围与展示约定

只调整消息文案和模型的表达长度，不改变策略、行情判断、阈值、冷却、去重、频率、接收群或个人归属。
不增加模型调用，不补发历史提醒，不改写历史通知正文。

- 公共信号：股票名称代码→北京时间→不复权参考价→AI建议操作摘要→触发原因→风险与来源。
- 个人做T：所属用户和股票→观察说明及数量约束→价格和RSI→指标时间→风险。
- 个人止盈止损：所属用户和股票→触发时间→当前价/个人阈值→归属说明。
- 个人仓位：中文信号名称→仓位约束→成本差额→申报日期与快照时间。
- 保持既有日报、缩量回踩汇总与运行故障提醒。本轮不调整这些消息的内容或发送逻辑。

## 实现定位

- `backend/src/main/java/com/stocksentinel/notification/NotificationTemplate.java:29`：分段、图标、去掉策略版本、缩短模型展示、保留UTF-8字节上限和注入字符过滤。
- `backend/src/main/java/com/stocksentinel/notification/NotificationTemplate.java:62`：规则说明的长小数最多显示4位，极小非零数若会被舍入为零则原样显示；原始数值不变。
- `backend/src/main/java/com/stocksentinel/notification/MessageDisplay.java`：显示时间统一北京时间 `yyyy-MM-dd HH:mm`，不改存储时刻。
- `backend/src/main/java/com/stocksentinel/advisory/CompatibleAdvisoryClient.java:17`：模型建议80字、依据和风险各最多2条且每条50字、失效50字的表达要求，禁止复述无关技术字段；JSON结构不变。
- `backend/src/main/java/com/stocksentinel/personal/TObservationService.java:148`：做T排版及时间。
- `backend/src/main/java/com/stocksentinel/personal/PersonalPriceService.java:50`：个人价位提醒排版。
- `backend/src/main/java/com/stocksentinel/personal/PositionService.java:58`：中文信号名称与仓位排版。

群内AI部分明确标为摘要，最多展开两项风险，更多风险提示未展开；完整模型输出、信号参数、版本、规则及指标来源保留原有入库机制。模型未生成、失败、超时的原状态区分保持。

## 公共消息示意（非实际发送）

```text
📌【StockSentinel · 盘中快速拉升】
股票：示例股票 600000
🕒 2026-10-22 09:35（北京时间）
💰 参考价格：20.3 元｜不复权分钟收盘价

🤖 大模型辅助建议（摘要）
等待回踩确认，避免追涨。

📍 触发原因
五分钟涨幅达到阈值，实际2.1963%，阈值2%

⚠️ AI风险：快速拉升后可能回落；分钟行情存在延迟。
失效条件：价格跌回触发区间。

⚠️ 分钟行情可能延迟，信号不代表持仓或交易指令。
来源：本地确定性规则；来源：大模型辅助建议
仅供辅助分析，不构成收益承诺；不自动下单。
```

## 校验与运行状态

本轮只改代码并执行本机模拟/隔离schema测试，未重启运行服务、未调用真实模型、未发送企微测试消息。
Java全量149项通过，日志 `.runtime/message-layout-final-java.log`；通知、盘中、个人仓位及做T隔离数据库79项通过，日志 `.runtime/message-layout-database.log`。新增覆盖北京时间跨日、长小数展示不改原值、AI摘要及风险未展开标记、个人提醒归属和做T指标时间。
不需要新增数据库迁移；新模板须部署后用于新生成的通知，已固化的历史/待重试正文不会被覆盖。

## 13:36 部署复核

用户授权后于2026-10-22北京时间13:36重启Java/Python，消息模板及建议操作前置已部署。
Java149项全量测试通过（`.runtime/message-deploy-unit.log`）；Java/Python健康UP，企微AUTHENTICATED、失败0。
数据库维持V20，无新增迁移；持仓仍2条。未主动发送测试消息，不补发或改写既有正文。
运行目录：`.runtime/20261022-133657-830636/`；发布包、前版JAR及SHA256清单：`.runtime\release-messages-20261022-133606`。
