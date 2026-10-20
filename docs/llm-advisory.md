# 规则后置大模型分析模块

## 背景与目标

本地规则产生并保存信号后，调用 OpenAI 兼容模型给出条件式交易建议、依据、风险和失效条件。
模型不负责决定规则是否命中，结果单独落表，不修改 signal_event。

## 现状与影响点

- `backend/src/main/java/com/stocksentinel/advisory/SignalAdvisoryService.java`：接收已有信号 ID，从数据库取信号及规则明细，不接收任意外部提示词。
- `CompatibleAdvisoryClient.java`：Chat Completions 请求、JSON 输出解析和完整性验证。
- `AdviceStore.java`：MyBatis 参数绑定及 JSONB 写入。
- `backend/src/main/resources/db/migration/V2__signal_advice.sql`：独立分析表及唯一约束。
- TASK-04已完成本地规则引擎，StrategyAdvisoryListener在新信号事务提交后调用分析服务，并暂停事务上下文；重复信号不调用。定时扫描与持久任务恢复仍待TASK-06。

## 使用与数据流

服务端在规则事务提交后调用：

```java
SignalAdvisoryService.Status status = advisoryService.analyze(persistedSignalId);
```

也可通过 `POST /api/v1/signals/{id}/advice` 手动触发；不支持由请求上传虚构行情。
返回 SUCCESS、ALREADY_ANALYZED、DISABLED、NOT_FOUND 或 FAILED。
默认关闭返回 HTTP 503，不访问数据库、不调用模型；信号缺失返回 404；模型分析失败返回 502。
成功和已分析返回 200。结果保存在 signal_advice，当前查询使用 SQL：

```sql
SELECT signal_id, provider, model, prompt_version, status, advice, error_code, created_at
FROM signal_advice
WHERE signal_id = :signal_id
ORDER BY created_at DESC;
```

环境变量：LLM_ENABLED、LLM_BASE_URL（包含供应商版本路径）、LLM_MODEL、LLM_API_KEY、LLM_TIMEOUT_SECONDS。
密钥只能通过 shell/secret manager 注入，不保存进项目文件。URL 必须 HTTPS 且不含凭据、query 或 fragment。
请求基于 [OpenAI 官方 Chat Completions 格式](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)，供应商须支持 messages、max_tokens、response_format=json_object。

## 校验与失败处理

- 输入信号必须存在；提示词将输入视为数据，要求只引用已有信息，缺失信息明确说明。
- 最多 60000 字符输入、1200 输出 token；连接超时 5 秒，读取超时默认 30 秒，可配置 1–120 秒。
- 仅接受 finish_reason=stop，拒绝截断和空内容，验证建议、依据、风险和失效条件。
- HTTP错误、无效JSON和输出校验失败均记录 MODEL_ANALYSIS_FAILED，不保存异常响应、地址、密钥或堆栈。
- 不自动重试，以免重复付费；同一信号+模型+提示词版本已存在时不再调用，包括失败记录。
- 当前单实例以 synchronized 串行执行及数据库唯一约束去重；多实例并发费用控制须增加数据库任务认领后再部署。
- 模型请求期间不持有数据库事务。数据库故障会正常向上报错，不能伪装成成功。
- 模型自然语言可能存在错误；程序保证结构和来源边界，不能保证建议内容事实完全正确。

## 待确认与后续接入

供应商、模型和API地址由用户配置；本次未使用真实密钥、未发生真实模型费用。
TASK-04已将行情、复权类型、指标版本、数据完整性及规则参数写进 signal_event.details 供模型引用。提交后监听器为进程内同步执行，未实现持久队列或崩溃恢复。
需要失败重试、多实例、建议查询API、持仓信息或消息推送时单独开发，不擅自添加。

## 验证与回滚

测试使用模拟客户端覆盖开关、重复、缺失信号、成功、失败、JSON解析及截断。
将 LLM_ENABLED=false 即可停止模型调用，已有规则信号和建议保留。
本机已应用V2、V3；后续调整应新增迁移，不删除已有业务信号。
## DeepSeek连接更新

2026-10-20已完成真实鉴权和最小JSON生成测试，deepseek-flash返回成功，共59 tokens；没有生成股票建议或发企微。项目支持DEEPSEEK_API_KEY作为LLM_API_KEY后备，自动调用仍默认关闭。配置与验证边界见 [DeepSeek连接记录](deepseek-connection.md)。
