# 每日股票智能表格：接入与配置

状态：接入代码和本地校验已完成，默认关闭；尚未部署或调用真实文档接口。

## 已核验的接入方式

采用 API 模式机器人授权的文档 MCP，不使用 Bot Secret 作为文档访问凭据，也不需要另建企业应用。
官方说明：<https://developer.work.weixin.qq.com/document/path/101468>。
机器人仅能编辑自己创建的文档；授权有效期目前为7天，需要到企微续期。

管理员在「工作台→智能机器人→对应机器人→编辑→可使用权限」授权文档，复制 Streamable HTTP URL，在本机环境变量 `WECOM_DOC_MCP_URL` 配置；URL是敏感凭据，不贴到聊天、配置文件或日志。

## 首版范围与边界

- 新建一份「StockSentinel 每日股票记录」智能表格，并复用默认子表、重命名默认文本列，再添加其余列。
- 沿用现有日报数据口径：公开关注股票、最多5只技术候选、最多5只缩量回踩候选、当日产生公共信号的股票。按交易日＋股票合并一行，标注来源类别。
- 表格字段：记录键、交易日、股票名称、股票代码、来源类别、建议操作、参考价格、规则依据、风险提示、信号时间、日线依据、数据覆盖。
- 只读本地快照；不补拉行情、不调用模型、不导出个人持仓、成本、止盈止损或群成员信息。
- 单独文档任务在交易日15:35起检查，每5分钟一次，最多当天一次导出；需要已有当日冻结日报才归档，避免与15:30群推送耦合。保持选股覆盖不完整、非即时行情、未回测和不自动下单说明。
- 每次外部新增最多100行；本地冻结内容、确认进度和状态。超时/断连/响应缺失等无法确认新增结果时标为UNKNOWN，禁止盲重发；没有远程记录查询能力时不宣称端到端恰好一次。
- 新建过程同样持久化阶段与文档/子表ID，结果不明不重新建表，不删除既有文档。不自动修改文档共享权限。

## 本地接口契约

| 方法与路径 | 行为 |
|---|---|
| GET `/api/v1/documents/wecom` | 开关、目标文档与初始化状态；不返回MCP URL或会话凭据 |
| POST `/api/v1/documents/wecom/setup` | 默认dryRun；显式`{"dryRun":false}`才新建表格并初始化字段；已有目标不会新建 |
| GET `/api/v1/documents/wecom/preview?tradeDate=YYYY-MM-DD` | 读取本地数据预览，不调用MCP |
| POST `/api/v1/documents/wecom/exports` | 参数tradeDate、dryRun（默认true）；false仅允许已收盘且已生成日报的开市日，不接受未来日期 |
| GET `/api/v1/documents/wecom/exports/YYYY-MM-DD` | 确认行数、总数、状态及安全错误码，无个人数据或原始错误 |
| POST `/api/v1/documents/wecom/exports/YYYY-MM-DD/retry` | 默认dryRun；显式false且仅FAILED允许，沿用冻结快照和确认进度；UNKNOWN/SENDING/SUCCESS不可重发 |

仅服务本机回环地址；不增加群助手写文档工具，外部工具名称固定白名单，不让模型选择任意URL/工具。

## 发现与依据

Postman API Discovery的Orbit检索未返回匹配的企微接口，未采用其不相关候选。已直接核验企微官方文档正文；REST智能表格接口与机器人MCP参数并不完全相同，本功能仅按机器人MCP文档实现。
MCP传输依据：<https://modelcontextprotocol.io/specification/2025-03-26/basic/transports>，支持JSON和SSE响应，初始化、会话头及固定工具调用；不执行服务端下发的额外请求。

## Windows配置步骤

在PowerShell执行以下命令。授权URL可能携带密钥，只在隐藏输入框输入；不要把实际值写在脚本、`.env`或聊天中。

```powershell
$taskDocSecret = Read-Host '粘贴企微授权页的完整 Streamable HTTP URL' -AsSecureString
$taskDocUrl = [System.Net.NetworkCredential]::new('', $taskDocSecret).Password
[Environment]::SetEnvironmentVariable('WECOM_DOC_MCP_URL', $taskDocUrl, 'User')
[Environment]::SetEnvironmentVariable('WECOM_DOC_ENABLED', 'true', 'User')
[Environment]::SetEnvironmentVariable('WECOM_DOC_SCHEDULE_ENABLED', 'true', 'User')
$taskDocUrl = $null
$taskDocSecret = $null
```

代码部署并执行V21后，先初始化（默认不修改文档）：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/documents/wecom
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/v1/documents/wecom/setup
# 确认创建时显式发送false；创建成功后状态接口给出文档链接。
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/v1/documents/wecom/setup `
  -ContentType 'application/json' -Body '{"dryRun":false}'
```

预览和当日归档（示例日期需替换成实际已生成日报的开市日）：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/documents/wecom/preview?tradeDate=2026-10-22'
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/v1/documents/wecom/exports `
  -ContentType 'application/json' -Body '{"tradeDate":"2026-10-22","dryRun":false}'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/documents/wecom/exports/2026-10-22'
```

授权过期：在企微重新授权，更新本机URL后授权重启，再对FAILED任务调用retry（正文`{"dryRun":false}`）。不修改旧冻结内容，不允许用retry重发UNKNOWN。
初始化中断后SETTING_UP、导出中断后SENDING也不自动重试，需要结合远程文档人工核对。响应没有足够新增记录ID时记UNKNOWN，不把HTTP200当作全部成功。

## 功能限制

- 当前接入智能表格（MCP文档类型10），不是新版智能文档（类型11）的页面内容块接口；适合按日期筛选股票记录。
- 使用机器人授权页提供的Streamable HTTP入口；生产只接受官方企微HTTPS域名，拒绝跳转；不兼容旧式SSE入口。
- MCP URL缺失时不能启用，默认关闭无需凭据。URL及会话ID不入库、不进状态接口或错误日志。
- 公共信号同股同日只归档最近一条及其已保存模型建议，不宣称覆盖当天所有信号明细。关注/候选按导出时本地状态冻结，非历史名单重建。
- 收盘日报只是导出的前置就绪条件，文档按归档时本地数据冻结；不是将历史群正文解析为行情，更不会强制确认当日日线。
- 未实现自动文档分享、群内链接推送、授权自动续期；不修改现有群通知。

## 校验结果

- Java全量161项通过：`.runtime/wecom-doc-accepted-unit.log`，包含真实回环HTTP的JSON/SSE、会话头、超时、不明确认、超大响应和授权错误测试，以及定时边界、默认关闭HTTP契约。
- 隔离PostgreSQL schema 40项通过：`.runtime/wecom-doc-accepted-database.log`，其中本功能17项，另覆盖既有日报9项、通知13项、旧台账迁移1项。
- 本功能验证了初始化/每日幂等、并发单次追加、部分确认进度、UNKNOWN不可重试、授权续期后安全重试、字段变更阻止写入、默认dryRun、前导零股票代码、个人持仓不导出及最近日线信号选择。
- `scripts/start_live_local.py`通过语法检查；新配置可从进程/用户/机器环境读取，Python行情进程继续排除WECOM凭据。
- 运行库只读复核仍为V20，现有持仓2条；V21仅在临时schema执行并清理。无真实文档创建、无额外模型调用、无企微测试推送。

复用命令：

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test
# 下列命令需要已配置STOCKSENTINEL_TEST_DB_URL/USER/PASSWORD，只使用隔离schema。
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml `
  failsafe:integration-test failsafe:verify `
  '-Dit.test=DocumentArchiveLocalDatabaseIT,ReportsLocalDatabaseIT,NotificationLocalDatabaseIT,TTradeMigrationLocalDatabaseIT'
```
