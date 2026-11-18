package com.mrmzone.idm.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.util.Fmt

class DownloadAdapter(
    private val onClick: (DownloadItem) -> Unit,
    private val onLongClick: (DownloadItem) -> Unit,
    private val onAction: (DownloadItem) -> Unit,
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private var data: List<DownloadItem> = emptyList()
    val selected = mutableSetOf<Long>()
    val selectionMode get() = selected.isNotEmpty()

    init { setHasStableIds(true) }

    fun submit(list: List<DownloadItem>) {
        // snapshot primitive fields so DiffUtil can detect live changes on shared objects
        val old = snap; val new = list.map { Snap(it) }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        })
        data = list; snap = new
        diff.dispatchUpdatesTo(this)
    }
    private var snap: List<Snap> = emptyList()
    private data class Snap(val id: Long, val name: String, val st: Status, val dl: Long, val tot: Long, val sp: Long, val err: String?, val q: Boolean) {
        constructor(d: DownloadItem) : this(d.id, d.fileName, d.status, d.downloaded, d.totalSize, d.speed, d.error, d.inQueue)
    }

    fun items() = data

    override fun getItemId(position: Int) = data[position].id
    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) = h.bind(data[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val card = v.findViewById<MaterialCardView>(R.id.card)
        private val icon = v.findViewById<ImageView>(R.id.icon)
        private val iconBg = v.findViewById<View>(R.id.iconBg)
        private val name = v.findViewById<TextView>(R.id.name)
        private val progress = v.findViewById<LinearProgressIndicator>(R.id.progress)
        private val info = v.findViewById<TextView>(R.id.info)
        private val status = v.findViewById<TextView>(R.id.status)
        private val action = v.findViewById<ImageButton>(R.id.action)

        fun bind(d: DownloadItem) {
            val ctx = itemView.context
            name.text = d.fileName
            val (ic, col) = catStyle(d.category)
            icon.setImageResource(ic)
            iconBg.background = GradientDrawable().apply { cornerRadius = 12f * ctx.resources.displayMetrics.density; setColor(ContextCompat.getColor(ctx, col)) }

            val sizeTxt = if (d.totalSize > 0) "${Fmt.size(d.downloaded)} / ${Fmt.size(d.totalSize)}" else Fmt.size(d.downloaded)
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = if (d.status == Status.COMPLETED) 100 else d.progress
            val accent = when (d.status) {
                Status.COMPLETED -> R.color.ok
                Status.ERROR -> R.color.err
                Status.PAUSED, Status.QUEUED -> R.color.text_secondary
                else -> R.color.brand_blue
            }
            progress.setIndicatorColor(ContextCompat.getColor(ctx, accent))
            status.setTextColor(ContextCompat.getColor(ctx, accent))

            when (d.status) {
                Status.DOWNLOADING -> {
                    info.text = "${d.progress}% • $sizeTxt"
                    status.text = "${Fmt.speed(d.speed)} • ${Fmt.time(d.timeLeftSec)}"
                    if (d.totalSize <= 0) progress.isIndeterminate = true
                }
                Status.CONNECTING -> { info.text = sizeTxt; status.text = "Connecting…"; progress.isIndeterminate = true }
                Status.MOVING -> { info.text = sizeTxt; status.text = "Saving…"; progress.isIndeterminate = true }
                Status.WAITING -> { info.text = "${d.progress}% • $sizeTxt"; status.text = d.error ?: "Waiting" }
                Status.QUEUED -> { info.text = "${d.progress}% • $sizeTxt"; status.text = "In queue" }
                Status.PAUSED -> { info.text = "${d.progress}% • $sizeTxt"; status.text = "Paused" }
                Status.COMPLETED -> { info.text = "${Fmt.size(d.totalSize)} • ${Fmt.date(d.finishedAt)}"; status.text = "Complete" }
                Status.ERROR -> { info.text = "${d.progress}% • $sizeTxt"; status.text = "Error" }
            }
            if (d.inQueue && d.status != Status.COMPLETED) info.text = "⏱ " + info.text

            action.setImageResource(when {
                d.status == Status.COMPLETED -> R.drawable.ic_folder
                d.status.isActive -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            })
            action.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, if (d.status.isActive) R.color.brand_orange else R.color.brand_blue))
            action.visibility = if (selectionMode) View.GONE else View.VISIBLE
            action.setOnClickListener { onAction(d) }
            card.isChecked = d.id in selected
            itemView.setOnClickListener { onClick(d) }
            itemView.setOnLongClickListener { onLongClick(d); true }
        }
    }

    companion object {
        fun catStyle(c: Category): Pair<Int, Int> = when (c) {
            Category.VIDEO -> R.drawable.ic_cat_video to R.color.cat_video
            Category.MUSIC -> R.drawable.ic_cat_music to R.color.cat_music
            Category.DOCUMENTS -> R.drawable.ic_cat_doc to R.color.cat_doc
            Category.COMPRESSED -> R.drawable.ic_cat_zip to R.color.cat_zip
            Category.PROGRAMS -> R.drawable.ic_cat_app to R.color.cat_app
            Category.IMAGES -> R.drawable.ic_cat_image to R.color.cat_image
            Category.OTHER -> R.drawable.ic_cat_file to R.color.cat_other
        }
    }
}
