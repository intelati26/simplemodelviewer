package simplemodelview

/**
 * The part of the picture that is on screen, as fractions of its width / height: [left, top, right, bottom].
 * The picture is fitted and centred in a container of [w] x [h]; it is then zoomed by [scale] about the container's
 * centre and moved by ([tx], [ty]) pixels.
 */
fun visibleFractions(w: Float, h: Float, aspect: Float, scale: Float, tx: Float, ty: Float): FloatArray {
    val imgW = minOf(w, h * aspect)          // the picture is fitted inside the container
    val imgH = imgW / aspect
    fun qx(p: Float) = ((p - w / 2f - tx) / scale + imgW / 2f) / imgW
    fun qy(p: Float) = ((p - h / 2f - ty) / scale + imgH / 2f) / imgH
    return floatArrayOf(qx(0f), qy(0f), qx(w), qy(h)).map { it.coerceIn(0f, 1f) }.toFloatArray()
}
