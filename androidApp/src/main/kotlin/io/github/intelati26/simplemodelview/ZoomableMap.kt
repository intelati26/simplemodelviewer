package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import simplemodelview.GlobeCamera
import simplemodelview.globeForward
import simplemodelview.visibleFractions

/**
 * A map picture with pinch-zoom, pan, double-tap zoom and tap-for-value. Tap positions are converted back through the zoom and pan
 * and reported as fractions of the picture; [onViewport] gets the visible part (left, top, right, bottom fractions).
 */
@Composable
internal fun ZoomableMap(
    bitmap: android.graphics.Bitmap, crosshair: Pair<Float, Float>?, description: String,
    onViewport: (Float, Float, Float, Float) -> Unit, onTap: (Float, Float) -> Unit,
) {
    var scale by remember(bitmap.width, bitmap.height) { mutableStateOf(1f) }
    var offset by remember(bitmap.width, bitmap.height) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // tell the view model which part of the picture is on screen, for "export this screen area"
    LaunchedEffect(scale, offset, size, bitmap) {
        if (size.width == 0) return@LaunchedEffect
        val f = visibleFractions(size.width.toFloat(), size.height.toFloat(), bitmap.width.toFloat() / bitmap.height, scale, offset.x, offset.y)
        onViewport(f[0], f[1], f[2], f[3])
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 12f)
                    // keep the point under the fingers fixed, then pan
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    offset = (offset + (centroid - centre - offset) * (1f - newScale / scale) + pan)
                    scale = newScale
                    val maxX = size.width * (scale - 1f) / 2f
                    val maxY = size.height * (scale - 1f) / 2f
                    offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val aspect = bitmap.width.toFloat() / bitmap.height
        Box(
            Modifier.aspectRatio(aspect)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onTap = { p -> onTap(p.x / this.size.width, p.y / this.size.height) },
                        // zoom in on that spot (the wide US map leaves room above and below it), or back out
                        onDoubleTap = { q ->
                            if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else {
                                val target = 2.5f
                                val t = (q - Offset(this.size.width / 2f, this.size.height / 2f)) * (1f - target)   // keeps that point under the finger
                                val maxX = size.width * (target - 1f) / 2f
                                val maxY = size.height * (target - 1f) / 2f
                                scale = target
                                offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                            }
                        },
                    )
                },
        ) {
            Image(image, contentDescription = description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            crosshair?.let { (fx, fy) ->
                Canvas(Modifier.fillMaxSize()) {
                    val x = fx * this.size.width
                    val y = fy * this.size.height
                    val r = 10.dp.toPx() / scale
                    val stroke = 1.5.dp.toPx() / scale
                    drawLine(Color.Black, Offset(x - r, y), Offset(x + r, y), stroke * 2.4f)
                    drawLine(Color.Black, Offset(x, y - r), Offset(x, y + r), stroke * 2.4f)
                    drawLine(Color.White, Offset(x - r, y), Offset(x + r, y), stroke)
                    drawLine(Color.White, Offset(x, y - r), Offset(x, y + r), stroke)
                }
            }
        }
    }
}

/** The globe: drag to turn it, pinch to zoom, tap for a value. The picture is drawn by the core at the screen's pixel size. */
@Composable
internal fun GlobeSurface(
    bitmap: android.graphics.Bitmap?, cam: GlobeCamera, tapLatLon: Pair<Double, Double>?, description: String,
    onSize: (Int, Int) -> Unit, onGesture: (Float, Float, Float) -> Unit, onTap: (Float, Float) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it; onSize(it.width, it.height) }
            .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> onGesture(pan.x, pan.y, zoom) } }
            .pointerInput(Unit) { detectTapGestures { p -> onTap(p.x, p.y) } },
    ) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        tapLatLon?.let { (lat, lon) ->
            val p = globeForward(cam, size.width, size.height, lat, lon)
            if (p != null) Canvas(Modifier.fillMaxSize()) {
                val x = p[0].toFloat(); val y = p[1].toFloat(); val r = 10.dp.toPx(); val w = 1.5.dp.toPx()
                drawLine(Color.Black, Offset(x - r, y), Offset(x + r, y), w * 2.4f); drawLine(Color.Black, Offset(x, y - r), Offset(x, y + r), w * 2.4f)
                drawLine(Color.White, Offset(x - r, y), Offset(x + r, y), w); drawLine(Color.White, Offset(x, y - r), Offset(x, y + r), w)
            }
        }
    }
}
