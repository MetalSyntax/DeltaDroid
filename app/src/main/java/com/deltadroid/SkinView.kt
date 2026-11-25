package com.deltadroid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.swordfish.libretrodroid.GLRetroView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Dibuja una .deltaskin (info.json + PDF/PNG) y convierte los toques en input, como el
 * ControllerView de Delta: el skin se ajusta al ancho y se pega abajo; la pantalla del juego
 * va en `screens`/`gameScreenFrame` o, si no hay, en el espacio libre (o detrás, si es translúcido).
 */
@SuppressLint("ViewConstructor")
class SkinView(context: Context, skinFile: File, private val system: GameSystem) : View(context) {
    /** Rectángulo (px) donde debe dibujarse el juego y si estamos en horizontal. */
    var onLayout: ((RectF, Boolean) -> Unit)? = null
    var onKey: ((keyCode: Int, down: Boolean) -> Unit)? = null
    var onAnalog: ((source: Int, x: Float, y: Float) -> Unit)? = null
    /** Toque en la pantalla táctil de la DS (px de la vista) o null al soltar. */
    var onTouchScreen: ((PointF?) -> Unit)? = null
    var onMenu: (() -> Unit)? = null

    private class Item(val inputs: Any, val frame: RectF, val hit: RectF, val thumb: JSONObject?)

    private val zip = ZipFile(skinFile)
    private val reps = JSONObject(zip.getInputStream(zip.getEntry("info.json")).reader().readText())
        .getJSONObject("representations").let { it.optJSONObject("iphone") ?: it.getJSONObject("ipad") }

    private var items = emptyList<Item>()
    private var skin: Bitmap? = null
    private var thumb: Bitmap? = null
    private val skinRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pressed = mutableSetOf<String>() // bajo los dedos
    private val held = mutableSetOf<String>()    // retenidos por "Mantener botones"
    private val active = mutableSetOf<String>()  // lo que el núcleo ve pulsado

    /** Al apagarlo se sueltan todos los botones retenidos. */
    var sustain = false
        set(on) {
            field = on
            if (on) return
            (active - pressed).forEach { press(it, false) }
            held.clear()
            active.retainAll(pressed)
        }
    private var stick: PointF? = null
    private var hadTouch = false
    private val haptics = Prefs.haptics(context)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val landscape = w > h
        val ori = if (landscape) "landscape" else "portrait"
        // edgeToEdge = iPhone sin botón Home (~19.5:9), que es la proporción de casi todos los Android actuales
        val tall = max(w, h).toFloat() / min(w, h) > 1.9f
        val rep = (if (tall) reps.optJSONObject("edgeToEdge")?.optJSONObject(ori) else null)
            ?: reps.getJSONObject("standard").getJSONObject(ori)

        val ms = rep.getJSONObject("mappingSize")
        val mw = ms.getDouble("width").toFloat()
        val mh = ms.getDouble("height").toFloat()
        val s = min(w / mw, h / mh)
        skinRect.set((w - mw * s) / 2, h - mh * s, (w + mw * s) / 2, h.toFloat())
        fun map(f: JSONObject) = RectF(
            skinRect.left + f.getDouble("x").toFloat() * s, skinRect.top + f.getDouble("y").toFloat() * s,
            skinRect.left + (f.getDouble("x") + f.getDouble("width")).toFloat() * s,
            skinRect.top + (f.getDouble("y") + f.getDouble("height")).toFloat() * s,
        )

        val defExt = rep.optJSONObject("extendedEdges")
        items = rep.getJSONArray("items").objects().map { o ->
            val f = map(o.getJSONObject("frame"))
            val ext = o.optJSONObject("extendedEdges")
            fun edge(k: String) = (ext?.optDouble(k)?.takeUnless { it.isNaN() } ?: defExt?.optDouble(k, 0.0) ?: 0.0).toFloat() * s
            val inputs: Any = when (val i = o.get("inputs")) {
                is JSONArray -> List(i.length()) { i.getString(it) }
                is JSONObject -> i.keys().asSequence().associateWith { i.getString(it) }
                else -> emptyList<String>()
            }
            Item(inputs, f, RectF(f.left - edge("left"), f.top - edge("top"), f.right + edge("right"), f.bottom + edge("bottom")), o.optJSONObject("thumbstick"))
        }

        val translucent = rep.optBoolean("translucent")
        val screens = rep.optJSONArray("screens")?.objects()
        val game = when {
            screens != null -> screens.map { map(it.getJSONObject("outputFrame")) }.reduce { a, b -> a.apply { union(b) } }
            rep.has("gameScreenFrame") -> map(rep.getJSONObject("gameScreenFrame"))
            !landscape && !translucent -> RectF(0f, 0f, w.toFloat(), skinRect.top)
            else -> RectF(0f, 0f, w.toFloat(), h.toFloat())
        }
        paint.alpha = if (translucent) (Prefs.opacity(context) * 255).toInt() else 255

        val assets = rep.getJSONObject("assets")
        val name = assets.optString("resizable").ifEmpty {
            listOf("large", "medium", "small").map(assets::optString).first { it.isNotEmpty() }
        }
        skin = render(name, skinRect.width().toInt(), skinRect.height().toInt())
        thumb = items.firstNotNullOfOrNull { it.thumb }?.let {
            render(it.getString("name"), (it.getDouble("width") * s).toInt(), (it.getDouble("height") * s).toInt())
        }
        onLayout?.invoke(game, landscape)
        invalidate()
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private fun render(name: String, w: Int, h: Int): Bitmap? {
        val entry = zip.getEntry(name) ?: return null
        if (!name.endsWith(".pdf", true)) {
            val b = zip.getInputStream(entry).use(BitmapFactory::decodeStream) ?: return null
            return Bitmap.createScaledBitmap(b, max(w, 1), max(h, 1), true)
        }
        // PdfRenderer necesita un archivo con seek: se extrae a caché
        val tmp = File(context.cacheDir, "skin.pdf")
        zip.getInputStream(entry).use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val page = renderer.openPage(0)
        val bmp = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); renderer.close(); pfd.close()
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        skin?.let { canvas.drawBitmap(it, null, skinRect, paint) }
        val t = thumb ?: return
        for (item in items) if (item.thumb != null) {
            val p = stick ?: PointF()
            val cx = item.frame.centerX() + p.x * item.frame.width() / 2
            val cy = item.frame.centerY() + p.y * item.frame.height() / 2
            canvas.drawBitmap(t, cx - t.width / 2f, cy - t.height / 2f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = mutableSetOf<String>()
        var left: PointF? = null
        var touch: PointF? = null
        val ending = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_POINTER_UP
        if (e.actionMasked != MotionEvent.ACTION_CANCEL) for (i in 0 until e.pointerCount) {
            if (ending && i == e.actionIndex) continue
            val x = e.getX(i)
            val y = e.getY(i)
            val item = items.firstOrNull { it.frame.contains(x, y) } ?: items.firstOrNull { it.hit.contains(x, y) } ?: continue
            when (val inp = item.inputs) {
                is List<*> -> inp.forEach { now += it as String }
                is Map<*, *> -> {
                    val values = inp.values.map { it as String }
                    val dx = ((x - item.frame.centerX()) / (item.frame.width() / 2)).coerceIn(-1f, 1f)
                    val dy = ((y - item.frame.centerY()) / (item.frame.height() / 2)).coerceIn(-1f, 1f)
                    when {
                        values.any { it.startsWith("touchScreen") } -> touch = PointF(x, y)
                        item.thumb != null || values.any { it.startsWith("analogStick") } -> left = PointF(dx, dy)
                        else -> dpadDirections(dx, dy).forEach { d -> (inp[d] as String?)?.let { now += it } }
                    }
                }
            }
        }

        val added = now - pressed
        // "Mantener botones": cada toque alterna el botón entre retenido y suelto
        if (sustain) added.filter { it != "menu" }.forEach { if (!held.remove(it)) held += it }
        val effective = (if (sustain) now.filter { it == "menu" }.toSet() else now) + held
        (effective - active).forEach { press(it, true) }
        (active - effective).forEach { press(it, false) }
        if (added.isNotEmpty() && haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        pressed.clear(); pressed += now
        active.clear(); active += effective

        if (system == GameSystem.N64) {
            val c = effective.mapNotNull { C_STICK[it] }
            onAnalog?.invoke(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, c.sumOf { it.first.toDouble() }.toFloat().coerceIn(-1f, 1f), c.sumOf { it.second.toDouble() }.toFloat().coerceIn(-1f, 1f))
        }
        if (thumb != null || left != null || stick != null) {
            onAnalog?.invoke(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, left?.x ?: 0f, left?.y ?: 0f)
            stick = left
            invalidate()
        }
        if (touch != null || hadTouch) onTouchScreen?.invoke(touch)
        hadTouch = touch != null
        return true
    }

    private fun press(name: String, down: Boolean) {
        if (name == "menu") { if (down) onMenu?.invoke(); return }
        system.keys[name]?.let { onKey?.invoke(it, down) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        zip.close()
    }
}
