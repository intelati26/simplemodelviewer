package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import simplemodelview.GlobeCamera
import simplemodelview.globeForward
import simplemodelview.visibleFractions

/** The zoom and pan of a map, so that two panes of a compare can share them. */
internal class ZoomState { var scale by mutableStateOf(1f); var offset by mutableStateOf(Offset.Zero) }

/**
 * A map picture with pinch-zoom, pan, double-tap zoom and tap-for-value. Tap positions are converted back through the zoom and pan
 * and reported as fractions of the picture; [onViewport] gets the visible part (left, top, right, bottom fractions).
 */
@Composable
internal fun ZoomableMap(
    bitmap: android.graphics.Bitmap, crosshair: Pair<Float, Float>?, description: String,
    detail: DetailLayer? = null, onViewport: (Float, Float, Float, Float) -> Unit, onTap: (Float, Float) -> Unit,
    focus: simplemodelview.MapFocus? = null, onFocusApplied: () -> Unit = {},
    onScrub: (Float, Float) -> Unit = onTap,
    /** For a compare: the picture is these layers (the first sets the shape) drawn in order on [layersBackground]; [zoom] is shared with the other pane. */
    layers: List<android.graphics.Bitmap>? = null, layersBackground: Color = Color.Unspecified, zoom: ZoomState? = null, reportViewport: Boolean = true,
) {
    // keyed to the picture's shape (not its pixel count): another product over the same area comes with other cells, and keeps the zoom
    val shape = (bitmap.width.toFloat() / bitmap.height * 1000f).roundToInt()
    val z = zoom ?: remember(shape) { ZoomState() }
    var scale by z::scale
    var offset by z::offset
    var size by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // coming back from the globe: zoom to the part of the picture it was showing (this picture's first composition, so the zoom state is fresh)
    LaunchedEffect(focus, bitmap, size) {
        if (focus == null || size.width == 0) return@LaunchedEffect
        val aspect = bitmap.width.toFloat() / bitmap.height
        val fitW = if (size.width.toFloat() / size.height > aspect) size.height * aspect else size.width.toFloat()
        val fitH = fitW / aspect
        val s = minOf(size.width / ((focus.right - focus.left) * fitW), size.height / ((focus.bottom - focus.top) * fitH)).coerceIn(1f, 12f)
        val cx = ((focus.left + focus.right) / 2f - 0.5f) * fitW
        val cy = ((focus.top + focus.bottom) / 2f - 0.5f) * fitH
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        scale = s
        offset = Offset((-cx * s).coerceIn(-maxX, maxX), (-cy * s).coerceIn(-maxY, maxY))
        onFocusApplied()
    }
    // tell the view model which part of the picture is on screen, for "export this screen area"
    LaunchedEffect(scale, offset, size, bitmap) {
        if (size.width == 0 || !reportViewport) return@LaunchedEffect
        val f = visibleFractions(size.width.toFloat(), size.height.toFloat(), bitmap.width.toFloat() / bitmap.height, scale, offset.x, offset.y)
        onViewport(f[0], f[1], f[2], f[3])
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
            // keyed to the picture's size like the zoom state above: a handler started for the previous picture would keep driving that picture's (old) state
            .pointerInput(shape) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 12f)
                    // keep the point under the fingers fixed, then pan
                    val center = Offset(size.width / 2f, size.height / 2f)
                    offset = (offset + (centroid - center - offset) * (1f - newScale / scale) + pan)
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
                // touch and hold, then drag: the crosshair follows the finger and the read-out follows the crosshair (a drag that starts at once still pans)
                .pointerInput(bitmap) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p -> onScrub((p.x / this.size.width).coerceIn(0f, 1f), (p.y / this.size.height).coerceIn(0f, 1f)) },
                        onDrag = { change, _ -> change.consume(); onScrub((change.position.x / this.size.width).coerceIn(0f, 1f), (change.position.y / this.size.height).coerceIn(0f, 1f)) },
                    )
                }
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
            if (layers != null) {
                if (layersBackground != Color.Unspecified) Box(Modifier.fillMaxSize().background(layersBackground))
                for (l in layers) Image(remember(l) { l.asImageBitmap() }, contentDescription = description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else Image(image, contentDescription = description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            // a finer picture of the zoomed-in part, over the overview
            detail?.let { d ->
                val detailImage = remember(d) { d.bitmap.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    val w = this.size.width; val h = this.size.height   // this.size: the picture's, not the screen's `size` above
                    val x = (d.left * w).roundToInt(); val y = (d.top * h).roundToInt()
                    drawImage(detailImage, dstOffset = IntOffset(x, y), dstSize = IntSize((d.right * w).roundToInt() - x, (d.bottom * h).roundToInt() - y))
                }
            }
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

/** Two products of the same area and time one above the other: A is the page's full picture; B is its own field on the shared lines. Zoom, pan and the crosshair are shared. */
@Composable
internal fun ComparePanes(
    modifier: Modifier, a: android.graphics.Bitmap, b: List<android.graphics.Bitmap>, titleA: String, titleB: String, background: Color, crosshair: Pair<Float, Float>?,
    detail: DetailLayer?, onViewport: (Float, Float, Float, Float) -> Unit, onTap: (Float, Float) -> Unit, onScrub: (Float, Float) -> Unit,
    focus: simplemodelview.MapFocus?, onFocusApplied: () -> Unit,
    /** Which pane the product controls change (0 above, 1 below): a tap on a pane selects it. */
    active: Int = 0, onSelect: (Int) -> Unit = {},
) {
    val zoom = remember(a.width, a.height) { ZoomState() }
    androidx.compose.foundation.layout.Column(modifier.fillMaxSize()) {
        // the selected pane's label is in the accent color, with a thin line along its top edge
        @Composable fun label(text: String, selected: Boolean) = androidx.compose.material3.Text(
            text, Modifier.padding(4.dp).background(if (selected) Color(0xE02F5DA8) else Color(0xB0000000)).padding(horizontal = 6.dp, vertical = 1.dp),
            color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ZoomableMap(a, crosshair, "First product", detail = detail, onViewport = onViewport, onTap = { x, y -> onSelect(0); onTap(x, y) }, onScrub = onScrub, focus = focus, onFocusApplied = onFocusApplied, zoom = zoom)
            label(titleA, active == 0)
            if (active == 0) Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF2F5DA8)))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ZoomableMap(b.first(), crosshair, "Second product", onViewport = { _, _, _, _ -> }, onTap = { x, y -> onSelect(1); onTap(x, y) }, onScrub = onScrub, layers = b, layersBackground = background, zoom = zoom, reportViewport = false)
            label(titleB, active == 1)
            if (active == 1) Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF2F5DA8)))
        }
    }
}

/** The globe: drag to turn it, pinch to zoom, tap for a value. The picture is drawn by the core at the screen's pixel size. */
@Composable
internal fun GlobeSurface(
    bitmap: android.graphics.Bitmap?, cam: GlobeCamera, tapLatLon: Pair<Double, Double>?, description: String,
    onSize: (Int, Int) -> Unit, onGesture: (Float, Float, Float) -> Unit, onTap: (Float, Float) -> Unit,
    polar: simplemodelview.PolarCamera? = null, onPolarGesture: ((Float, Float, Float, Float, Float) -> Unit)? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it; onSize(it.width, it.height) }
            .pointerInput(Unit) { detectTransformGestures { centroid, pan, zoom, _ -> if (polar != null && onPolarGesture != null) onPolarGesture(pan.x, pan.y, zoom, centroid.x, centroid.y) else onGesture(pan.x, pan.y, zoom) } }
            .pointerInput(Unit) { detectTapGestures { p -> onTap(p.x, p.y) } },
    ) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        tapLatLon?.let { (lat, lon) ->
            val p = if (polar != null) polar.project(size.width, size.height, lat, lon) else globeForward(cam, size.width, size.height, lat, lon)
            if (p != null) Canvas(Modifier.fillMaxSize()) {
                val x = p[0].toFloat(); val y = p[1].toFloat(); val r = 10.dp.toPx(); val w = 1.5.dp.toPx()
                drawLine(Color.Black, Offset(x - r, y), Offset(x + r, y), w * 2.4f); drawLine(Color.Black, Offset(x, y - r), Offset(x, y + r), w * 2.4f)
                drawLine(Color.White, Offset(x - r, y), Offset(x + r, y), w); drawLine(Color.White, Offset(x, y - r), Offset(x, y + r), w)
            }
        }
    }
}
