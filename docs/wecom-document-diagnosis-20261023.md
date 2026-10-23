# 企微智能表格未生效：只读排查

结论：当前模块没有启用、没有配置文档授权入口，也没有初始化目标表格。未发现已经尝试写入后失败的任务。企微群机器人正常与文档功能是否启用是独立的两件事。

## 实际证据

1. 当前运行版本是扫描可靠性V23。`GET /api/v1/documents/wecom`返回`{"status":"DISABLED","enabled":false}`。
2. 进程、用户、机器三个环境范围的`WECOM_DOC_ENABLED`、`WECOM_DOC_SCHEDULE_ENABLED`、`WECOM_DOC_MCP_URL`均未配置。检查仅输出存在性及非敏感布尔开关，没有读取到或输出授权URL。
3. 当前运行启动器`.runtime/release-scan-coverage-20261023-120304/start_backend.py:69`在读取环境后，明确覆盖两个文档开关为`false`。这是当时部署要求“文档模块保持关闭”的残留部署设置；仅在系统中设置true仍会被此启动器覆盖。
4. 运行库已包含V21文档表。目标`target_key=daily-stocks`的状态为`NEW`，`doc_id/sheet_id`均为空，`last_error`为空；`wecom_document_export`没有任何记录。
5. 运行库已有10月20日至23日的冻结日报。因此当前阻断并非“从来没有日报”，而是功能关闭、缺凭据与初始化未完成。具体日期的后续导出仍按原收盘/开市日校验。
6. 当前启动后的日志中，文档调度与MCP错误标识计数为0；结合关闭状态和数据库记录，说明并未进入文档创建/导出流程，不能据此推断真实企微接口可用或授权是否过期。

## 代码依据

- `backend/src/main/java/com/stocksentinel/document/DocumentSettings.java:14`：仅enabled时校验官方HTTPS授权入口。
- `backend/src/main/java/com/stocksentinel/document/DocumentConfiguration.java:15`：配置类只在`wecom-doc.enabled=true`时创建文档客户端与归档服务。
- `backend/src/main/java/com/stocksentinel/document/DocumentArchiveService.java:22`：初始化才调用`create_doc`创建机器人自己的智能表格。
- `backend/src/main/resources/application.yml`：文档开关默认false，MCP URL默认空，与Bot ID/Secret相互独立。
- `backend/src/main/resources/db/migration/V21__wecom_document_archive.sql`：目标初始化为NEW；创建和导出状态独立持久化。

## 后续必要步骤

1. 用户在本机环境变量配置企微机器人文档授权页提供的`WECOM_DOC_MCP_URL`，不要发到聊天或写入仓库。配置方法见[文档接入](wecom-stock-document.md)。仅在企微点授权不等于服务已拿到授权入口。
2. 确认将文档模块启用后，调整实际使用的启动器，不再强制覆盖两个开关为false；在重启前校验URL存在性及官方域名，避免缺配置影响Java启动。
3. 获得重启及真实文档创建/写入授权后，重启Java，先查看状态和初始化预览，再初始化一份智能表格。返回READY和文档链接才表示创建成功。
4. 预览公开股票记录，选择已收盘且已有冻结日报的交易日做首次导出，核对确认行数，再开启交易日15:35起的自动归档。
5. 该模块目前是“智能表格”，不会自动分享文档或往群里发送文档链接。没有群消息本身不能证明文档写入失败；应以状态和确认行数为准。

本次只读排查，没有改开关、重启、创建表格、写企微文档或补跑导出；没有使用机器人Secret代替文档授权。

复核SQL（只读）：

```sql
SELECT target_key, status, doc_id IS NOT NULL AS has_document,
       sheet_id IS NOT NULL AS has_sheet, last_error
FROM public.wecom_document_target;

SELECT trade_date, status, total_rows, confirmed_rows, last_error
FROM public.wecom_document_export
ORDER BY trade_date DESC LIMIT 5;
```
