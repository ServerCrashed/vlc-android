/*
 * ************************************************************************
 *  VideoPlayerSubtitlesDelegate.kt
 * *************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.gui.video

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.vlc.R
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Keeps the subtitles surface inside the screen, whatever the video scale mode is.
 *
 * libvlc gives the subtitles surface the exact same bounds as the video surface. When the video
 * is zoomed/cropped (fit screen, original size...), the video surface is bigger than the screen
 * and the subtitles are cut along with the picture.
 *
 * This delegate lays out the subtitles surface on its own: it always gets the biggest size that
 * fits in the screen while keeping the media aspect ratio (the "best fit" bounds), so the subtitles
 * are never cut nor distorted. This box is stuck to the bottom of the visible picture, so that the
 * subtitles do not float in the middle of the screen when the video fills a portrait screen.
 */
class VideoPlayerSubtitlesDelegate(private val videoTrackProvider: () -> IMedia.VideoTrack?) {

    private var surfaceFrame: FrameLayout? = null
    private var videoSurface: View? = null
    private var subtitlesSurface: View? = null

    private val updateRunnable = Runnable { update() }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleUpdate() }

    /**
     * libvlc gives its own layout params to the subtitles surface on every video layout change.
     * Fix the subtitles bounds right away, in the same layout pass, so that a frame is never drawn
     * (and the subtitles surface never resized) with the libvlc bounds: this would flicker.
     */
    private var correctingLayout = false
    private val subtitlesLayoutChangeListener = View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
        if (correctingLayout) return@OnLayoutChangeListener
        computeBounds()?.let {
            if (it.left != left || it.top != top || it.right != right || it.bottom != bottom) {
                correctingLayout = true
                view.layout(it.left, it.top, it.right, it.bottom)
                correctingLayout = false
            }
        }
        // Layout params cannot be safely changed during a layout pass
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        subtitlesSurface?.let {
            it.removeCallbacks(updateRunnable)
            it.post(updateRunnable)
        }
    }

    /**
     * Start managing the subtitles surface. Has to be called once the libvlc views are attached
     *
     * @param videoLayout the layout hosting the video and subtitles surfaces
     */
    fun attach(videoLayout: VLCVideoLayout) {
        detach()
        val frame = videoLayout.findViewById<FrameLayout>(R.id.player_surface_frame) ?: return
        val video = frame.findViewById<View>(R.id.surface_video) ?: return
        val subtitles = frame.findViewById<View>(R.id.surface_subtitles) ?: return
        surfaceFrame = frame
        videoSurface = video
        subtitlesSurface = subtitles
        frame.addOnLayoutChangeListener(layoutChangeListener)
        video.addOnLayoutChangeListener(layoutChangeListener)
        subtitles.addOnLayoutChangeListener(subtitlesLayoutChangeListener)
        subtitles.post(updateRunnable)
    }

    /**
     * Stop managing the subtitles surface
     */
    fun detach() {
        subtitlesSurface?.removeCallbacks(updateRunnable)
        surfaceFrame?.removeOnLayoutChangeListener(layoutChangeListener)
        videoSurface?.removeOnLayoutChangeListener(layoutChangeListener)
        subtitlesSurface?.removeOnLayoutChangeListener(subtitlesLayoutChangeListener)
        surfaceFrame = null
        videoSurface = null
        subtitlesSurface = null
    }

    /**
     * Make the subtitles surface layout params match [computeBounds]
     */
    private fun update() {
        val video = videoSurface ?: return
        val subtitles = subtitlesSurface ?: return
        val videoLp = video.layoutParams ?: return
        val subtitlesLp = subtitles.layoutParams ?: return
        if (videoLp.width <= 0 || videoLp.height <= 0) return
        val bounds = computeBounds()
        if (bounds == null) {
            // Use the libvlc layout
            if (subtitlesLp !== videoLp) subtitles.layoutParams = videoLp
            return
        }
        // libvlc shares the video layout params instance with the subtitles surface: never modify it
        if (subtitlesLp !== videoLp && subtitlesLp is FrameLayout.LayoutParams && subtitlesLp.width == bounds.width()
                && subtitlesLp.height == bounds.height() && subtitlesLp.topMargin == bounds.top) return
        subtitles.layoutParams = FrameLayout.LayoutParams(bounds.width(), bounds.height(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = bounds.top }
    }

    /**
     * Compute the subtitles surface bounds, in the surface frame coordinates
     *
     * @return the bounds, or null if the libvlc layout has to be used
     */
    private fun computeBounds(): Rect? {
        val frame = surfaceFrame ?: return null
        val video = videoSurface ?: return null
        val videoLp = video.layoutParams ?: return null

        val frameWidth = frame.width
        val frameHeight = frame.height
        val videoWidth = videoLp.width
        val videoHeight = videoLp.height
        // The video surface is not explicitly sized (OpenGL vout, PiP): libvlc places everything itself
        if (frameWidth <= 0 || frameHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null

        // Use the media aspect ratio: the video surface is distorted by the forced ratios (fill, 4:3...)
        val ar = sourceAspectRatio() ?: videoWidth.toDouble() / videoHeight

        // Biggest size fitting in the frame with the media aspect ratio
        var width = frameWidth
        var height = frameHeight
        if (ar * frameHeight > frameWidth)
            height = ceil(frameWidth / ar).toInt().coerceAtMost(frameHeight)
        else
            width = ceil(frameHeight * ar).toInt().coerceAtMost(frameWidth)

        // The video already (nearly) has these bounds: just use the libvlc layout
        if (abs(width - videoWidth) <= videoWidth * TOLERANCE && abs(height - videoHeight) <= videoHeight * TOLERANCE) return null

        // Stick the subtitles to the bottom of the visible picture instead of the middle of the screen
        val videoBottom = if (video.height > 0) video.bottom.coerceAtMost(frameHeight) else (frameHeight + height) / 2
        val top = (videoBottom - height).coerceIn(0, frameHeight - height)
        val left = (frameWidth - width) / 2
        return Rect(left, top, left + width, top + height)
    }

    /**
     * @return the display aspect ratio of the current video track, or null if unknown
     */
    private fun sourceAspectRatio(): Double? {
        val track = runCatching { videoTrackProvider() }.getOrNull() ?: return null
        if (track.width <= 0 || track.height <= 0) return null
        val sar = if (track.sarNum > 0 && track.sarDen > 0) track.sarNum.toDouble() / track.sarDen else 1.0
        val ar = track.width * sar / track.height
        // The video is rotated by 90° or 270°
        return if (track.orientation >= IMedia.VideoTrack.Orientation.LeftTop) 1 / ar else ar
    }

    companion object {
        // libvlc slightly oversizes the video surface to crop the decoder padding
        private const val TOLERANCE = 0.02
    }
}
