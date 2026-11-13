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

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
 * fits in the screen while keeping the video surface aspect ratio (the "best fit" bounds).
 * The subtitles position and size are therefore the same in every scale mode.
 */
class VideoPlayerSubtitlesDelegate {

    private var surfaceFrame: FrameLayout? = null
    private var videoSurface: View? = null
    private var subtitlesSurface: View? = null

    private val updateRunnable = Runnable { update() }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        // Layout params cannot be safely changed during a layout pass
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
        subtitles.addOnLayoutChangeListener(layoutChangeListener)
        subtitles.post(updateRunnable)
    }

    /**
     * Stop managing the subtitles surface
     */
    fun detach() {
        subtitlesSurface?.removeCallbacks(updateRunnable)
        arrayOf(surfaceFrame, videoSurface, subtitlesSurface).forEach { it?.removeOnLayoutChangeListener(layoutChangeListener) }
        surfaceFrame = null
        videoSurface = null
        subtitlesSurface = null
    }

    private fun update() {
        val frame = surfaceFrame ?: return
        val video = videoSurface ?: return
        val subtitles = subtitlesSurface ?: return
        val videoLp = video.layoutParams ?: return
        val subtitlesLp = subtitles.layoutParams ?: return

        val frameWidth = frame.width
        val frameHeight = frame.height
        val videoWidth = videoLp.width
        val videoHeight = videoLp.height
        // The video surface is not explicitly sized (OpenGL vout, PiP): libvlc places everything itself
        if (frameWidth <= 0 || frameHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        // Biggest size fitting in the frame with the video surface aspect ratio
        var width = frameWidth
        var height = frameHeight
        if (videoWidth.toLong() * frameHeight > videoHeight.toLong() * frameWidth)
            height = ceil(frameWidth.toDouble() * videoHeight / videoWidth).toInt()
        else
            width = ceil(frameHeight.toDouble() * videoWidth / videoHeight).toInt()

        // The video already (nearly) has these bounds: just use the libvlc layout
        if (abs(width - videoWidth) <= videoWidth * TOLERANCE && abs(height - videoHeight) <= videoHeight * TOLERANCE) {
            if (subtitlesLp !== videoLp) subtitles.layoutParams = videoLp
            return
        }

        // libvlc shares the video layout params instance with the subtitles surface: never modify it
        if (subtitlesLp !== videoLp && subtitlesLp.width == width && subtitlesLp.height == height) return
        val gravity = (videoLp as? FrameLayout.LayoutParams)?.gravity?.takeIf { it != FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY }
                ?: Gravity.CENTER
        subtitles.layoutParams = FrameLayout.LayoutParams(width, height, gravity)
    }

    companion object {
        // libvlc slightly oversizes the video surface to crop the decoder padding
        private const val TOLERANCE = 0.02
    }
}
