package com.hermes.mobile

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

        messageAdapter = MessageAdapter(messages) { mediaPath ->
            downloadAttachment(mediaPath)
        }
        findViewById<RecyclerView>(R.id.rvMessages).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
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

                val list = client.getMessages(s.id)
                messages.clear()
                messages.addAll(list)
                messageAdapter.notifyDataSetChanged()
                // 只有原本就在底部(或新消息到来)才自动滚到底,翻历史时不动
                if (wasAtBottom || list.size != oldCount) {
                    rv.scrollToPosition(messages.size - 1)
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

    private fun sendMessage() {
        val s = currentSession ?: return
        val client = currentApi ?: return
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        etMessage.setText("")
        scope.launch {
            try {
                if (s.serverId == client.server.id && client.server.loggedIn) {
                    // 新会话(本地插入、服务器可能还没落库): 尝试跳过 resume
                    val ok = client.sendMessage(s.id, text)
                    if (!ok) {
                        // resume 失败可能是新会话还没落库,用 skipResume 重试
                        client.sendMessageNewSession(s.id, text)
                    }
                } else {
                    client.sendMessage(s.id, text)
                }
                delay(800)
                refreshMessages()
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ================= 附件发送 =================

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
        val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, REQ_CAMERA)
        } else {
            toast(getString(R.string.no_camera))
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
            REQ_CAMERA -> data?.extras?.get("data")?.let { bmp ->
                sendBitmapImage(bmp as android.graphics.Bitmap)
            } ?: toast(getString(R.string.camera_failed))
            REQ_IMAGE -> data?.data?.let { uri ->
                sendUriImage(uri)
            }
            REQ_FILE -> data?.data?.let { uri ->
                sendUriFile(uri)
            }
        }
    }

    private fun sendBitmapImage(bmp: android.graphics.Bitmap) {
        val client = currentApi ?: return
        scope.launch {
            toast(getString(R.string.img_uploading))
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            val dataUrl = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            val ok = client.sendImage(currentSession!!.id, dataUrl, "camera_${System.currentTimeMillis()}.jpg", etMessage.text.toString().trim())
            etMessage.setText("")
            toast(if (ok) getString(R.string.img_sent) else getString(R.string.msg_failed, "unknown"))
            delay(800)
            refreshMessages()
        }
    }

    private fun sendUriImage(uri: android.net.Uri) {
        val client = currentApi ?: return
        scope.launch {
            toast(getString(R.string.img_uploading))
            val dataUrl = readUriAsDataUrl(uri)
            if (dataUrl == null) {
                toast(getString(R.string.read_image_failed))
                return@launch
            }
            val name = queryFileName(uri) ?: "image_${System.currentTimeMillis()}.jpg"
            val ok = client.sendImage(currentSession!!.id, dataUrl, name, etMessage.text.toString().trim())
            etMessage.setText("")
            toast(if (ok) getString(R.string.img_sent) else getString(R.string.msg_failed, "unknown"))
            delay(800)
            refreshMessages()
        }
    }

    private fun sendUriFile(uri: android.net.Uri) {
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
            val caption = etMessage.text.toString().trim()
            val text = if (caption.isEmpty()) getString(R.string.file_uploaded, serverPath) else "$caption\n文件: $serverPath"
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
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
