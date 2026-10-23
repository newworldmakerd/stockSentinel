# 企微群 chatid 发现模式

## Windows终端写法

先确认终端类型。同一个机器人正在连接主服务时，先暂停该Java服务，发现结束后再恢复；发现工具不会替你停止其他进程。

CMD（命令提示符）使用双引号，不加PowerShell的调用符号：

```cmd
"C:\Program Files\Java\jdk-21.0.10\bin\java.exe" -jar "D:\lyh\code\AIDemo\stockSentinel\backend\target\stocksentinel-0.1.0-SNAPSHOT.jar" --discover-wecom-chatid
```

PowerShell使用调用符号：

```powershell
& 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe' -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --discover-wecom-chatid
```

两种写法均要求当前终端的环境中已有WECOM_BOT_ID和WECOM_BOT_SECRET；不把凭据直接写进命令或文档。等待鉴权成功后，在目标新群中@机器人发消息，控制台显示chatid后退出。

已补齐独立命令行入口 `--discover-wecom-chatid`，用于第一次配置群通知时获取目标群chatid。无需预先配置WECOM_CHAT_ID，不启动Spring、数据库、Flyway、Python、模型或通知worker，无数据库变更。

## 操作步骤

真实连接必须先获授权。同一机器人创建新长连接会接管原连接，运行前先停止使用该机器人的其他实例。

1. 将智能机器人加入目标企微群。
2. 在项目根目录的PowerShell 7中执行下面命令。Bot ID和Secret仅保留在环境变量，不写入任何文件。
3. 看到“鉴权成功”后，在目标群 @机器人 发送任意一条消息。
4. 控制台显示第一个有效群chatid后自动退出。将该值配置到WECOM_CHAT_ID，再按TASK-05文档启用通知。

```powershell
$env:WECOM_BOT_ID = Read-Host 'Bot ID' -MaskInput
$env:WECOM_BOT_SECRET = Read-Host 'Bot Secret' -MaskInput
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --discover-wecom-chatid
# 将输出的实际群chatid填到下一行
$env:WECOM_CHAT_ID = '实际群chatid'
```

无需开启WECOM_ENABLED；即使环境中已有该开关，发现入口也绕过普通服务启动。发现模式禁止发送通知、欢迎语和自动回复，只执行鉴权、心跳及群chatid提取。只接受当前Bot ID的aibot_msg_callback且chattype=group，忽略单聊、缺失/非法chatid和其他机器人的消息，不记录正文、完整回调或Secret。chatid仅显示在当前控制台，不自动写配置文件。

等待窗口约180秒，获得第一个有效群chatid即退出；同时多个群有消息时请核对目标群。Ctrl+C可结束并清理连接。未收到消息时确认机器人已入群，并在鉴权成功后重新 @机器人。

| 退出码 | 含义 |
|---|---|
| 0 | 已提取群chatid |
| 1 | 发现模式异常，未输出原始异常信息 |
| 2 | 缺少Bot ID或Secret环境变量 |
| 3 | 鉴权拒绝、连接被接管或连接尝试耗尽 |
| 4 | 等待超时 |
| 130 | 等待线程被中断 |

鉴权失败时检查机器人后台配置；不要将Secret或完整回调粘贴到聊天或日志。

## 验证

2026-10-20：常规Java59项测试通过并完成JAR打包，新增4项发现模式测试，覆盖本地真实WebSocket群消息回调、分片、单聊/错误机器人/非法chatid过滤、禁止发送、认证拒绝及缺少凭据入口。原有26项本机集成测试另行回归，结果见chatid-discovery-integration.log。日志：chatid-discovery-tests.log。

已用可执行JAR实际运行缺少凭据的分支，确认退出码2，没有启动Spring或数据库。以上均为本地模拟，不是企微真实连接验收；本次没有连接真实企微、发送消息或读取真实群消息。

实现位置：

- backend/src/main/java/com/stocksentinel/StockSentinelApplication.java：在Spring启动前分派独立入口。
- backend/src/main/java/com/stocksentinel/notification/ChatIdDiscovery.java：环境变量、等待、输出和退出清理。
- backend/src/main/java/com/stocksentinel/notification/SmartBotClient.java：接收专用模式及群chatid回调，send方法直接拒绝。
- backend/src/test/java/com/stocksentinel/SmartBotProtocolTest.java：loopback WebSocket模拟验收。
