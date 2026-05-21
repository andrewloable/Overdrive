package com.overdrive.app.ui.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for displaying recording files with video thumbnails.
 * Supports multi-select mode for batch operations.
 */
class RecordingAdapter(
    private val onPlay: (RecordingFile) -> Unit,
    private val onDelete: (RecordingFile) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : ListAdapter<RecordingFile, RecordingAdapter.RecordingViewHolder>(RecordingDiffCallback()) {
    
    // Cache for thumbnails — size-bounded LRU. The cap is set in BYTES so a
    // mix of small sidecar JPEGs (~50 KB decoded) and full-res
    // MediaMetadataRetriever frames (~1 MB on a 1080p clip) coexist without
    // unbounded growth as the user scrolls through hundreds of recordings.
    // 8 MB ≈ 100 sidecar thumbs OR ~16 full-res frames, comfortable for a 4 GB
    // RAM head unit.
    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    
    // Multi-select state
    var selectMode = false
        private set
    private val selectedItems = mutableSetOf<String>() // paths
    
    fun enterSelectMode() {
        selectMode = true
        selectedItems.clear()
        notifyDataSetChanged()
    }
    
    fun exitSelectMode() {
        selectMode = false
        selectedItems.clear()
        notifyDataSetChanged()
    }
    
    fun selectAll() {
        for (i in 0 until itemCount) {
            selectedItems.add(getItem(i).path)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }
    
    fun deselectAll() {
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }
    
    fun getSelectedRecordings(): List<RecordingFile> {
        return currentList.filter { it.path in selectedItems }
    }
    
    val selectedCount: Int get() = selectedItems.size
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return RecordingViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvCameraId: TextView = itemView.findViewById(R.id.tvCameraId)
        private val tvRecordingTime: TextView = itemView.findViewById(R.id.tvRecordingTime)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        private val tvFilename: TextView? = itemView.findViewById(R.id.tvFilename)
        private val tvSeverity: TextView? = itemView.findViewById(R.id.tvSeverity)
        private val tvActorSummary: TextView? = itemView.findViewById(R.id.tvActorSummary)
        private val severityStripe: View? = itemView.findViewById(R.id.severityStripe)

        fun bind(recording: RecordingFile) {
            tvCameraId.text = "C${recording.cameraId}"
            tvRecordingTime.text = recording.formattedTime
            tvDuration.text = if (recording.durationMs > 0) recording.formattedDuration else "--:--"
            tvSize.text = recording.formattedSize
            tvFilename?.text = recording.file.name

            // Severity badge + stripe (item 7) — only when v3 sidecar provided severity
            when (recording.peakSeverity?.uppercase()) {
                "CRITICAL" -> {
                    tvSeverity?.visibility = View.VISIBLE
                    tvSeverity?.text = "CRITICAL"
                    tvSeverity?.setBackgroundColor(0xCCEF4444.toInt())
                    severityStripe?.visibility = View.VISIBLE
                    severityStripe?.setBackgroundColor(0xFFEF4444.toInt())
                }
                "ALERT" -> {
                    tvSeverity?.visibility = View.VISIBLE
                    tvSeverity?.text = "ALERT"
                    tvSeverity?.setBackgroundColor(0xCCFF8800.toInt())
                    severityStripe?.visibility = View.VISIBLE
                    severityStripe?.setBackgroundColor(0xFFFF9B3D.toInt())
                }
                else -> {
                    tvSeverity?.visibility = View.GONE
                    severityStripe?.visibility = View.GONE
                }
            }

            // Actor + proximity summary (v3 only). Mid-dot prefix reads cleanly
            // without leaning on emoji glyphs that don't render at SOTA quality
            // on the head-unit font stack.
            val parts = mutableListOf<String>()
            if (recording.personCount > 0)  parts += "${recording.personCount} person"
            if (recording.vehicleCount > 0) parts += "${recording.vehicleCount} vehicle"
            if (recording.bikeCount > 0)    parts += "${recording.bikeCount} bike"
            if (recording.animalCount > 0)  parts += "${recording.animalCount} animal"
            val proxLabel = when (recording.peakProximity?.uppercase()) {
                "VERY_CLOSE" -> "very close"
                "CLOSE" -> "close"
                "MID" -> "mid"
                "FAR" -> "far"
                else -> null
            }
            if (proxLabel != null) parts += proxLabel
            if (parts.isNotEmpty()) {
                tvActorSummary?.visibility = View.VISIBLE
                tvActorSummary?.text = parts.joinToString(" · ")
            } else {
                tvActorSummary?.visibility = View.GONE
            }

            // Load thumbnail
            loadThumbnail(recording)
            
            if (selectMode) {
                cbSelect.visibility = View.VISIBLE
                cbSelect.setOnCheckedChangeListener(null)
                cbSelect.isChecked = recording.path in selectedItems
                btnDelete.visibility = View.GONE

                cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedItems.add(recording.path)
                    else           selectedItems.remove(recording.path)
                    onSelectionChanged?.invoke(selectedItems.size)
                }

                itemView.setOnClickListener {
                    cbSelect.isChecked = !cbSelect.isChecked
                }

                itemView.setOnLongClickListener(null)
            } else {
                cbSelect.setOnCheckedChangeListener(null)
                cbSelect.visibility = View.GONE
                btnDelete.visibility = View.VISIBLE

                btnDelete.setOnClickListener { onDelete(recording) }
                // Tile body opens the player. Long-press = enter multi-select.
                itemView.setOnClickListener { onPlay(recording) }
                itemView.setOnLongClickListener {
                    enterSelectMode()
                    selectedItems.add(recording.path)
                    notifyDataSetChanged()
                    onSelectionChanged?.invoke(selectedItems.size)
                    true
                }
            }
        }
        
        private fun loadThumbnail(recording: RecordingFile) {
            // Cache key includes the hero presence so we don't mix MP4-frame thumbs
            // with hero AI thumbs in memory.
            val cacheKey = recording.heroThumbnailFile?.absolutePath
                ?: recording.path

            val cached = thumbnailCache.get(cacheKey)
            if (cached != null) {
                ivThumbnail.setImageBitmap(cached)
                return
            }

            ivThumbnail.setImageResource(R.color.surface_variant)

            CoroutineScope(Dispatchers.IO).launch {
                // Prefer the hero JPEG written by ThumbnailBuffer next to the MP4.
                // Falls back to MediaMetadataRetriever for legacy clips with no
                // sidecar.
                val thumbnail = recording.heroThumbnailFile?.let { decodeJpeg(it) }
                    ?: extractThumbnail(recording.path)
                if (thumbnail != null) {
                    thumbnailCache.put(cacheKey, thumbnail)
                }

                withContext(Dispatchers.Main) {
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                        getItem(bindingAdapterPosition).path == recording.path &&
                        thumbnail != null) {
                        ivThumbnail.setImageBitmap(thumbnail)
                    }
                }
            }
        }

        private fun decodeJpeg(file: java.io.File): Bitmap? {
            return try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        }

        private fun extractThumbnail(path: String): Bitmap? {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val frame = retriever.getFrameAtTime(1_000_000) // 1 second in
                retriever.release()
                frame
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun clearCache() {
        thumbnailCache.evictAll()
    }
    
    private class RecordingDiffCallback : DiffUtil.ItemCallback<RecordingFile>() {
        override fun areItemsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean {
            return oldItem.path == newItem.path
        }
        
        override fun areContentsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean {
            return oldItem == newItem
        }
    }
}
