/*
 * SPDX-FileCopyrightText: 2023-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.panpf.zoomimage.GlideZoomImageView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ext.fade
import org.lineageos.glimpse.ext.load
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.Thumbnail
import org.lineageos.glimpse.utils.CapturedFrameCache
import org.lineageos.glimpse.viewmodels.LocalPlayerViewModel

class MediaViewerAdapter(
    private val localPlayerViewModel: LocalPlayerViewModel,
) : ListAdapter<Media, MediaViewerAdapter.MediaViewHolder>(UniqueItemDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MediaViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.media_view, parent, false),
    )

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: MediaViewHolder) {
        super.onViewAttachedToWindow(holder)

        holder.onViewAttachedToWindow()
    }

    override fun onViewDetachedFromWindow(holder: MediaViewHolder) {
        holder.onViewDetachedFromWindow()

        super.onViewDetachedFromWindow(holder)
    }

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Views
        private val imageView = view.findViewById<GlideZoomImageView>(R.id.imageView)

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val playerControlView =
            view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
        private val playerView = view.findViewById<PlayerView>(R.id.playerView)

        // زر التقاط لقطة من الفيديو
        // ملاحظة: أضف هذا الزر في ملف media_view.xml بالمعرّف captureFrameButton
        private val captureFrameButton = view.findViewById<MaterialButton>(R.id.captureFrameButton)

        private var media: Media? = null
        private var isCurrentlyDisplayedView = false
        private var captureJob: Job? = null

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val mediaPositionObserver: (Int?) -> Unit = { currentPosition: Int? ->
            isCurrentlyDisplayedView = currentPosition == bindingAdapterPosition

            val isNowVideoPlayer = isCurrentlyDisplayedView && media?.mediaType == MediaType.VIDEO

            imageView.isVisible = !isNowVideoPlayer
            playerView.isVisible = isNowVideoPlayer
            captureFrameButton.isVisible = isNowVideoPlayer

            if (!isNowVideoPlayer || localPlayerViewModel.fullscreenMode.value) {
                playerControlView.hideImmediately()
            } else {
                playerControlView.show()
            }

            val player = when (isNowVideoPlayer) {
                true -> localPlayerViewModel.exoPlayer
                false -> null
            }

            playerView.player = player
            playerControlView.player = player
        }

        private val sheetsHeightObserver = { sheetsHeight: Pair<Int, Int> ->
            if (!localPlayerViewModel.fullscreenMode.value) {
                val (topHeight, bottomHeight) = sheetsHeight

                playerControlView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = topHeight
                    bottomMargin = bottomHeight
                }
            }
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val fullscreenModeObserver = { fullscreenMode: Boolean ->
            if (media?.mediaType == MediaType.VIDEO) {
                playerControlView.fade(!fullscreenMode)
            }
        }

        private var observersJob: Job? = null

        init {
            imageView.setOnClickListener {
                localPlayerViewModel.toggleFullscreenMode()
            }
            playerView.setOnClickListener {
                localPlayerViewModel.toggleFullscreenMode()
            }

            captureFrameButton.setOnClickListener {
                captureCurrentFrame()
            }
        }

        fun bind(media: Media) {
            this.media = media

            imageView.load(media.uri)
        }

        fun onViewAttachedToWindow() {
            observersJob = itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                launch {
                    localPlayerViewModel.mediaPosition.collectLatest(mediaPositionObserver)
                }
                launch {
                    localPlayerViewModel.sheetsHeight.collectLatest(sheetsHeightObserver)
                }
                launch {
                    localPlayerViewModel.fullscreenMode.collectLatest(fullscreenModeObserver)
                }
            }
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        fun onViewDetachedFromWindow() {
            observersJob?.cancel()
            observersJob = null

            captureJob?.cancel()
            captureJob = null

            playerView.player = null
            playerControlView.player = null
        }

        /**
         * يلتقط الإطار الحالي من الفيديو، يحدّث الصورة فورًا، ثم يحفظها في المجلد المخفي.
         */
        private fun captureCurrentFrame() {
            val media = this.media?.takeIf { it.mediaType == MediaType.VIDEO } ?: return
            val context = itemView.context
            val positionUs = localPlayerViewModel.exoPlayer.currentPosition * 1000

            captureJob?.cancel()
            captureJob = itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                val frame = withContext(Dispatchers.IO) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, media.uri)
                            retriever.getFrameAtTime(
                                positionUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )
                        } finally {
                            retriever.release()
                        }
                    }.getOrNull()
                } ?: return@launch

                val thumbnail = frame.scaledToThumbnail()

                // تحديث فوري للصورة المعروضة في المشغل
                Glide.with(imageView).load(thumbnail).into(imageView)

                // حفظ نسخة دائمة في المجلد المخفي على التخزين الخارجي
                withContext(Dispatchers.IO) {
                    runCatching {
                        CapturedFrameCache.save(media, thumbnail)
                    }
                }
            }
        }

        private fun Bitmap.scaledToThumbnail(): Bitmap {
            val maxSize = Thumbnail.MAX_THUMBNAIL_SIZE
            if (width <= maxSize && height <= maxSize) return this

            val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
            return Bitmap.createScaledBitmap(
                this,
                (width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
    }
}
