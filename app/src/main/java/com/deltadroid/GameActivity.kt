package com.deltadroid

import android.content.res.Configuration
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

class GameActivity : ComponentActivity() {
    private lateinit var retro: GLRetroView
    private lateinit var system: GameSystem
    private lateinit var rom: File
    private val srm by lazy { File(dir("saves"), rom.name + ".srm") }
    private val state by lazy { File(dir("states"), rom.name + ".state") }
    private var landscape = false
    private var gameRect = RectF()
    private var viewSize = RectF()
    private var ready = false
    private var fastForward by mutableStateOf(false)
    private var menuOpen by mutableStateOf(false)
    private var cheatsOpen by mutableStateOf(false)
    private var muted by mutableStateOf(false)
    private var sustain by mutableStateOf(false)
    private lateinit var skin: SkinView
    private val cheatsFile by lazy { File(dir("cheats"), rom.name + ".json") }
    private var cheats by mutableStateOf(emptyList<Cheat>())
    private var appliedCheats = 0

    private fun dir(name: String) = File(filesDir, name).apply { mkdirs() }

    // melonDS: pantallas una sobre otra en vertical y lado a lado en horizontal, como las skins de Delta
    private fun variables() = if (system == GameSystem.DS) arrayOf(
        Variable("melonds_touch_mode", "Touch"),
        Variable("melonds_screen_layout", if (landscape) "Left/Right" else "Top/Bottom"),
    ) else emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rom = File(intent.getStringExtra(EXTRA_ROM)!!)
        system = GameSystem.valueOf(intent.getStringExtra(EXTRA_SYSTEM)!!)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val skinFile = try {
            system.skinFile(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Falta la skin de ${system.label} (corre tools/fetch_assets.sh)", Toast.LENGTH_LONG).show()
            finish(); return
        }

        retro = GLRetroView(this, GLRetroViewData(this).apply {
            coreFilePath = "${applicationInfo.nativeLibraryDir}/lib${system.core}_libretro_android.so"
            gameFilePath = rom.path
            systemDirectory = dir("system").path // BIOS opcionales (melonDS/mGBA traen HLE)
            savesDirectory = dir("saves").path
            saveRAMState = srm.takeIf { it.exists() }?.readBytes()
            variables = variables()
        })
        lifecycle.addObserver(retro)
        retro.isFocusable = true // mandos físicos: GLRetroView mapea sus teclas

        skin = SkinView(this, skinFile, system).apply {
            onLayout = { rect, land ->
                gameRect = rect
                viewSize = RectF(0f, 0f, width.toFloat(), height.toFloat())
                if (land != landscape) {
                    landscape = land
                    if (ready) retro.updateVariables(*variables())
                }
                applyViewport()
            }
            onKey = { code, down -> retro.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code) }
            onAnalog = { src, x, y -> retro.sendMotionEvent(src, x, y) }
            onTouchScreen = ::sendTouch
            onMenu = ::showMenu
        }

        setContentView(FrameLayout(this).apply {
            addView(retro)
            addView(skin)
            val title = intent.getStringExtra(EXTRA_TITLE) ?: rom.nameWithoutExtension
            addView(ComposeView(this@GameActivity).apply {
                setContent {
                    DeltaTheme(Color(system.color)) {
                        val toggled = buildSet {
                            if (fastForward) add(PauseAction.FAST_FORWARD)
                            if (sustain) add(PauseAction.SUSTAIN)
                            if (muted) add(PauseAction.MUTE)
                        }
                        if (menuOpen && !cheatsOpen) PauseSheet(title, system.label, toggled, ::onPauseAction, ::closeMenu)
                        if (cheatsOpen) CheatsSheet(cheats, ::setCheats) { cheatsOpen = false; closeMenu() }
                    }
                }
            })
        })
        retro.requestFocus()

        lifecycleScope.launch {
            retro.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.SurfaceCreated }
            ready = true
            applyViewport()
            setCheats(CheatStore.load(cheatsFile), persist = false)
        }
        lifecycleScope.launch {
            retro.getGLRetroErrors().collect {
                Toast.makeText(this@GameActivity, "Error del núcleo ${system.core} ($it)", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this) { showMenu() }
    }

    // El setter de viewport espera al hilo GL: no se toca hasta que el juego cargó, para no bloquear la UI
    private fun applyViewport() {
        if (!ready || viewSize.isEmpty) return
        val w = viewSize.width()
        val h = viewSize.height()
        retro.viewport = RectF(gameRect.left / w, gameRect.top / h, gameRect.right / w, gameRect.bottom / h)
    }

    /** Convierte el toque a coordenadas 0..1 del framebuffer de melonDS (256x384 o 512x192). */
    private fun sendTouch(p: android.graphics.PointF?) {
        if (p == null) return retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_POINTER, -1f, -1f)
        val fa = if (landscape) 512f / 192f else 256f / 384f
        val r = gameRect
        val cw = if (fa > r.width() / r.height()) r.width() else r.height() * fa
        val ch = cw / fa
        val x = (p.x - (r.centerX() - cw / 2)) / cw
        val y = (p.y - (r.centerY() - ch / 2)) / ch
        retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_POINTER, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private fun showMenu() {
        if (menuOpen) return
        retro.onPause() // detiene el hilo GL = emulación en pausa
        menuOpen = true
    }

    private fun closeMenu() {
        menuOpen = false
        retro.onResume()
    }

    private fun onPauseAction(a: PauseAction) {
        when (a) {
            PauseAction.SAVE -> { state.writeBytes(retro.serializeState()); toast("Estado guardado") }
            PauseAction.LOAD -> toast(if (state.exists() && retro.unserializeState(state.readBytes())) "Estado cargado" else "No hay estado guardado")
            PauseAction.FAST_FORWARD -> { fastForward = !fastForward; retro.frameSpeed = if (fastForward) 3 else 1 }
            PauseAction.RESET -> retro.reset()
            PauseAction.SCREENSHOT -> return screenshot()
            PauseAction.CHEATS -> { cheatsOpen = true; return }
            PauseAction.SUSTAIN -> {
                sustain = !sustain
                skin.sustain = sustain
                if (sustain) toast("Toca los botones que quieras dejar pulsados; otra vez para soltarlos")
            }
            PauseAction.MUTE -> { muted = !muted; retro.audioEnabled = !muted }
            PauseAction.EXIT -> return finish()
        }
        closeMenu()
    }

    /** Guarda los trucos y se los pasa al núcleo; los índices sobrantes (trucos borrados) se apagan. */
    private fun setCheats(list: List<Cheat>, persist: Boolean = true) {
        cheats = list
        if (persist) CheatStore.save(cheatsFile, list)
        list.forEachIndexed { i, c -> retro.setCheat(i, c.enabled, c.code) }
        for (i in list.size until appliedCheats) retro.setCheat(i, false, "")
        appliedCheats = list.size
    }

    /** Copia el área del juego de la superficie GL y la guarda en Imágenes/DeltaDroid. */
    private fun screenshot() {
        // con el hilo GL en pausa la superficie no tiene buffer: se reanuda y se copia el fotograma siguiente
        closeMenu()
        retro.postDelayed({ copySurface() }, 150)
    }

    private fun copySurface() {
        val full = Bitmap.createBitmap(retro.width, retro.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(retro, full, { result ->
            if (result != PixelCopy.SUCCESS) return@request toast("No se pudo capturar")
            val r = gameRect.run { Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()) }
            r.intersect(0, 0, full.width, full.height)
            // ponytail: incluye las barras negras del ajuste de aspecto; recortarlas por color cortaba juegos con fondo negro
            val shot = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { saveImage(shot) }.isSuccess }
                toast(if (ok) "Captura guardada en Imágenes/DeltaDroid" else "No se pudo guardar la captura")
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveImage(bmp: Bitmap) {
        val name = "${rom.nameWithoutExtension}_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DeltaDroid")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            // ponytail: Android 7–9 sin permiso de almacenamiento -> carpeta propia de la app (Android/data/...)
            File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        if (::retro.isInitialized && ready) runCatching {
            retro.serializeSRAM().takeIf { it.isNotEmpty() }?.let(srm::writeBytes)
        }
    }

    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_TITLE = "title"
    }
}
