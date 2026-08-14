# Hermes Friends (Hermes 好友)

微信式的 Hermes Agent 手机客户端 —— 把 Hermes 的会话变成"好友列表",支持多服务器、项目分组、附件收发。

> 本 App 用 Hermes Agent 开发,回馈 Hermes 社区。核心逻辑完全在服务器端(Hermes),App 只做桥接,不含任何 AI 内核。

## 特性

- 📱 **微信式操作**:好友 = 会话窗口,添加/删除好友 = 创建/删除会话
- 🖥 **多服务器**:一个 App 连接多个 Hermes 内核,服务器可自定义名称、独立账号
- 📂 **三层折叠**:服务器 → 项目组(按工作目录) → 会话
- 💬 **完整聊天**:收发消息、思考内容显示、附件下载/打开
- 📷 **媒体发送**:拍照、相册选图、发送文件
- 🔒 **隐私安全**:不含任何服务器凭据,地址/账号/密码由用户自己填写

## 截图

(待补充)

## 使用前提

你需要一个运行中的 **Hermes Agent 服务器**(`hermes serve` / dashboard,端口 9119):
- 官方文档: https://hermes-agent.nousresearch.com/docs
- 出门在外时,手机需能访问到服务器(如 WireGuard 隧道回内网)

## 构建

```bash
# 需要 JDK 17 + Android SDK
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 打开 App → 添加服务器 → 填 Hermes 服务器地址 + 账号密码
2. 服务器列表默认全折叠,点击展开
3. 长按服务器:重命名/删除;长按会话:删除
4. "+" 按钮:添加服务器 / 创建新对话到指定项目组

## 架构

```
手机 App (纯 UI, 微信式操作)
   │  HTTPS + WebSocket
   ▼
Hermes serve (你的服务器, 端口 9119)
   │
   ▼
state.db (会话/消息/记忆 唯一真源)
```

App 与 Hermes 的通信协议:
- REST: `/api/sessions`(列表)、`/api/sessions/{id}/messages`(消息)、`/api/files/*`(附件)
- WebSocket: `/api/ws`(JSON-RPC: session.create / session.resume / prompt.submit / image.attach)

## License

MIT © onlyxn
