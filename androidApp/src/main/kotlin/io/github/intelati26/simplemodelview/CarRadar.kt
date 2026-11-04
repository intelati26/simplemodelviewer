package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import simplemodelview.FrameTarget
import simplemodelview.MrmsCatalog
import simplemodelview.MrmsProduct
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.Units
import simplemodelview.View
import simplemodelview.mrmsHasHalo
import simplemodelview.mrmsStep
import simplemodelview.styleIn

/**
 * What the car display shows: the newest MRMS scan of one product over one sector, drawn by the same renderer as the phone's MRMS page. A few products
 * and the sectors can be stepped through; there is no loop (a moving picture does not belong on a driver's screen). [onChange] is called when the picture,
 * the title or the message changes, so the screen can redraw.
 */
internal class CarRadar(private val scope: CoroutineScope, private val onChange: () -> Unit) {
    private val renderer = MapRenderer(AppContainer.core, AppContainer.lines)
    private var job: Job? = null

    /** The few products that are useful at a glance in the car. */
    val products: List<MrmsProduct> = listOfNotNull(
        MrmsCatalog.byId("MergedReflectivityQCComposite_00.50"),
        MrmsCatalog.all.firstOrNull { it.id.startsWith("RotationTrack") && it.id.contains("60min") },
        MrmsCatalog.byId("MESH_00.50"),
        MrmsCatalog.byId("VIL_00.50"),
    ).ifEmpty { listOf(MrmsCatalog.default) }
    val sectors: List<Sector> = Sector.all

    var productIndex = 0; private set
    var sectorIndex = 0; private set
    var bitmap: Bitmap? = null; private set
    var validTime: Long? = null; private set
    var message: String? = "Loading…"; private set
    var dark = true

    val product: MrmsProduct get() = products[productIndex]
    val sector: Sector get() = sectors[sectorIndex]

    fun stepProduct(delta: Int) { productIndex = Math.floorMod(productIndex + delta, products.size); refresh() }
    fun stepSector(delta: Int) { sectorIndex = Math.floorMod(sectorIndex + delta, sectors.size); refresh() }

    /** Looks for the newest scan of the product and draws it over the sector. A newer request replaces one still running. */
    fun refresh() {
        job?.cancel()
        val product = product
        val sector = sector
        message = "Loading…"
        onChange()
        job = scope.launch {
            try {
                val repo = AppContainer.mrms
                val files = repo.listFiles(product, System.currentTimeMillis() / 1000)
                val file = files.lastOrNull() ?: throw IllegalStateException("No recent ${product.title} scans")
                val step = mrmsStep(product.cellDegrees, sector.west, sector.east, 1200, dataSaver = false)
                val margin = 0.05
                val frame = repo.loadFrame(product, file, sector.west - margin, sector.south - margin, sector.east + margin, sector.north + margin, step, FrameTarget.Planned)
                val grid = frame.window.grid
                val picture = Product(product.directory, product.displayTitle, product.group, product.styleIn(Units()), hasMembers = false, timeDimension = null, grid = grid)
                val view = View(sector.name, 0, 0, grid.columns, grid.rows, 1, 1, grid)
                val map = withContext(Dispatchers.Default) {
                    renderer.render(picture, picture.style, frame.window.values, emptyList(), view, dark, contours = false, halo = mrmsHasHalo(product))
                }
                bitmap = map
                validTime = file.validTime
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Could not load the scan"
            }
            onChange()
        }
    }

    fun cancel() { job?.cancel() }
}
