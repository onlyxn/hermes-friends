package com.hermes.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
                h.preview.text = s.preview.ifEmpty { h.itemView.context.getString(R.string.loading) }
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
 * 附件槽位适配器: 待发送的图片/文件(缩略图 + 删除)
 */
data class PendingAttachment(
    val uri: android.net.Uri? = null,
    val bitmap: android.graphics.Bitmap? = null,
    val name: String,
    val isImage: Boolean
)

class AttachmentAdapter(
    private val items: MutableList<PendingAttachment>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<AttachmentAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.attThumb)
        val name: TextView = v.findViewById(R.id.attName)
        val remove: TextView = v.findViewById(R.id.attRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attachment, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        if (a.bitmap != null) {
            holder.thumb.setImageBitmap(a.bitmap)
            holder.name.visibility = View.GONE
        } else if (a.isImage && a.uri != null) {
            holder.thumb.setImageURI(a.uri)
            holder.name.visibility = View.GONE
        } else {
            // 文件: 显示文件图标(用文本替代) + 文件名
            holder.thumb.setImageResource(android.R.drawable.ic_menu_myplaces)
            holder.name.text = a.name
            holder.name.visibility = View.VISIBLE
        }
        holder.remove.visibility = View.VISIBLE
        holder.remove.setOnClickListener { onRemove(position) }
    }
}

/**
 * 消息气泡适配器。
 * 自动识别内容中的 MEDIA:<path> 标记,显示下载按钮 + 图片缩略图。
 */
class MessageAdapter(
    private val items: List<HermesMessage>,
    private val onDownload: (String) -> Unit,  // 传入 MEDIA 路径
    private val onLoadImage: (String, ImageView) -> Unit  // 加载图片(下载+显示)
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val layoutUser: View = v.findViewById(R.id.layoutUser)
        val layoutAssistant: View = v.findViewById(R.id.layoutAssistant)
        val bubbleUser: TextView = v.findViewById(R.id.bubbleUser)
        val bubbleAssistant: TextView = v.findViewById(R.id.bubbleAssistant)
        val imgContainerUser: LinearLayout = v.findViewById(R.id.imgContainerUser)
        val imgContainerAssistant: LinearLayout = v.findViewById(R.id.imgContainerAssistant)
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
        // 去掉 MEDIA:/@image: 行,只显示正文
        val displayText = m.content
            .lines()
            .filter { !it.trim().startsWith("MEDIA:") && !it.trim().startsWith("@image:") }
            .joinToString("\n")
            .trim()
        // 所有图片路径(用于缩略图)
        val imagePaths = mediaPaths.filter { isImagePath(it) }

        if (m.role == "user") {
            holder.layoutUser.visibility = View.VISIBLE
            holder.layoutAssistant.visibility = View.GONE
            holder.bubbleUser.text = displayText.ifEmpty { holder.itemView.context.getString(R.string.image_or_file) }
            bindImages(holder.imgContainerUser, imagePaths)
            bindDownloadButton(holder.btnDownloadUser, mediaPaths)
        } else {
            holder.layoutUser.visibility = View.GONE
            holder.layoutAssistant.visibility = View.VISIBLE
            // 助手气泡: 空内容显示"…"(工作中), 否则显示正文或兜底
            holder.bubbleAssistant.text = displayText.ifEmpty { "…" }
            // ★ 工作状态气泡(打字动画): 固定宽度为三个点, 避免切换时宽度跳动
            // (占位气泡初始内容为空, 也要固定, 之后动画直接改文本不会重新绑定)
            if (displayText in TYPING_STEPS || displayText.isEmpty()) {
                holder.bubbleAssistant.minWidth = (52 * holder.itemView.resources.displayMetrics.density).toInt()
                holder.bubbleAssistant.gravity = android.view.Gravity.CENTER
            } else {
                holder.bubbleAssistant.minWidth = 0
                holder.bubbleAssistant.gravity = android.view.Gravity.START
            }
            bindImages(holder.imgContainerAssistant, imagePaths)
            bindDownloadButton(holder.btnDownloadAssistant, mediaPaths)
        }
    }

    /** 多图缩略图: 每张图动态添加一个 ImageView(竖排) */
    private fun bindImages(container: LinearLayout, imagePaths: List<String>) {
        container.removeAllViews()
        if (imagePaths.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        imagePaths.forEach { path ->
            val img = ImageView(ctx)
            img.layoutParams = LinearLayout.LayoutParams(
                (200 * density).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            img.adjustViewBounds = true
            img.maxHeight = (280 * density).toInt()
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setBackgroundResource(if (container.id == R.id.imgContainerUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_assistant)
            img.setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            val lp = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (4 * density).toInt()
            img.layoutParams = lp
            container.addView(img)
            onLoadImage(path, img)
        }
    }

    private fun bindDownloadButton(btn: Button, mediaPaths: List<String>) {
        if (mediaPaths.isEmpty()) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        btn.text = if (mediaPaths.size == 1) {
            btn.context.getString(R.string.download_attachment)
        } else {
            btn.context.getString(R.string.download_attachment_n, mediaPaths.size)
        }
        btn.setOnClickListener {
            onDownload(mediaPaths.first())
        }
    }

    companion object {
        private val IMAGE_EXT = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        // 打字动画步骤(用于固定气泡宽度)
        val TYPING_STEPS = setOf(".", "..", "...", "…")

        /** 从消息内容里提取所有图片/附件路径(支持 MEDIA: 和 @image: 两种标记) */
        fun extractMediaPaths(content: String): List<String> {
            val result = mutableListOf<String>()
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("MEDIA:")) {
                    result.add(t.removePrefix("MEDIA:").trim())
                } else if (t.startsWith("@image:")) {
                    result.add(t.removePrefix("@image:").trim())
                }
            }
            return result.filter { it.isNotEmpty() }
        }

        /** 判断路径是否是图片 */
        fun isImagePath(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXT
        }
    }
}
