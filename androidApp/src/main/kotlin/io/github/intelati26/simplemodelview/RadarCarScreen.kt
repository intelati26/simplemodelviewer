package io.github.intelati26.simplemodelview

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import simplemodelview.formatAge
import simplemodelview.formatClock

/**
 * The car screen: the radar picture fills the map surface, a short pane beside it names the product, the scan time and the sector, with buttons to step
 * through the products and the sectors. The scan is looked for again every two minutes while the screen is showing.
 */
internal class RadarCarScreen(carContext: CarContext) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val radar = CarRadar(scope) { invalidate(); draw() }
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible: Rect? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            surface = surfaceContainer.surface; width = surfaceContainer.width; height = surfaceContainer.height
            draw()
        }
        override fun onVisibleAreaChanged(visibleArea: Rect) { visible = visibleArea; draw() }
        override fun onStableAreaChanged(stableArea: Rect) {}
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) { surface = null }
    }

    init {
        radar.dark = carContext.isDarkMode
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                radar.dark = this@RadarCarScreen.carContext.isDarkMode
                radar.refresh()
                scope.launch { while (isActive) { delay(2 * 60 * 1000L); radar.refresh() } }
            }
            override fun onDestroy(owner: LifecycleOwner) { radar.cancel(); scope.cancel() }
        })
    }

    override fun onGetTemplate(): Template {
        val now = System.currentTimeMillis() / 1000
        val valid = radar.validTime
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(radar.product.displayTitle).addText(radar.message ?: valid?.let { "Valid ${formatClock(it, now)}  ·  ${formatAge(now - it)} ago" } ?: "").build())
            .addRow(Row.Builder().setTitle(radar.sector.name).addText("MRMS, NOAA/NSSL. Not for warnings or safety decisions.").build())
            .addAction(Action.Builder().setTitle("‹ Product").setOnClickListener { radar.stepProduct(-1) }.build())
            .addAction(Action.Builder().setTitle("Product ›").setOnClickListener { radar.stepProduct(1) }.build())
            .build()
        val content = PaneTemplate.Builder(pane)
            .setHeader(Header.Builder().setTitle("Radar").setStartHeaderAction(Action.APP_ICON).build())
            .build()
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("Refresh").setOnClickListener { radar.refresh() }.build())
                    .addAction(Action.Builder().setTitle("Sector ›").setOnClickListener { radar.stepSector(1) }.build())
                    .build()
            )
            .build()
    }

    /** Draws the picture, as large as fits, in the part of the surface the host says is visible (the rest is under its own panels). */
    private fun draw() {
        val s = surface?.takeIf { it.isValid } ?: return
        val canvas = try { s.lockCanvas(null) } catch (e: Exception) { null } ?: return
        try {
            canvas.drawColor(if (radar.dark) Color.rgb(0x20, 0x24, 0x28) else Color.rgb(0xED, 0xEF, 0xF2))
            val bitmap = radar.bitmap ?: return
            val area = visible?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
            val scale = minOf(area.width() / bitmap.width.toFloat(), area.height() / bitmap.height.toFloat())
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = area.left + (area.width() - w) / 2f
            val top = area.top + (area.height() - h) / 2f
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
