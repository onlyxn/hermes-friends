package com.hermes.mobile

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var serverStore: ServerStore
    private lateinit var api: ApiClient
    private var currentApi: ApiClient? = null
    private lateinit var loginView: LinearLayout
    private lateinit var listView: LinearLayout
    private lateinit var chatView: LinearLayout

    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var messageAdapter: MessageAdapter
    private val sessionRows = mutableListOf<SessionRow>()
    // 每个服务器一份完整会话列表: serverId -> sessions
    private val serverSessions = mutableMapOf<String, MutableList<HermesSession>>()
    // 折叠状态: 服务器 id 集合 + 每个服务器的组名集合
    private val collapsedServers = mutableSetOf<String>()
    private val collapsedGroupsByServer = mutableMapOf<String, MutableSet<String>>()
    private val messages = mutableListOf<HermesMessage>()
    private var currentSession: HermesSession? = null

    // 📎 附件槽位: 待发送的图片/文件
    private val pendingAttachments = mutableListOf<PendingAttachment>()
    private lateinit var attachmentAdapter: AttachmentAdapter
    private var pendingCameraFile: java.io.File? = null

    // "…" 动态打字动画: . .. ... 循环
    private val typingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var typingRunnable: Runnable? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var refreshJob: Job? = null

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etMessage: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serverStore = ServerStore(this)
        api = ApiClient(this, ServerConfig(
            id = "pending", name = "", baseUrl = "", username = ""
        ))

        loginView = findViewById(R.id.loginView)
        listView = findViewById(R.id.listView)
        chatView = findViewById(R.id.chatView)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etMessage = findViewById(R.id.etMessage)

        findViewById<Button>(R.id.btnLogin).setOnClickListener { doLogin() }
        findViewById<ImageView>(R.id.btnLoginBack).setOnClickListener { showList() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMessage() }
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { showList() }
        findViewById<ImageView>(R.id.btnAddSession).setOnClickListener { showAddMenu() }
        findViewById<ImageView>(R.id.btnAttach).setOnClickListener { showAttachMenu() }
        findViewById<ImageView>(R.id.btnLogout).setOnClickListener { showLogin() }

        sessionAdapter = SessionAdapter(sessionRows, { s ->
            showChat(s)
        }, { group ->
            // 折叠/展开项目组(当前服务器)
            val sid = currentServerId() ?: return@SessionAdapter
            val groups = collapsedGroupsByServer.getOrPut(sid) { mutableSetOf() }
            if (!groups.remove(group)) groups.add(group)
            rebuildSessionRows()
        }, { serverId ->
            // 折叠/展开服务器
            if (!collapsedServers.remove(serverId)) collapsedServers.add(serverId)
            rebuildSessionRows()
        }, { server ->
            // 长按服务器: 重命名/删除
            showServerActions(server)
        }, { session ->
            // 长按会话: 删除
            showSessionActions(session)
        })
        findViewById<RecyclerView>(R.id.rvSessions).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sessionAdapter
        }

        messageAdapter = MessageAdapter(messages, { mediaPath ->
            downloadAttachment(mediaPath)
        }, { mediaPath, imageView ->
            loadImageInto(mediaPath, imageView)
        })
        findViewById<RecyclerView>(R.id.rvMessages).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
            // ★ 禁用 item 动画: notifyItemChanged 的淡入淡出是闪烁根源(打字动画每 350ms 触发一次)
            itemAnimator = null
        }

        // 📎 附件槽位
        attachmentAdapter = AttachmentAdapter(pendingAttachments) { idx ->
            pendingAttachments.removeAt(idx)
            attachmentAdapter.notifyDataSetChanged()
            updateAttachBar()
        }
        findViewById<RecyclerView>(R.id.rvAttachments).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = attachmentAdapter
        }

        findViewById<SwipeRefreshLayout>(R.id.swipeSessions).setOnRefreshListener {
            refreshAllServers { findViewById<SwipeRefreshLayout>(R.id.swipeSessions).isRefreshing = false }
        }
        findViewById<SwipeRefreshLayout>(R.id.swipeMessages).setOnRefreshListener {
            refreshMessages { findViewById<SwipeRefreshLayout>(R.id.swipeMessages).isRefreshing = false }
        }

        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        val servers = serverStore.loadAll()
        if (servers.isEmpty()) {
            showLogin()
        } else {
            showList()
            refreshAllServers()
        }
    }

    /** 当前展开操作的服务器(第一个未折叠的,或第一个) */
    private fun currentServerId(): String? {
        val servers = serverStore.loadAll()
        return servers.firstOrNull { it.id !in collapsedServers }?.id ?: servers.firstOrNull()?.id
    }

    private fun showLogin() {
        // 已有服务器时显示返回按钮,否则隐藏(首次添加)
        findViewById<ImageView>(R.id.btnLoginBack).visibility =
            if (serverStore.loadAll().isNotEmpty()) View.VISIBLE else View.GONE
        loginView.visibility = View.VISIBLE
        listView.visibility = View.GONE
        chatView.visibility = View.GONE
    }

    private fun showList() {
        loginView.visibility = View.GONE
        listView.visibility = View.VISIBLE
        chatView.visibility = View.GONE
        currentSession = null
        currentApi = null
        pollJob?.cancel()
        rebuildSessionRows()
    }

    private fun showChat(s: HermesSession) {
        currentSession = s
        // 找到会话所属服务器,用它的 ApiClient
        val server = serverStore.get(s.serverId)
        if (server == null) {
            toast(getString(R.string.server_not_found))
            return
        }
        currentApi = ApiClient(this, server)
        loginView.visibility = View.GONE
        listView.visibility = View.GONE
        chatView.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvChatTitle).text = s.displayName
        refreshMessages()
        startPolling()
    }

    private fun doLogin() {
        val server = etServer.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            toast(getString(R.string.fill_all))
            return
        }
        // 新建服务器配置并登录
        val cfg = ServerConfig(
            id = java.util.UUID.randomUUID().toString(),
            name = server,
            baseUrl = server,
            username = user
        )
        val client = ApiClient(this, cfg)
        scope.launch {
            try {
                val ok = client.login(user, pass)
                if (ok) {
                    cfg.loggedIn = true
                    serverStore.add(cfg)
                    toast(getString(R.string.server_added))
                    etServer.setText("")
                    etUser.setText("")
                    etPass.setText("")
                    showList()
                    refreshAllServers()
                } else {
                    toast(getString(R.string.login_failed))
                }
            } catch (e: Exception) {
                toast(getString(R.string.conn_failed, e.message))
            }
        }
    }

    /** 刷新所有已登录服务器 */
    private fun refreshAllServers(after: () -> Unit = {}) {
        val servers = serverStore.loadAll()
        var remaining = servers.count { it.loggedIn }
        if (remaining == 0) {
            rebuildSessionRows()
            after()
            return
        }
        servers.filter { it.loggedIn }.forEach { server ->
            val client = ApiClient(this, server)
            scope.launch {
                try {
                    val list = client.listSessions()
                    // 保留旧缩略
                    val old = serverSessions[server.id]?.associate { it.id to it.preview } ?: emptyMap()
                    // 保留本地有但服务器还没有的会话(新建未落库的),但超过 10 分钟的一律清掉(可能是幽灵条目)
                    val now = System.currentTimeMillis()
                    val localOnly = serverSessions[server.id]
                        ?.filter { ls ->
                            list.none { it.id == ls.id } &&
                                (now - ls.lastActivity) < 10 * 60 * 1000
                        }
                        ?: emptyList()
                    val withPreviews = (localOnly + list).map { s ->
                        s.copy(
                            preview = if (s.preview.isEmpty()) old[s.id] ?: "" else s.preview,
                            serverId = server.id
                        )
                    }
                    serverSessions[server.id] = withPreviews.toMutableList()
                    rebuildSessionRows()
                    fillPreviews(server.id, client)
                } catch (e: ApiClient.UnauthorizedException) {
                    server.loggedIn = false
                    serverStore.update(server)
                } catch (e: Exception) {
                    // 网络失败: 保留旧数据
                } finally {
                    remaining--
                    if (remaining <= 0) after()
                }
            }
        }
    }

    private var previewJob: Job? = null

    /** 填充某服务器的会话缩略(只拉一次) */
    private fun fillPreviews(serverId: String, client: ApiClient) {
        val sessions = serverSessions[serverId] ?: return
        previewJob?.cancel()
        previewJob = scope.launch {
            sessions.forEach { s ->
                if (s.preview.isEmpty()) {
                    val last = client.getLastMessage(s.id)
                    if (last.isNotEmpty()) {
                        val ai = sessions.indexOfFirst { it.id == s.id }
                        if (ai >= 0) sessions[ai] = sessions[ai].copy(preview = last)
                    }
                }
            }
            rebuildSessionRows()
        }
    }

    /** 从 cwd 推导项目组名(与桌面端项目概念一致) */
    private fun projectGroupOf(s: HermesSession): String {
        val cwd = s.cwd ?: return "Home"
        val norm = cwd.replace('\\', '/').trimEnd('/')
        if (norm.isEmpty() || norm == "/" || norm == "/root") return "Home"
        // 取路径最后一段作为组名
        return norm.substringAfterLast('/').ifEmpty { "Home" }
    }

    /** 三层重建: 服务器 → 项目组 → 会话 */
    private fun rebuildSessionRows() {
        sessionRows.clear()
        val servers = serverStore.loadAll()
        servers.forEach { server ->
            val sessions = serverSessions[server.id] ?: emptyList()
            val serverExpanded = server.id !in collapsedServers
            sessionRows.add(SessionRow.ServerHeader(server, sessions.size, serverExpanded))
            if (serverExpanded) {
                val groups = sessions.groupBy { projectGroupOf(it) }
                val collapsedGroups = collapsedGroupsByServer[server.id] ?: emptySet()
                groups.forEach { (group, groupSessions) ->
                    val groupExpanded = group !in collapsedGroups
                    sessionRows.add(SessionRow.Header(group, groupSessions.size, groupExpanded))
                    if (groupExpanded) {
                        groupSessions.sortedByDescending { it.lastActivity }
                            .forEach { sessionRows.add(SessionRow.Item(it)) }
                    }
                }
            }
        }
        sessionAdapter.notifyDataSetChanged()
    }

    private fun refreshMessages(after: () -> Unit = {}) {
        val s = currentSession ?: return
        val client = currentApi ?: return
        scope.launch {
            try {
                val rv = findViewById<RecyclerView>(R.id.rvMessages)
                // ② 精确判断: 最后一条消息"完全可见"才算贴底(用户翻到附近不算)
                val lm = rv.layoutManager as? LinearLayoutManager
                val wasAtBottom = lm?.let {
                    it.findLastCompletelyVisibleItemPosition() >= messages.size - 1
                } ?: true
                val oldCount = messages.size
                // 🟢 占位气泡: agent 还在回复时(本地哨兵气泡存在), 刷新后保留
                val hadPlaceholder = messages.any { it.id == PLACEHOLDER_ID }

                val list = client.getMessages(s.id)

                // ★ 防闪烁: 动画期间(有占位气泡)且服务器无新消息时, 不重建列表
                val oldIds = messages.filter { it.id != PLACEHOLDER_ID }.map { it.id }
                val newIds = list.map { it.id }
                val unchanged = hadPlaceholder && oldIds == newIds

                if (!unchanged) {
                    messages.clear()
                    messages.addAll(list)
                    // 占位气泡加回末尾(工作状态指示, 由 sendMessage 轮询循环在回复到达时移除)
                    if (hadPlaceholder) {
                        messages.add(HermesMessage(
                            id = PLACEHOLDER_ID,
                            role = "assistant",
                            content = "",
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                    messageAdapter.notifyDataSetChanged()
                    // 只有原本就在底部(或新消息到来)才自动滚到底,翻历史时不动
                    if (wasAtBottom || list.size != oldCount) {
                        rv.scrollToPosition(messages.size - 1)
                    }
                }
            } catch (e: ApiClient.UnauthorizedException) {
                showLogin()
                toast(getString(R.string.login_expired))
            } catch (e: Exception) {
                toast(getString(R.string.load_failed, e.message))
            } finally {
                after()
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(3000)
                refreshMessages()
            }
        }
    }

    /** 💬 启动 ". .. ..." 打字动画(占位气泡动态跳动, 直接改 TextView 不触发 notify 防闪烁) */
    private fun startTypingAnimation() {
        stopTypingAnimation()
        var i = 0
        val steps = arrayOf(".", "..", "...", "…")
        val runnable = object : Runnable {
            override fun run() {
                val idx = messages.indexOfLast { it.id == PLACEHOLDER_ID }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(content = steps[i % steps.size])
                    // ★ 直接更新视图文本, 不 notifyItemChanged(避免 RecyclerView 变化动画闪烁)
                    val rv = findViewById<RecyclerView>(R.id.rvMessages)
                    val holder = rv.findViewHolderForAdapterPosition(idx)
                    if (holder is MessageAdapter.VH) {
                        holder.bubbleAssistant.text = steps[i % steps.size]
                    }
                    i++
                    typingHandler.postDelayed(this, 350)
                }
            }
        }
        typingRunnable = runnable
        typingHandler.post(runnable)
    }

    private fun stopTypingAnimation() {
        typingRunnable?.let { typingHandler.removeCallbacks(it) }
        typingRunnable = null
    }

    private fun sendMessage() {
        val s = currentSession ?: return
        val client = currentApi ?: return
        val text = etMessage.text.toString().trim()

        // 📎 有附件槽: 上传全部附件, 和文字一起发
        if (pendingAttachments.isNotEmpty()) {
            sendWithAttachments(s, client, text)
            return
        }

        if (text.isEmpty()) return
        etMessage.setText("")

        // 本地先显示用户消息
        messages.add(HermesMessage(
            id = System.currentTimeMillis(),
            role = "user",
            content = text,
            timestamp = System.currentTimeMillis()
        ))
        // 占位气泡("…" = 工作中状态指示)
        val placeholder = HermesMessage(
            id = PLACEHOLDER_ID,
            role = "assistant",
            content = "",
            timestamp = System.currentTimeMillis()
        )
        messages.add(placeholder)
        messageAdapter.notifyDataSetChanged()
        findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(messages.size - 1)
        // ★ 无条件启动动画(不依赖事件): 气泡一定动态跳动
        startTypingAnimation()

        scope.launch {
            try {
                // ★ 事件驱动(桌面端同款): 监听 session.info 的 running 字段
                // running=true → "…"气泡; running=false → 回复完成, 移除气泡拉全量
                var statusOk = false
                try {
                    statusOk = client.sendMessageWithStatus(
                        s.id, text,
                        onRunning = { running ->
                            runOnUiThread {
                                val idx = messages.indexOfLast { it.id == PLACEHOLDER_ID }
                                if (running && idx < 0) {
                                    // 开始工作: 显示占位气泡 + 启动打字动画
                                    messages.add(HermesMessage(
                                        id = PLACEHOLDER_ID,
                                        role = "assistant",
                                        content = "",
                                        timestamp = System.currentTimeMillis()
                                    ))
                                    messageAdapter.notifyDataSetChanged()
                                    findViewById<RecyclerView>(R.id.rvMessages)
                                        .scrollToPosition(messages.size - 1)
                                    startTypingAnimation()
                                } else if (!running && idx >= 0) {
                                    // 完成: 停止动画, 移除占位, 拉全量
                                    stopTypingAnimation()
                                    messages.removeAt(idx)
                                    messageAdapter.notifyItemRemoved(idx)
                                    refreshMessages()
                                }
                            }
                        },
                        onDone = {
                            runOnUiThread {
                                stopTypingAnimation()
                                messages.removeAll { it.id == PLACEHOLDER_ID }
                                messageAdapter.notifyDataSetChanged()
                                refreshMessages()
                            }
                        },
                        onError = { err ->
                            runOnUiThread {
                                stopTypingAnimation()
                                val idx = messages.indexOfLast { it.id == PLACEHOLDER_ID }
                                if (idx >= 0) {
                                    messages[idx] = messages[idx].copy(
                                        content = "⚠️ " + getString(R.string.msg_failed, err)
                                    )
                                    messageAdapter.notifyItemChanged(idx)
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    statusOk = false
                }
                if (!statusOk) {
                    // 事件通道失败(可能新会话未落库): 兜底用普通发送
                    val ok2 = client.sendMessageNewSession(s.id, text)
                    if (!ok2) {
                        client.sendMessage(s.id, text)
                    }
                    delay(8000)
                    runOnUiThread {
                        stopTypingAnimation()
                        messages.removeAll { it.id == PLACEHOLDER_ID }
                        refreshMessages()
                    }
                }
            } catch (e: Exception) {
                toast(getString(R.string.msg_failed, e.message))
            }
        }
    }

    /** "+"菜单: 添加服务器 / 创建新对话到... */
    private fun showAddMenu() {
        val servers = serverStore.loadAll().filter { it.loggedIn }
        val options = mutableListOf(getString(R.string.add_server))
        if (servers.isNotEmpty()) options.add(getString(R.string.new_chat_to))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_menu_title))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showLogin()
                    1 -> createSessionInGroupDialog(servers)
                }
            }
            .show()
    }

    /** 创建新对话: 选服务器 → 选组 → 输入名称 */
    private fun createSessionInGroupDialog(servers: List<ServerConfig>) {
        // 第一步: 选服务器
        val names = servers.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_server))
            .setItems(names) { _, which ->
                val server = servers[which]
                // 第二步: 选组(该服务器的项目组)
                val sessions = serverSessions[server.id] ?: emptyList()
                val groups = sessions.map { projectGroupOf(it) }.distinct()
                if (groups.isEmpty()) {
                    createSessionInGroup(server, "Home")
                    return@setItems
                }
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.choose_group))
                    .setItems(groups.toTypedArray()) { _, g ->
                        createSessionInGroup(server, groups[g])
                    }
                    .show()
            }
            .show()
    }

    /** 创建新会话: 输入第一句话(发送后立即落库,避免懒创建404) */
    private fun createSessionInGroup(server: ServerConfig, group: String) {
        val input = EditText(this)
        input.hint = getString(R.string.hint_first_msg)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_session_in, group))
            .setView(input)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val firstMsg = input.text.toString().trim()
                if (firstMsg.isEmpty()) {
                    toast(getString(R.string.first_msg_empty))
                    return@setPositiveButton
                }
                val client = ApiClient(this, server)
                scope.launch {
                    val sid = client.createSession("")
                    if (sid != null) {
                        // 用内存短 id 直接发第一句话(跳过 resume,新建会话尚未落库)
                        val sent = client.sendMessageNewSession(sid, firstMsg)
                        if (sent) {
                            // 发送成功 → 服务器已落库,不插入本地假条目(避免出现两个窗口)
                            // 等刷新拉到正式条目
                            toast(getString(R.string.session_created))
                            delay(1500)
                            refreshAllServers()
                        } else {
                            // 发送失败 → 本地兜底插入,用户还能看到并重试
                            toast(getString(R.string.create_failed_first_msg))
                            val sessions = serverSessions.getOrPut(server.id) { mutableListOf() }
                            sessions.add(
                                0, HermesSession(
                                    id = sid,
                                    displayName = firstMsg.take(20),
                                    source = "desktop",
                                    messageCount = 1,
                                    lastActivity = System.currentTimeMillis(),
                                    preview = firstMsg.take(60),
                                    serverId = server.id
                                )
                            )
                            rebuildSessionRows()
                            delay(1500)
                            refreshAllServers()
                        }
                    } else {
                        toast(getString(R.string.create_failed))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** 会话长按: 删除 */
    private fun showSessionActions(session: HermesSession) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_session_title))
            .setMessage(getString(R.string.delete_session_msg, session.displayName))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val server = serverStore.get(session.serverId)
                if (server == null) {
                    toast(getString(R.string.server_not_found))
                    return@setPositiveButton
                }
                val client = ApiClient(this, server)
                scope.launch {
                    try {
                        client.deleteSession(session.id)
                        // 本地移除
                        serverSessions[session.serverId]?.removeAll { it.id == session.id }
                        rebuildSessionRows()
                        toast(getString(R.string.session_deleted))
                    } catch (e: Exception) {
                        toast(getString(R.string.delete_failed, e.message))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** 服务器长按: 重命名 / 删除 */
    private fun showServerActions(server: ServerConfig) {
        val options = arrayOf(getString(R.string.rename), getString(R.string.delete_server))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(server.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameServerDialog(server)
                    1 -> {
                        serverStore.remove(server.id)
                        serverSessions.remove(server.id)
                        collapsedServers.remove(server.id)
                        rebuildSessionRows()
                        toast(getString(R.string.server_deleted))
                    }
                }
            }
            .show()
    }

    private fun renameServerDialog(server: ServerConfig) {
        val input = EditText(this)
        input.setText(server.name)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_server_title))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.name_empty))
                    return@setPositiveButton
                }
                server.name = name
                serverStore.update(server)
                rebuildSessionRows()
                toast(getString(R.string.renamed))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** 📎 批量发送附件槽: 逐个上传图片/文件, 最后和文字一起发出一条消息 */
    private fun sendWithAttachments(s: HermesSession, client: ApiClient, text: String) {
        scope.launch {
            val uploaded = mutableListOf<String>()  // 服务器路径
            var failed = false
            toast(getString(R.string.img_uploading))

            for (att in pendingAttachments) {
                try {
                    if (att.isImage) {
                        // 图片: 上传到 images/, 用 image.attach 附加
                        val dataUrl = if (att.bitmap != null) {
                            val baos = java.io.ByteArrayOutputStream()
                            att.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                            "data:image/jpeg;base64," +
                                android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                        } else {
                            readUriAsDataUrl(att.uri!!)
                        }
                        if (dataUrl == null) { failed = true; continue }
                        val path = client.uploadImage(dataUrl, att.name)
                        if (path != null) uploaded.add(path) else failed = true
                    } else {
                        // 文件: 上传到 /tmp/hermes_upload/
                        val dataUrl = readUriAsDataUrl(att.uri!!)
                        if (dataUrl == null) { failed = true; continue }
                        val serverPath = "/tmp/hermes_upload/${att.name}"
                        val saved = client.uploadFile(serverPath, dataUrl)
                        if (saved != null) uploaded.add(serverPath) else failed = true
                    }
                } catch (e: Exception) {
                    failed = true
                }
            }

            // 清空槽位
            runOnUiThread {
                pendingAttachments.clear()
                attachmentAdapter.notifyDataSetChanged()
                updateAttachBar()
            }

            if (uploaded.isEmpty() && failed) {
                toast(getString(R.string.upload_failed))
                return@launch
            }

            // 发送: 图片用 image.attach 批量附加, 文件用 MEDIA: 标记, 文字作为正文
            val imagePaths = uploaded.filter { MessageAdapter.isImagePath(it) }
            val filePaths = uploaded.filter { !MessageAdapter.isImagePath(it) }

            val mediaLines = filePaths.joinToString("\n") { "MEDIA:$it" }
            val caption = if (text.isEmpty()) "" else text
            val body = if (mediaLines.isEmpty()) caption else caption + if (caption.isEmpty()) "" else "\n" + mediaLines

            if (imagePaths.isNotEmpty()) {
                // 多图: 一次 wsRpc 批量 attach + submit
                val ok = client.sendImagesBatch(s.id, imagePaths, body)
                if (!ok) {
                    // 兜底: 正文带 @image: 标记
                    client.sendMessage(s.id, if (body.isEmpty()) imagePaths.joinToString("\n") { "@image:$it" } else body)
                }
            } else {
                client.sendMessage(s.id, body)
            }

            etMessage.setText("")
            toast(getString(R.string.file_sent))
            delay(800)
            refreshMessages()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ================= 附件发送 =================

    // 图片内存缓存: 服务器路径 -> Bitmap(避免重复下载)
    private val imageCache = mutableMapOf<String, android.graphics.Bitmap>()

    /** 🖼 加载 MEDIA 图片到 ImageView(缓存→下载→显示, 点击全屏), 自动校正 EXIF 方向 */
    private fun loadImageInto(mediaPath: String, imageView: ImageView) {
        // 0. 点击查看大图(微信式): 悬浮窗先显示缓存图, "查看原图"才拉完整文件
        imageView.setOnClickListener {
            showImageViewer(mediaPath)
        }

        // 1. 内存缓存
        imageCache[mediaPath]?.let {
            imageView.setImageBitmap(it)
            return
        }
        // 2. 磁盘缓存(cacheDir)
        val cacheFile = java.io.File(cacheDir, "img_" + mediaPath.hashCode() + ".jpg")
        if (cacheFile.exists()) {
            val bmp = decodeBitmapWithExif(cacheFile)
            if (bmp != null) {
                imageCache[mediaPath] = bmp
                imageView.setImageBitmap(bmp)
                return
            }
        }
        // 3. 网络下载
        val client = currentApi ?: return
        scope.launch {
            try {
                val ok = client.downloadFile(mediaPath, cacheFile)
                if (ok && cacheFile.exists()) {
                    val bmp = decodeBitmapWithExif(cacheFile)
                    if (bmp != null) {
                        imageCache[mediaPath] = bmp
                        runOnUiThread { imageView.setImageBitmap(bmp) }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    /** 🖼 微信式图片查看悬浮窗: 缓存秒开 + 查看原图 + 拖动/双指缩放/双击/X关闭 */
    private fun showImageViewer(mediaPath: String) {
        val client = currentApi ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_image_viewer, null)
        val viewerImage = dialogView.findViewById<ImageView>(R.id.viewerImage)
        val btnOriginal = dialogView.findViewById<Button>(R.id.btnViewOriginal)
        val btnClose = dialogView.findViewById<TextView>(R.id.btnViewerClose)
        val loading = dialogView.findViewById<TextView>(R.id.viewerLoading)

        // 微信式沉浸: 用 Dialog + 无边框全屏主题(AlertDialog 自带窗口装饰会"框死")
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        btnClose.setOnClickListener { dialog.dismiss() }

        // ===== 手势: 拖动 + 双指缩放 + 双击 =====
        val matrix = android.graphics.Matrix()
        var currentScale = 1f
        var minScale = 1f
        var lastTouchX = 0f
        var lastTouchY = 0f
        var dragging = false

        fun fitToScreen(bmp: android.graphics.Bitmap) {
            val vw = viewerImage.width.toFloat().coerceAtLeast(1f)
            val vh = viewerImage.height.toFloat().coerceAtLeast(1f)
            val scale = minOf(vw / bmp.width, vh / bmp.height)
            minScale = scale
            currentScale = scale
            matrix.reset()
            matrix.postScale(scale, scale)
            // 居中
            matrix.postTranslate((vw - bmp.width * scale) / 2, (vh - bmp.height * scale) / 2)
            viewerImage.imageMatrix = matrix
        }

        val scaleDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val newScale = (currentScale * d.scaleFactor).coerceIn(minScale * 0.5f, minScale * 8f)
                val factor = newScale / currentScale
                matrix.postScale(factor, factor, d.focusX, d.focusY)
                currentScale = newScale
                viewerImage.imageMatrix = matrix
                return true
            }
        })

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                if (currentScale > minScale * 1.1f) {
                    // 还原
                    viewerImage.setImageBitmap(imageCache[mediaPath] ?: return true)
                    fitToScreen(imageCache[mediaPath] ?: return true)
                } else {
                    // 放大 2.5 倍(以点击点为中心)
                    val target = minScale * 2.5f
                    val factor = target / currentScale
                    matrix.postScale(factor, factor, e.x, e.y)
                    currentScale = target
                    viewerImage.imageMatrix = matrix
                }
                return true
            }

            override fun onDown(e: android.view.MotionEvent): Boolean {
                lastTouchX = e.x
                lastTouchY = e.y
                dragging = true
                return true
            }
        })

        viewerImage.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) {
                gestureDetector.onTouchEvent(event)
                if (event.action == android.view.MotionEvent.ACTION_MOVE && dragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    matrix.postTranslate(dx, dy)
                    viewerImage.imageMatrix = matrix
                    lastTouchX = event.x
                    lastTouchY = event.y
                } else if (event.action == android.view.MotionEvent.ACTION_UP ||
                    event.action == android.view.MotionEvent.ACTION_CANCEL
                ) {
                    dragging = false
                }
            }
            true
        }

        fun setImage(bmp: android.graphics.Bitmap) {
            viewerImage.setImageBitmap(bmp)
            viewerImage.post { fitToScreen(bmp) }
        }

        // ===== 第一步: 显示缓存图(秒开) =====
        val thumbFile = java.io.File(cacheDir, "img_" + mediaPath.hashCode() + ".jpg")
        var shown = false
        imageCache[mediaPath]?.let {
            setImage(it)
            shown = true
        }
        if (!shown && thumbFile.exists()) {
            decodeBitmapWithExif(thumbFile)?.let {
                imageCache[mediaPath] = it
                setImage(it)
                shown = true
            }
        }
        // 缓存都没有: 自动下载
        if (!shown) {
            loading.visibility = View.VISIBLE
            scope.launch {
                val ok = client.downloadFile(mediaPath, thumbFile)
                if (ok && thumbFile.exists()) {
                    val bmp = decodeBitmapWithExif(thumbFile)
                    if (bmp != null) {
                        imageCache[mediaPath] = bmp
                        runOnUiThread {
                            loading.visibility = View.GONE
                            setImage(bmp)
                        }
                    }
                }
            }
        }

        // ===== 第二步: 查看原图 =====
        var originalLoaded = false
        btnOriginal.setOnClickListener {
            if (originalLoaded) return@setOnClickListener
            originalLoaded = true
            btnOriginal.text = getString(R.string.loading_original)
            btnOriginal.isEnabled = false
            val fullFile = java.io.File(cacheDir, "img_full_" + mediaPath.hashCode() + ".jpg")
            scope.launch {
                val ok = if (fullFile.exists()) true else client.downloadFile(mediaPath, fullFile)
                if (ok && fullFile.exists()) {
                    val bmp = decodeBitmapSampled(fullFile)
                    if (bmp != null) {
                        runOnUiThread {
                            setImage(bmp)
                            // 原图已加载: 按钮不再需要, 隐藏(微信式)
                            btnOriginal.visibility = View.GONE
                            originalLoaded = false
                        }
                    } else {
                        runOnUiThread {
                            btnOriginal.text = getString(R.string.download_failed)
                            btnOriginal.isEnabled = true
                            originalLoaded = false
                        }
                    }
                } else {
                    runOnUiThread {
                        btnOriginal.text = getString(R.string.download_failed)
                        btnOriginal.isEnabled = true
                        originalLoaded = false
                    }
                }
            }
        }

        dialog.show()
    }

    /** 按屏幕尺寸采样解码(大图不爆内存), 校正 EXIF */
    private fun decodeBitmapSampled(file: java.io.File): android.graphics.Bitmap? {
        return try {
            // 先读尺寸
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            // 按屏幕 2 倍采样(显示足够清晰)
            val display = resources.displayMetrics
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / sample > display.widthPixels * 2 && h / sample > display.heightPixels * 2) {
                sample *= 2
            }
            // 采样解码
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            // EXIF 校正
            val exif = android.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bmp
            }
            val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: Exception) {
            null
        }
    }

    /** 解码图片并校正 EXIF 方向(手机拍照的图会带旋转标记) */
    private fun decodeBitmapWithExif(file: java.io.File): android.graphics.Bitmap? {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val exif = android.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bmp
            }
            val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: Exception) {
            bmp
        }
    }

    /** ⬇ 下载 MEDIA 附件到手机,成功后直接打开 */
    private fun downloadAttachment(mediaPath: String) {
        val client = currentApi ?: return
        scope.launch {
            toast(getString(R.string.downloading))
            val name = mediaPath.substringAfterLast("/").ifEmpty { "attachment_${System.currentTimeMillis()}" }
            val isApk = name.endsWith(".apk", ignoreCase = true)

            if (isApk) {
                // APK 特殊处理: 下载到应用私有目录 + 检查安装权限 + FileProvider 打开
                if (android.os.Build.VERSION.SDK_INT >= 26 &&
                    !packageManager.canRequestPackageInstalls()
                ) {
                    // 引导用户开启"允许安装未知应用"
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.need_install_perm))
                        .setMessage(getString(R.string.install_perm_prompt))
                        .setPositiveButton(getString(R.string.go_enable)) { _, _ ->
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                    return@launch
                }
                val target = java.io.File(cacheDir, name)
                val downloaded = client.downloadFile(mediaPath, target)
                if (downloaded) {
                    toast(getString(R.string.downloaded_installing))
                    openFile(target)
                } else {
                    toast(getString(R.string.download_failed))
                }
                return@launch
            }

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // Android 10+: MediaStore 保存,拿到 URI 直接打开
                val uri = client.downloadFileToMediaStore(mediaPath, name, contentResolver)
                if (uri != null) {
                    toast(getString(R.string.saved_to, name))
                    openUri(uri, name)
                } else {
                    toast(getString(R.string.download_failed))
                }
            } else {
                // Android 9-: 直接写 Download 目录,用 FileProvider 打开
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val target = java.io.File(dir, name)
                val downloaded = client.downloadFile(mediaPath, target)
                if (downloaded) {
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        .setData(android.net.Uri.fromFile(target))
                        .let { sendBroadcast(it) }
                    toast(getString(R.string.saved_to, name))
                    openFile(target)
                } else {
                    toast(getString(R.string.download_failed))
                }
            }
        }
    }

    /** Android 10+: 用 content URI 直接打开 */
    private fun openUri(uri: android.net.Uri, name: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(name))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.no_app_to_open))
        }
    }

    /** Android 9-: 用 FileProvider 打开本地文件 */
    private fun openFile(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(file.name))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.no_app_to_open))
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "image/*"
            "mp4", "mkv", "webm", "avi", "mov" -> "video/*"
            "mp3", "wav", "flac", "m4a", "ogg" -> "audio/*"
            "pdf" -> "application/pdf"
            "txt", "md", "log", "json", "yaml", "yml", "xml", "csv" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "zip", "7z", "rar", "tar", "gz" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun showAttachMenu() {
        if (currentSession == null) return
        val options = arrayOf(getString(R.string.attach_camera), getString(R.string.attach_gallery), getString(R.string.attach_file))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.attach_menu_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> pickImage()
                    2 -> pickFile()
                }
            }
            .show()
    }

    private fun launchCamera() {
        // 保存到文件(保留 EXIF 方向信息), 而不是取缩略图
        try {
            val file = java.io.File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            pendingCameraFile = file
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, REQ_CAMERA)
            } else {
                toast(getString(R.string.no_camera))
            }
        } catch (e: Exception) {
            toast(getString(R.string.camera_failed))
        }
    }

    private fun pickImage() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        startActivityForResult(
            android.content.Intent.createChooser(intent, getString(R.string.pick_image)),
            REQ_IMAGE
        )
    }

    private fun pickFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
        }
        startActivityForResult(
            android.content.Intent.createChooser(intent, getString(R.string.pick_file)),
            REQ_FILE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> {
                // 拍照 → 从文件读(带 EXIF), 校正方向后加入附件槽
                val file = pendingCameraFile
                pendingCameraFile = null
                if (file != null && file.exists()) {
                    scope.launch {
                        val bmp = decodeBitmapWithExif(file)
                        if (bmp != null) {
                            pendingAttachments.add(PendingAttachment(
                                bitmap = bmp,
                                name = file.name,
                                isImage = true
                            ))
                            attachmentAdapter.notifyDataSetChanged()
                            updateAttachBar()
                        } else {
                            toast(getString(R.string.read_image_failed))
                        }
                    }
                } else {
                    toast(getString(R.string.camera_failed))
                }
            }
            REQ_IMAGE -> data?.data?.let { uri ->
                // 相册选图 → 加入附件槽(可连续选多张), EXIF 方向校正后存入
                val name = queryFileName(uri) ?: "image_${System.currentTimeMillis()}.jpg"
                scope.launch {
                    val bmp = decodeUriBitmap(uri)
                    val att = if (bmp != null) {
                        PendingAttachment(bitmap = bmp, name = name, isImage = true)
                    } else {
                        PendingAttachment(uri = uri, name = name, isImage = true)
                    }
                    pendingAttachments.add(att)
                    attachmentAdapter.notifyDataSetChanged()
                    updateAttachBar()
                }
            }
            REQ_FILE -> data?.data?.let { uri ->
                // 选文件 → 加入附件槽
                val name = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"
                pendingAttachments.add(PendingAttachment(uri = uri, name = name, isImage = false))
                attachmentAdapter.notifyDataSetChanged()
                updateAttachBar()
            }
        }
    }

    /** 从 content Uri 解码图片并校正 EXIF 方向 */
    private fun decodeUriBitmap(uri: android.net.Uri): android.graphics.Bitmap? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val bmp = android.graphics.BitmapFactory.decodeStream(input)
            input.close()
            if (bmp == null) return null
            // 读 EXIF(通过临时文件或直接读 uri 的 orientation)
            var orientation = android.media.ExifInterface.ORIENTATION_NORMAL
            try {
                val desc = contentResolver.openAssetFileDescriptor(uri, "r")
                if (desc != null) {
                    val exif = android.media.ExifInterface(desc.fileDescriptor)
                    orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    desc.close()
                }
            } catch (e: Exception) {
            }
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bmp
            }
            val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: Exception) {
            null
        }
    }

    /** 附件槽可见性: 有附件就显示 */
    private fun updateAttachBar() {
        findViewById<RecyclerView>(R.id.rvAttachments).visibility =
            if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 📸 选图后弹预览+说明对话框: 输入文字(可选)后图片和文字一起发送 */
    private fun promptSendImage(bmp: android.graphics.Bitmap?, uri: android.net.Uri?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_send_image, null)
        val preview = dialogView.findViewById<ImageView>(R.id.dlgImgPreview)
        val caption = dialogView.findViewById<EditText>(R.id.dlgCaption)
        if (bmp != null) {
            preview.setImageBitmap(bmp)
        } else if (uri != null) {
            preview.setImageURI(uri)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.attach_menu_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val text = caption.text.toString().trim()
                if (bmp != null) sendBitmapImage(bmp, text)
                else if (uri != null) sendUriImage(uri, text)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun sendBitmapImage(bmp: android.graphics.Bitmap, captionText: String) {
        val client = currentApi ?: return
        scope.launch {
            toast(getString(R.string.img_uploading))
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            val dataUrl = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            val ok = client.sendImage(currentSession!!.id, dataUrl, "camera_${System.currentTimeMillis()}.jpg", captionText)
            etMessage.setText("")
            toast(if (ok) getString(R.string.img_sent) else getString(R.string.msg_failed, "unknown"))
            delay(800)
            refreshMessages()
        }
    }

    private fun sendUriImage(uri: android.net.Uri, captionText: String) {
        val client = currentApi ?: return
        scope.launch {
            toast(getString(R.string.img_uploading))
            val dataUrl = readUriAsDataUrl(uri)
            if (dataUrl == null) {
                toast(getString(R.string.read_image_failed))
                return@launch
            }
            val name = queryFileName(uri) ?: "image_${System.currentTimeMillis()}.jpg"
            val ok = client.sendImage(currentSession!!.id, dataUrl, name, captionText)
            etMessage.setText("")
            toast(if (ok) getString(R.string.img_sent) else getString(R.string.msg_failed, "unknown"))
            delay(800)
            refreshMessages()
        }
    }

    private fun sendUriFile(uri: android.net.Uri) {
        // 选文件后也弹对话框输入说明(可选), 文件和文字一起发
        val dialogView = layoutInflater.inflate(R.layout.dialog_send_image, null)
        val preview = dialogView.findViewById<ImageView>(R.id.dlgImgPreview)
        val caption = dialogView.findViewById<EditText>(R.id.dlgCaption)
        preview.visibility = View.GONE  // 文件没有预览
        caption.hint = getString(R.string.hint_caption)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.attach_file))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                sendUriFileWithCaption(uri, caption.text.toString().trim())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun sendUriFileWithCaption(uri: android.net.Uri, captionText: String) {
        val client = currentApi ?: return
        scope.launch {
            toast(getString(R.string.file_uploading))
            val name = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"
            val dataUrl = readUriAsDataUrl(uri)
            if (dataUrl == null) {
                toast(getString(R.string.read_file_failed))
                return@launch
            }
            // 文件放到服务器 /tmp/hermes_upload/ 下,消息里带上路径
            val serverPath = "/tmp/hermes_upload/$name"
            val saved = client.uploadFile(serverPath, dataUrl)
            if (saved == null) {
                toast(getString(R.string.upload_failed))
                return@launch
            }
            val text = if (captionText.isEmpty()) getString(R.string.file_uploaded, serverPath) else "$captionText\n文件: $serverPath"
            val ok = client.sendMessage(currentSession!!.id, text)
            etMessage.setText("")
            toast(if (ok) getString(R.string.file_sent) else getString(R.string.msg_failed, "unknown"))
            delay(800)
            refreshMessages()
        }
    }

    private fun readUriAsDataUrl(uri: android.net.Uri): String? {
        return try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun queryFileName(uri: android.net.Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    companion object {
        private const val REQ_CAMERA = 1001
        private const val REQ_IMAGE = 1002
        private const val REQ_FILE = 1003
        // 占位气泡哨兵 id(永不与服务器消息 id 冲突)
        private const val PLACEHOLDER_ID = Long.MAX_VALUE - 1
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTypingAnimation()
        scope.cancel()
    }
}
