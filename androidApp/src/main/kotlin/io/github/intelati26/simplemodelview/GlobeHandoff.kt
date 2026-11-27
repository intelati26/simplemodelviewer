package io.github.intelati26.simplemodelview

import simplemodelview.Core
import simplemodelview.GlobeCamera
import simplemodelview.MapFocus
import simplemodelview.Sector
import simplemodelview.sectorForGlobe
import simplemodelview.View
import simplemodelview.focusFromGlobe
import simplemodelview.globeFitting
import simplemodelview.viewportBox

/**
 * Keeps the place when a page goes between its flat map and its globe: the map's on-screen box becomes the globe's first view ([enter] waits for the
 * globe's size when it has not been on screen yet), and the globe's on-screen box ([leave]) becomes the zoom of the map's next picture ([takeFocus]).
 */
internal class GlobeHandoff(private val core: Core) {
    var enter: DoubleArray? = null
    private var leaveCam: GlobeCamera? = null
    private var leaveW = 0
    private var leaveH = 0
    val leaving get() = leaveCam != null

    /** The globe being left, as turned now: the map's next picture zooms to what it shows. */
    fun leaveFrom(cam: GlobeCamera, w: Int, h: Int) { leaveCam = cam; leaveW = w; leaveH = h }
    fun cancelLeave() { leaveCam = null }

    /** The lat / lon box of what the map shows now: the part of [view] inside the viewport fractions. */
    fun boxOf(view: View?, viewport: FloatArray): DoubleArray? =
        view?.let { v -> viewportBox(v, viewport) { c, r -> core.gridToLatLon(v.grid, c, r) } }

    /** A globe that has [box] filling a [w] x [h] screen. */
    fun fit(box: DoubleArray, w: Int, h: Int): GlobeCamera {
        val minR = 0.3 * minOf(w, h)
        return globeFitting(box, w, h, minR, minR * 60)
    }

    /** What the map's picture [v] should zoom to, once: the part of it the globe showed, or null. */
    fun takeFocus(v: View): MapFocus? {
        val cam = leaveCam ?: return null
        leaveCam = null
        return focusFromGlobe(v, cam, leaveW, leaveH) { c, r -> core.gridToLatLon(v.grid, c, r) }
    }

    /**
     * A page switches its globe [on] or off: on, the globe's first view is the map's box (now if the globe's size is known, else when it is: see [enter]).
     * Returns the camera to start the globe with, or null to keep the page's.
     */
    fun switching(on: Boolean, view: View?, viewport: FloatArray, globeW: Int, globeH: Int): GlobeCamera? {
        val box = if (on) boxOf(view, viewport) else null
        if (on) cancelLeave()
        enter = if (globeW == 0) box else null
        return if (box != null && globeW > 0) fit(box, globeW, globeH) else null
    }

    /** A page goes from its globe (turned as [cam]) to the flat map: the sector that holds what the globe shows (the map then zooms to it), or null before the globe has a size. */
    fun leaving(cam: GlobeCamera, globeW: Int, globeH: Int, sectors: List<Sector> = Sector.all): Sector? {
        if (globeW == 0) return null
        leaveFrom(cam, globeW, globeH)
        return sectorForGlobe(cam, globeW, globeH, sectors)
    }
}
