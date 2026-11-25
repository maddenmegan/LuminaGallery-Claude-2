package com.lumina.gallery.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage

/**
 * True multi-touch pinch zoom (1x–5x), pan while zoomed and double-tap zoom.
 *
 * Pager friendliness: at 1x a single finger is NEVER consumed, so the surrounding HorizontalPager swipes normally.
 * Events are consumed only for 2+ fingers or while zoomed (and not when a pan is pushing against the image edge,
 * so you can swipe to the next photo from a zoomed edge).
 */
@Composable
fun ZoomableImage(model: Any?, modifier: Modifier = Modifier, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(box.width / 2f, box.height / 2f)
                            val ns = 2.5f
                            offset = clampOffset((tap - center) * (1f - ns), ns)
                            scale = ns
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = ev.changes.count { it.pressed }
                        val multi = pressed >= 2
                        if (multi || scale > 1.01f) {
                            val zoom = if (multi) ev.calculateZoom() else 1f
                            val pan = ev.calculatePan()
                            val centroid = ev.calculateCentroid(useCurrent = false)
                            val ns = (scale * zoom).coerceIn(1f, 5f)
                            val center = Offset(box.width / 2f, box.height / 2f)
                            val moved = if (centroid.isSpecified) {
                                val rel = centroid - center
                                rel - (rel - offset) * (ns / scale) + pan
                            } else offset + pan
                            val maxX = box.width * (ns - 1f) / 2f
                            val atEdge = !multi && ns > 1.01f &&
                                ((pan.x > 0f && offset.x >= maxX - 1f) || (pan.x < 0f && offset.x <= -maxX + 1f))
                            scale = ns
                            offset = if (ns <= 1.01f) Offset.Zero else clampOffset(moved, ns)
                            if (!atEdge) ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
