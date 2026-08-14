package com.hermes.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话列表条目: 服务器头 / 项目组头 / 会话 */
sealed class SessionRow {
    data class ServerHeader(
        val server: ServerConfig,
        val sessionCount: Int,
        val expanded: Boolean
    ) : SessionRow()

    data class Header(val groupName: String, val count: Int, val expanded: Boolean) : SessionRow()
    data class Item(val session: HermesSession) : SessionRow()
}

class SessionAdapter(
    private val rows: MutableList<SessionRow>,
    private val onClick: (HermesSession) -> Unit,
    private val onToggleGroup: (String) -> Unit,
    private val onToggleServer: (String) -> Unit,
    private val onLongPressServer: (ServerConfig) -> Unit,
    private val onLongPressSession: (HermesSession) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val TYPE_SERVER = 0
    private val TYPE_HEADER = 1
    private val TYPE_ITEM = 2

    class ServerVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvGroupTitle)
        val arrow: TextView = v.findViewById(R.id.tvGroupArrow)
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvGroupTitle)
        val arrow: TextView = v.findViewById(R.id.tvGroupArrow)
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val preview: TextView = v.findViewById(R.id.tvPreview)
        val time: TextView = v.findViewById(R.id.tvTime)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is SessionRow.ServerHeader -> TYPE_SERVER
        is SessionRow.Header -> TYPE_HEADER
        is SessionRow.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SERVER -> ServerVH(inflater.inflate(R.layout.item_group_header, parent, false))
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_group_header, parent, false))
            else -> ItemVH(inflater.inflate(R.layout.item_session, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SessionRow.ServerHeader -> {
                val h = holder as ServerVH
                h.title.text = "${row.server.name}  (${row.sessionCount})"
                h.arrow.text = if (row.expanded) "▾" else "▸"
                h.title.setTextColor(h.itemView.context.getColor(R.color.white))
                h.arrow.setTextColor(h.itemView.context.getColor(R.color.white))
                h.itemView.setBackgroundColor(h.itemView.context.getColor(R.color.primary))
                h.itemView.setOnClickListener { onToggleServer(row.server.id) }
                h.itemView.setOnLongClickListener {
                    onLongPressServer(row.server)
                    true
                }
            }
            is SessionRow.Header -> {
                val h = holder as HeaderVH
                h.title.text = row.groupName + "  (" + row.count + ")"
                h.arrow.text = if (row.expanded) "▾" else "▸"
                h.title.setTextColor(h.itemView.context.getColor(R.color.primary))
                h.arrow.setTextColor(h.itemView.context.getColor(R.color.primary))
                h.itemView.setBackgroundColor(h.itemView.context.getColor(R.color.group_header_bg))
                h.itemView.setOnClickListener { onToggleGroup(row.groupName) }
            }
            is SessionRow.Item -> {
                val h = holder as ItemVH
                val s = row.session
                h.name.text = s.displayName
                h.preview.text = s.preview.ifEmpty { "加载中…" }
                h.time.text = if (s.lastActivity > 0) timeFmt.format(Date(s.lastActivity)) else ""
                h.itemView.setOnClickListener { onClick(s) }
                h.itemView.setOnLongClickListener {
                    onLongPressSession(s)
                    true
                }
            }
        }
    }
}

/**
 * 消息气泡适配器。
 * 自动识别内容中的 MEDIA:<path> 标记,显示下载按钮。
 */
class MessageAdapter(
    private val items: List<HermesMessage>,
    private val onDownload: (String) -> Unit  // 传入 MEDIA 路径
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val layoutUser: View = v.findViewById(R.id.layoutUser)
        val layoutAssistant: View = v.findViewById(R.id.layoutAssistant)
        val bubbleUser: TextView = v.findViewById(R.id.bubbleUser)
        val bubbleAssistant: TextView = v.findViewById(R.id.bubbleAssistant)
        val btnDownloadUser: Button = v.findViewById(R.id.btnDownloadUser)
        val btnDownloadAssistant: Button = v.findViewById(R.id.btnDownloadAssistant)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val mediaPaths = extractMediaPaths(m.content)
        // 去掉 MEDIA: 行,只显示正文
        val displayText = m.content
            .lines()
            .filter { !it.trim().startsWith("MEDIA:") }
            .joinToString("\n")
            .trim()

        if (m.role == "user") {
            holder.layoutUser.visibility = View.VISIBLE
            holder.layoutAssistant.visibility = View.GONE
            holder.bubbleUser.text = displayText.ifEmpty { "(图片/文件)" }
            bindDownloadButton(holder.btnDownloadUser, mediaPaths)
        } else {
            holder.layoutUser.visibility = View.GONE
            holder.layoutAssistant.visibility = View.VISIBLE
            holder.bubbleAssistant.text = displayText.ifEmpty { "(图片/文件)" }
            bindDownloadButton(holder.btnDownloadAssistant, mediaPaths)
        }
    }

    private fun bindDownloadButton(btn: Button, mediaPaths: List<String>) {
        if (mediaPaths.isEmpty()) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        btn.text = if (mediaPaths.size == 1) "⬇ 下载附件" else "⬇ 下载附件 (${mediaPaths.size}个)"
        btn.setOnClickListener {
            onDownload(mediaPaths.first())
        }
    }

    companion object {
        /** 从消息内容里提取所有 MEDIA:<path> 路径 */
        fun extractMediaPaths(content: String): List<String> {
            return content.lines()
                .map { it.trim() }
                .filter { it.startsWith("MEDIA:") }
                .map { it.removePrefix("MEDIA:").trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
