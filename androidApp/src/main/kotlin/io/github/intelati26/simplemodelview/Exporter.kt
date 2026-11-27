package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import simplemodelview.Grid
import simplemodelview.Product
import simplemodelview.Style
import simplemodelview.VideoSize
import simplemodelview.View
import java.io.File

/*
 * The one way out of every page: the extractor (GRIB, GeoTIFF, Zarr, ...) of a page gives a [PaneField] for each time step, and from there it is the same flow
 * for every model: draw the field on the area's window, add the page's extras, compose one picture or a compare, scale to the chosen size, write the PNG or
 * the frames of the video. A page only describes the export ([ExportJob]); it does not draw, compose or encode.
 */

/** One time step of an export: its header line (asked for once the panes' fields are extracted), and for a video how many base steps it is held. */
class ExportFrame(val header: () -> String, val hold: Int = 1)

/** What is drawn on a field apart from the field itself. */
class RenderExtras(
    val overlays: List<Overlay> = emptyList(), val alerts: List<AlertDraw> = emptyList(), val underlay: Overlay? = null,
    val fieldLines: List<FieldLines> = emptyList(), val stations: List<StationPlot> = emptyList(), val halo: Boolean = false,
)

/** What an extractor gives for one pane at one time step: the field on its grid, the product (for the legend), and the page's extras for the window it is drawn on. */
class PaneField(
    val grid: Grid, val values: FloatArray, val product: Product, val style: Style = product.style,
    val members: List<String> = emptyList(), val extras: suspend (View) -> RenderExtras = { RenderExtras() },
    /** For a pane that is not drawn like the others: its own window of the grid / drawing choices. */
    val window: ((Grid) -> View)? = null, val drawing: Drawing? = null,
)

/** A pane of the export (a compare has two). [field] extracts the pane's field for the frame at an index; null when it has none near. */
class ExportPane(val title: String, val field: suspend (frame: Int) -> PaneField?)

/** The page's drawing choices for an export. */
class Drawing(val contours: Boolean, val counties: Int, val barbDensity: Int)

/**
 * Everything an export needs, taken from the page when it was asked for. [window] gives the area's window of a grid; [globe] (the page's own drawing of
 * its globe at a pixel size) is used when the area is the globe.
 */
class ExportJob(
    val frames: List<ExportFrame>, val panes: List<ExportPane>, val window: (Grid) -> View, val drawing: Drawing,
    val fileName: (extension: String) -> String, val frameMillis: Int = 250,
    val globe: (suspend (frame: Int, side: Int) -> Pair<Bitmap, PaneField>)? = null,
)

class Exporter(private val renderer: MapRenderer, private val dir: () -> File) {
    /** One picture of the first frame (with both panes in a compare), as a PNG no wider than [size], or null when it cannot be made. */
    suspend fun picture(job: ExportJob, size: VideoSize? = null): File? = withContext(Dispatchers.Default) {
        val image = frameImage(job, 0, GLOBE_EXPORT_SIDE, size?.width ?: 0, HashMap()) ?: return@withContext null
        val compare = job.panes.size > 1 && job.globe == null
        image.savePng(File(dir(), (if (compare) "compare_" else "") + job.fileName("png")))
    }

    /** Every frame as a video of the chosen size; [onProgress] gets (done, total). A frame whose second pane has nothing keeps the last one it had. */
    suspend fun loop(job: ExportJob, size: VideoSize, onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.Default) {
        val n = job.frames.size
        if (n < 2) return@withContext null
        val out = File(dir(), job.fileName("mp4"))
        var exporter: LoopExporter? = null
        val last = HashMap<Int, PaneField>()
        try {
            for (i in 0 until n) {
                var picture = frameImage(job, i, GLOBE_LOOP_SIDE, size.width, last)
                if (picture == null) { onProgress(i + 1, n); continue }
                val (sw, sh) = size.scaled(picture.width, picture.height)
                if (sw != picture.width) picture = Bitmap.createScaledBitmap(picture, sw, sh, true)
                if (exporter == null) exporter = LoopExporter(out, picture.width, picture.height, size.bitrate(picture.width, picture.height, (1000 / job.frameMillis)), frameMillis = job.frameMillis)
                exporter.add(picture, last = i == n - 1, hold = job.frames[i].hold)
                onProgress(i + 1, n)
            }
            exporter?.finish()
            if (exporter == null) null else out
        } catch (e: Exception) {
            exporter?.abort()
            throw e
        }
    }

    /** One finished picture of frame [i]: header, the pane(s), the color key(s). [maxWidth] limits a compare's width. */
    private suspend fun frameImage(job: ExportJob, i: Int, globeSide: Int, maxWidth: Int, last: HashMap<Int, PaneField>): Bitmap? {
        val frame = job.frames[i]
        job.globe?.let { drawGlobe ->
            val (map, pf) = drawGlobe(i, globeSide)
            return renderer.shareImage(map, pf.product, pf.style, frame.header(), pf.members)
        }
        val maps = ArrayList<Triple<Bitmap, PaneField, String>>()
        for ((k, pane) in job.panes.withIndex()) {
            val pf = pane.field(i)?.also { last[k] = it } ?: last[k] ?: return null
            val view = (pf.window ?: job.window)(pf.grid)
            val extras = pf.extras(view)
            val d = pf.drawing ?: job.drawing
            val map = withContext(Dispatchers.Default) {
                renderer.render(pf.product, pf.style, pf.values, pf.members, view, dark = false, extras.overlays, extras.alerts, d.contours, extras.halo, d.barbDensity, d.counties,
                    underlay = extras.underlay, fieldLines = extras.fieldLines, stations = extras.stations)
            }
            maps += Triple(map, pf, pane.title)
        }
        val (a, pa, ta) = maps[0]
        if (maps.size == 1) return renderer.shareImage(a, pa.product, pa.style, frame.header(), pa.members)
        val (b, pb, tb) = maps[1]
        return renderer.shareCompareImage(a, b, ta, tb, pa.product, pa.style, pb.product, pb.style, frame.header(), maxWidth)
    }
}
