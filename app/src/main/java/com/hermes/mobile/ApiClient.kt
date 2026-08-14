package com.hermes.mobile

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HermesSession(
    val id: String,
    val displayName: String,
    val source: String,
    val messageCount: Int,
    val lastActivity: Long,
    val preview: String,
    val cwd: String? = null,
    val serverId: String = ""
)

data class HermesMessage(
    val id: Long,
    val role: String,
    val content: String,
    val timestamp: Long
)

class ApiClient(private val context: Context, val server: ServerConfig) {

    // 每个服务器独立的 prefs 命名空间, cookie 互不干扰
    private val prefs: SharedPreferences =
        context.getSharedPreferences("server_${server.id}", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = server.baseUrl.trimEnd('/')
        set(value) {
            server.baseUrl = value.trimEnd('/')
        }

    val isLoggedIn: Boolean
        get() = server.loggedIn && server.cookie.isNotEmpty()

    fun logout() {
        server.cookie = ""
        server.loggedIn = false
        prefs.edit().clear().apply()
        // 持久化到服务器列表
        ServerStore(context).update(server)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val raw = server.cookie
                if (raw.isEmpty()) return emptyList()
                return raw.split(";").mapNotNull { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size != 2) return@mapNotNull null
                    try {
                        Cookie.Builder()
                            .domain(url.host)
                            .path("/")
                            .name(kv[0].trim())
                            .value(kv[1].trim().trim('"'))
                            .build()
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val all = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                if (all.isNotEmpty()) {
                    server.cookie = all
                    server.loggedIn = true
                    prefs.edit().putString("cookie_backup", all).apply()
                    ServerStore(context).update(server)
                }
            }
        })
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl + path).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            if (resp.code == 401) {
                server.cookie = ""
                server.loggedIn = false
                ServerStore(context).update(server)
                throw UnauthorizedException()
            }
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
            JSONObject(body)
        }
    }

    private suspend fun post(path: String, json: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(json.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            if (resp.code == 401) {
                server.cookie = ""
                server.loggedIn = false
                ServerStore(context).update(server)
                throw UnauthorizedException()
            }
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
            JSONObject(body)
        }
    }

    private suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl + path).delete().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401) {
                server.cookie = ""
                server.loggedIn = false
                ServerStore(context).update(server)
                throw UnauthorizedException()
            }
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
        }
    }

    class UnauthorizedException : RuntimeException("登录已过期")

    /** 验证当前保存的 cookie 是否仍然有效(调用 /api/auth/me) */
    suspend fun validateSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(baseUrl + "/api/auth/me").get().build()
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun login(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("provider", "basic")
            put("username", username)
            put("password", password)
        }
        val req = Request.Builder()
            .url(baseUrl + "/auth/password-login")
            .post(json.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            val ok = resp.isSuccessful && JSONObject(body).optBoolean("ok", false)
            if (ok) {
                server.loggedIn = true
                ServerStore(context).update(server)
            }
            ok
        }
    }

    suspend fun listSessions(): List<HermesSession> {
        // ③ limit=100: 默认只返回 20 条,76 个会话会截断!
        // ⑦ 用 sources 参数从源头过滤 cron(比客户端过滤更彻底)
        val obj = get("/api/sessions?limit=100&archived=exclude")
        val arr = obj.getJSONArray("sessions")
        val list = mutableListOf<HermesSession>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val source = s.optString("source")
            // ⑦ 过滤 cron 后台会话,只显示"人的会话"
            if (source == "cron") continue
            // ③ JSON null 修复: optString 会把 null 读成 "null" 字符串,必须先判空
            val displayName = if (s.isNull("display_name")) "" else s.optString("display_name")
            val title = if (s.isNull("title")) "" else s.optString("title")
            val sessionKey = if (s.isNull("session_key")) "" else s.optString("session_key")
            list.add(
                HermesSession(
                    id = s.optString("id"),
                    // ③ 与桌面端一致: 优先 title,其次 display_name,最后 session_key
                    displayName = title.ifEmpty {
                        displayName.ifEmpty { sessionKey.substringAfterLast(":").ifEmpty { "未命名" } }
                    },
                    source = source,
                    messageCount = s.optInt("message_count", 0),
                    lastActivity = (s.optDouble("last_activity_at", 0.0) * 1000).toLong(),
                    preview = "",
                    cwd = if (s.isNull("cwd")) null else s.optString("cwd")
                )
            )
        }
        return list.sortedByDescending { it.lastActivity }
    }

    suspend fun getMessages(sessionId: String): List<HermesMessage> {
        return try {
            val obj = get("/api/sessions/$sessionId/messages")
            val arr = obj.getJSONArray("messages")
            val list = mutableListOf<HermesMessage>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val role = m.optString("role")
                // 过滤 tool 消息,只显示 user/assistant
                if (role == "tool" || role == "session_meta") continue
                // 过滤 [IMPORTANT: 开头的系统通知(构建日志/进程完成等)
                val rawContent = if (m.isNull("content")) "" else m.optString("content")
                if (rawContent.trimStart().startsWith("[IMPORTANT:")) continue
                // JSON null 修复: 先判空再取,绝不直接 optString(会把 null 读成 "null")
                val content = rawContent.ifEmpty {
                    val apiContent = if (m.isNull("api_content")) "" else m.optString("api_content")
                    apiContent.ifEmpty {
                        // 思考消息: content 为空 → 依次用 reasoning / reasoning_content 兜底
                        val reasoning = if (m.isNull("reasoning")) "" else m.optString("reasoning")
                        reasoning.ifEmpty {
                            val rc = if (m.isNull("reasoning_content")) "" else m.optString("reasoning_content")
                            rc.trim()
                        }
                    }
                }
                // 内容全空的消息(纯思考残留/空壳)直接跳过,不显示
                if (content.isBlank()) continue
                list.add(
                    HermesMessage(
                        id = m.optLong("id"),
                        role = role,
                        content = content,
                        timestamp = (m.optDouble("timestamp", 0.0) * 1000).toLong()
                    )
                )
            }
            list
        } catch (e: Exception) {
            // 404 = 会话还没在服务器落库(新建未发消息),静默返回空
            emptyList()
        }
    }

    /** ⑤ 取会话最后一条可见消息(user/assistant),用于列表缩略 */
    suspend fun getLastMessage(sessionId: String): String = withContext(Dispatchers.IO) {
        try {
            val list = getMessages(sessionId)
            list.lastOrNull()?.content?.trim()?.take(80) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 📷 上传图片到服务器,返回服务器可见路径 */
    suspend fun uploadImage(dataUrl: String, filename: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("data_url", dataUrl)
                put("filename", filename)
            }
            val resp = post("/api/chat/image-upload", json)
            resp.optString("path").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** 📁 上传文件到服务器指定路径 */
    suspend fun uploadFile(serverPath: String, dataUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("path", serverPath)
                put("data_url", dataUrl)
                put("overwrite", true)
            }
            val resp = post("/api/files/upload", json)
            resp.optString("path").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** 🖼 发送图片: 上传后 attach 到会话,再带文字提交 */
    suspend fun sendImage(sessionId: String, dataUrl: String, filename: String, caption: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val imgPath = uploadImage(dataUrl, filename) ?: return@withContext false
                wsRpc(sessionId, listOf(
                    mapOf("method" to "image.attach", "params" to mapOf("session_id" to sessionId, "path" to imgPath)),
                    mapOf("method" to "prompt.submit", "params" to mapOf("session_id" to sessionId, "text" to caption.ifEmpty { "[图片: $filename]" }))
                ))
            } catch (e: Exception) {
                false
            }
        }

    /** 🧩 通用 WebSocket RPC 调用(按顺序发送多个方法,等最后一个响应)
     *  skipResume=true: 跳过 session.resume(用于刚创建尚未落库的会话,直接用内存短 id) */
    private suspend fun wsRpc(
        sessionId: String,
        calls: List<Map<String, Any>>,
        skipResume: Boolean = false
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val ticketResp = post("/api/auth/ws-ticket", JSONObject())
                val ticket = ticketResp.optString("ticket")
                if (ticket.isEmpty()) return@withContext false

                val wsUrl = baseUrl.replace("http", "ws") + "/api/ws?ticket=" + ticket
                val req = Request.Builder().url(wsUrl).build()
                val successBox = java.util.concurrent.atomic.AtomicBoolean(false)
                val done = java.util.concurrent.CountDownLatch(1)
                val ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
                    var resumed = false
                    var lastId = 0

                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        if (skipResume) {
                            // 跳过 resume: 直接发调用(新会话在内存注册表,短 id 可用)
                            sendCalls(webSocket, sessionId)
                        } else {
                            // 先 resume 会话
                            val resume = org.json.JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", 1)
                                put("method", "session.resume")
                                put("params", org.json.JSONObject().apply {
                                    put("session_id", sessionId)
                                })
                            }
                            webSocket.send(resume.toString())
                        }
                    }

                    private fun sendCalls(webSocket: okhttp3.WebSocket, liveSid: String) {
                        calls.forEachIndexed { idx, call ->
                            lastId = idx + 2
                            val payload = org.json.JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", idx + 2)
                                put("method", call["method"])
                                put("params", org.json.JSONObject(call["params"] as Map<*, *>).apply {
                                    // 所有后续调用都必须用 resume 返回的 liveSid
                                    put("session_id", liveSid)
                                })
                            }
                            webSocket.send(payload.toString())
                        }
                    }

                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        try {
                            val obj = org.json.JSONObject(text)
                            val id = obj.optInt("id", -1)
                            if (id == 1 && obj.has("result") && !resumed) {
                                resumed = true
                                // resume 完成,按顺序发送调用
                                val liveSid = obj.getJSONObject("result")
                                    .optString("session_id").ifEmpty { sessionId }
                                sendCalls(webSocket, liveSid)
                            } else if (id == lastId && lastId > 0) {
                                successBox.set(!obj.has("error"))
                                done.countDown()
                            }
                        } catch (e: Exception) {
                        }
                    }

                    override fun onFailure(
                        webSocket: okhttp3.WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?
                    ) {
                        done.countDown()
                    }
                })

                if (!done.await(12, java.util.concurrent.TimeUnit.SECONDS)) {
                    ws.cancel()
                    return@withContext false
                }
                Thread.sleep(1200)
                ws.close(1000, "done")
                ws.cancel()
                successBox.get()
            } catch (e: Exception) {
                false
            }
        }

    suspend fun createSession(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val ticketResp = post("/api/auth/ws-ticket", JSONObject())
            val ticket = ticketResp.optString("ticket")
            if (ticket.isEmpty()) return@withContext null

            val wsUrl = baseUrl.replace("http", "ws") + "/api/ws?ticket=" + ticket
            val req = Request.Builder().url(wsUrl).build()
            val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val done = java.util.concurrent.CountDownLatch(1)
            val ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val payload = org.json.JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "session.create")
                        put("params", org.json.JSONObject().apply {
                            put("title", name)
                        })
                    }
                    webSocket.send(payload.toString())
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    try {
                        val obj = org.json.JSONObject(text)
                        if (obj.optInt("id", -1) == 1) {
                            val sid = obj.optJSONObject("result")?.optString("session_id")
                            if (!sid.isNullOrEmpty()) result.set(sid)
                            done.countDown()
                        }
                    } catch (e: Exception) {
                    }
                }

                override fun onFailure(
                    webSocket: okhttp3.WebSocket,
                    t: Throwable,
                    response: okhttp3.Response?
                ) {
                    done.countDown()
                }
            })

            if (!done.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                ws.cancel()
                return@withContext null
            }
            ws.close(1000, "done")
            ws.cancel()
            result.get()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteSession(sessionId: String) {
        delete("/api/sessions/$sessionId")
    }

    /** ⬇ 下载服务器文件到本地,返回文件路径 */
    suspend fun downloadFile(serverPath: String, localFile: java.io.File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(baseUrl + "/api/files/download?path=" + java.net.URLEncoder.encode(serverPath, "UTF-8"))
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    localFile.parentFile?.mkdirs()
                    resp.body?.byteStream()?.use { input ->
                        localFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

    /** ⬇ 下载到 MediaStore 公共下载目录(Android 10+,免存储权限),返回保存的 URI */
    suspend fun downloadFileToMediaStore(
        serverPath: String,
        fileName: String,
        resolver: android.content.ContentResolver
    ): android.net.Uri? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(baseUrl + "/api/files/download?path=" + java.net.URLEncoder.encode(serverPath, "UTF-8"))
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                }
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return@withContext null
                val written = resolver.openOutputStream(uri)?.use { output ->
                    resp.body?.byteStream()?.use { input -> input.copyTo(output) }
                    true
                } ?: false
                if (written) uri else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun sendMessage(sessionId: String, text: String): Boolean =
        wsRpc(sessionId, listOf(
            mapOf("method" to "prompt.submit", "params" to mapOf("session_id" to sessionId, "text" to text))
        ))

    /** 给刚创建(未落库)的会话发消息: 跳过 resume,直接用内存短 id submit */
    suspend fun sendMessageNewSession(sessionId: String, text: String): Boolean =
        wsRpc(sessionId, listOf(
            mapOf("method" to "prompt.submit", "params" to mapOf("session_id" to sessionId, "text" to text))
        ), skipResume = true)
}
