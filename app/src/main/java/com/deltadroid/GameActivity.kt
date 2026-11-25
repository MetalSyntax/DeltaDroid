package com.deltadroid

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class GameActivity : ComponentActivity() {
    private lateinit var retro: GLRetroView
    private lateinit var system: GameSystem
    private lateinit var rom: File
    private lateinit var skin: SkinView
    private lateinit var title: String
    private val srm by lazy { File(appDir("saves"), rom.name + ".srm") }
    private val cheatsFile by lazy { File(appDir("cheats"), rom.name + ".json") }
    private var landscape = false
    private var gameRect = RectF()
    private var viewSize = RectF()
    private var ready = false

    private var menuOpen by mutableStateOf(false)
    private var cheatsOpen by mutableStateOf(false)
    private var statesMode by mutableStateOf<StatesMode?>(null)
    private var states by mutableStateOf(emptyList<SaveState>())
    private var fastForward by mutableStateOf(false)
    private var muted by mutableStateOf(false)
    private var sustain by mutableStateOf(false)
    private var cheats by mutableStateOf(emptyList<Cheat>())
    private var appliedCheats = 0
    /** Último fotograma (capturado al abrir el menú): miniaturas de estados y capturas de pantalla. */
    private var lastFrame: Bitmap? = null
    private var opening = false

    // mandos físicos
    private var mapping = emptyMap<Int, String>()
    private var skinHidden = false
    private val heldC = mutableSetOf<String>()
    private var stickDirs = emptyList<String>()

    // melonDS: pantallas una sobre otra en vertical y lado a lado en horizontal, como las skins de Delta
    private fun variables() = if (system == GameSystem.DS) arrayOf(
        Variable("melonds_touch_mode", "Touch"),
        Variable("melonds_screen_layout", if (landscape) "Left/Right" else "Top/Bottom"),
    ) else emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rom = File(intent.getStringExtra(EXTRA_ROM) ?: return finish())
        system = GameSystem.valueOf(intent.getStringExtra(EXTRA_SYSTEM) ?: return finish())
        title = intent.getStringExtra(EXTRA_TITLE) ?: rom.nameWithoutExtension
        if (!rom.exists()) { toast("El juego ya no está en la biblioteca"); return finish() }
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        mapping = Controls.load(this, system)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val skinFile = try {
            SkinStore.active(this, system, rom)
        } catch (e: Exception) {
            toast("Falta la skin de ${system.label} (corre tools/fetch_assets.sh)")
            return finish()
        }

        retro = GLRetroView(this, GLRetroViewData(this).apply {
            coreFilePath = "${applicationInfo.nativeLibraryDir}/lib${system.core}_libretro_android.so"
            gameFilePath = rom.path
            systemDirectory = appDir("system").path // BIOS opcionales (Ajustes > BIOS)
            savesDirectory = appDir("saves").path
            saveRAMState = srm.takeIf { it.exists() }?.readBytes()
            variables = variables()
        })
        lifecycle.addObserver(retro)
        retro.isFocusable = false // los botones físicos los traduce dispatchKeyEvent con el mapeo de Ajustes

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

        val root = object : FrameLayout(this) {
            // con la skin oculta (mando en uso), tocar la pantalla la vuelve a mostrar
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (!skinHidden) return false
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) setSkinHidden(false)
                return true
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent) = true
        }
        root.addView(retro)
        root.addView(skin)
        root.addView(ComposeView(this).apply { setContent { Sheets() } })
        setContentView(root)

        lifecycleScope.launch {
            retro.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.SurfaceCreated }
            ready = true
            applyViewport()
            setCheats(CheatStore.load(cheatsFile), persist = false)
        }
        lifecycleScope.launch {
            retro.getGLRetroErrors().collect {
                toast("Error del núcleo ${system.core} ($it)")
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this) { showMenu() }
        addShortcut()
    }

    @androidx.compose.runtime.Composable
    private fun Sheets() = DeltaTheme(Color(system.color)) {
        val toggled = buildSet {
            if (fastForward) add(PauseAction.FAST_FORWARD)
            if (sustain) add(PauseAction.SUSTAIN)
            if (muted) add(PauseAction.MUTE)
        }
        val mode = statesMode
        when {
            cheatsOpen -> CheatsSheet(cheats, ::setCheats) { cheatsOpen = false; closeMenu() }
            mode != null -> StatesSheet(
                mode, states,
                onNew = { saveState(null) },
                onPick = { if (mode == StatesMode.SAVE) saveState(it) else loadState(it) },
                onChanged = { states = StateStore.list(this, rom) },
                onDismiss = { statesMode = null; closeMenu() },
            )
            menuOpen -> PauseSheet(title, system.label, toggled, ::onPauseAction, ::closeMenu)
        }
    }

    /** Acceso directo en el ícono de la app a los últimos juegos (como los de Delta en iOS). */
    private fun addShortcut() = runCatching {
        ShortcutManagerCompat.pushDynamicShortcut(this, ShortcutInfoCompat.Builder(this, rom.name)
            .setShortLabel(title.take(24)).setLongLabel(title)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, GameActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_ROM, rom.path).putExtra(EXTRA_SYSTEM, system.name).putExtra(EXTRA_TITLE, title))
            .build())
    }

    // El setter de viewport espera al hilo GL: no se toca hasta que el juego cargó, para no bloquear la UI
    private fun applyViewport() {
        if (!ready || viewSize.isEmpty) return
        val r = if (skinHidden) viewSize else gameRect
        val w = viewSize.width()
        val h = viewSize.height()
        retro.viewport = RectF(r.left / w, r.top / h, r.right / w, r.bottom / h)
    }

    private fun setSkinHidden(hidden: Boolean) {
        if (hidden == skinHidden) return
        skinHidden = hidden
        skin.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        applyViewport()
    }

    /** Convierte el toque a coordenadas 0..1 del framebuffer de melonDS (256x384 o 512x192). */
    private fun sendTouch(p: PointF?) {
        if (p == null) return retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_POINTER, -1f, -1f)
        val fa = if (landscape) 512f / 192f else 256f / 384f
        val r = gameRect
        val cw = if (fa > r.width() / r.height()) r.width() else r.height() * fa
        val ch = cw / fa
        val x = (p.x - (r.centerX() - cw / 2)) / cw
        val y = (p.y - (r.centerY() - ch / 2)) / ch
        retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_POINTER, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    // ---------- mandos y teclado ----------

    private fun usingController() {
        if (Prefs.hideSkinWithController(this)) setSkinHidden(true)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val name = mapping[e.keyCode]
        if (name == null || !ready) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down && e.repeatCount > 0) return true
        if (e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) usingController()
        when (name) {
            "menu" -> if (down) showMenu()
            "quickSave" -> if (down) quickSave()
            "quickLoad" -> if (down) quickLoad()
            "fastForward" -> if (down) applyFastForward(!fastForward)
            in C_STICK -> { if (down) heldC += name else heldC -= name; sendCStick() }
            else -> system.keys[name]?.let { retro.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, it) }
        }
        return true
    }

    private fun sendCStick() {
        val c = heldC.mapNotNull { C_STICK[it] }
        retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, c.sumOf { it.first.toDouble() }.toFloat(), c.sumOf { it.second.toDouble() }.toFloat())
    }

    /** Sticks y cruceta "hat": en N64 van como analógicos; en el resto el stick izquierdo hace de cruceta. */
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE || !ready)
            return super.dispatchGenericMotionEvent(e)
        usingController()
        fun axis(a: Int) = e.getAxisValue(a).let { if (abs(it) < 0.15f) 0f else it }
        val hx = axis(MotionEvent.AXIS_HAT_X)
        val hy = axis(MotionEvent.AXIS_HAT_Y)
        val dirs = if (system == GameSystem.N64) {
            retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y))
            retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, axis(MotionEvent.AXIS_Z), axis(MotionEvent.AXIS_RZ))
            dpadDirections(hx, hy, 0.5f)
        } else {
            dpadDirections(if (hx != 0f) hx else axis(MotionEvent.AXIS_X), if (hy != 0f) hy else axis(MotionEvent.AXIS_Y), 0.5f)
        }
        (dirs - stickDirs.toSet()).forEach { d -> system.keys[d]?.let { retro.sendKeyEvent(KeyEvent.ACTION_DOWN, it) } }
        (stickDirs - dirs.toSet()).forEach { d -> system.keys[d]?.let { retro.sendKeyEvent(KeyEvent.ACTION_UP, it) } }
        stickDirs = dirs
        return true
    }

    // ---------- menú de pausa ----------

    /** Captura el fotograma actual (antes de pausar: con el hilo GL en pausa la superficie no tiene buffer). */
    private fun captureFrame(done: (Bitmap?) -> Unit) {
        if (!ready || retro.width == 0) return done(null)
        val full = Bitmap.createBitmap(retro.width, retro.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(retro, full, { result ->
            if (result != PixelCopy.SUCCESS) return@request done(null)
            val r = (if (skinHidden) viewSize else gameRect).run { Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()) }
            r.intersect(0, 0, full.width, full.height)
            done(if (r.isEmpty) null else Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height()))
        }, Handler(Looper.getMainLooper()))
    }

    private fun thumbnail(b: Bitmap?) = b?.let { Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width).toInt().coerceAtLeast(1), true) }

    private fun showMenu() {
        if (menuOpen || opening) return
        opening = true
        captureFrame { frame ->
            lastFrame = frame
            opening = false
            retro.onPause() // detiene el hilo GL = emulación en pausa
            menuOpen = true
        }
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        statesMode = null
        cheatsOpen = false
        retro.onResume()
    }

    private fun onPauseAction(a: PauseAction) {
        when (a) {
            PauseAction.SAVE, PauseAction.LOAD -> {
                states = StateStore.list(this, rom)
                statesMode = if (a == PauseAction.SAVE) StatesMode.SAVE else StatesMode.LOAD
                return
            }
            PauseAction.FAST_FORWARD -> applyFastForward(!fastForward)
            PauseAction.RESET -> retro.reset()
            PauseAction.SCREENSHOT -> saveScreenshot()
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

    private fun applyFastForward(on: Boolean) {
        fastForward = on
        retro.frameSpeed = if (on) Prefs.fastForwardSpeed(this) else 1
    }

    private fun saveState(target: SaveState?) {
        StateStore.write(this, rom, retro.serializeState(), thumbnail(lastFrame), StateKind.MANUAL, target)
        toast("Estado guardado")
        closeMenu()
    }

    private fun loadState(s: SaveState) {
        toast(if (retro.unserializeState(s.file.readBytes())) "Estado cargado" else "No se pudo cargar el estado")
        closeMenu()
    }

    private fun quickSave() = captureFrame { frame ->
        StateStore.write(this, rom, retro.serializeState(), thumbnail(frame), StateKind.QUICK)
        toast("Guardado rápido")
    }

    private fun quickLoad() {
        val f = File(StateStore.dir(this, rom), "quick.state")
        toast(if (f.exists() && retro.unserializeState(f.readBytes())) "Carga rápida" else "No hay guardado rápido")
    }

    /** Guarda los trucos y se los pasa al núcleo; los índices sobrantes (trucos borrados) se apagan. */
    private fun setCheats(list: List<Cheat>, persist: Boolean = true) {
        cheats = list
        if (persist) CheatStore.save(cheatsFile, list)
        list.forEachIndexed { i, c -> retro.setCheat(i, c.enabled, c.code) }
        for (i in list.size until appliedCheats) retro.setCheat(i, false, "")
        appliedCheats = list.size
    }

    private fun saveScreenshot() {
        // ponytail: incluye las barras negras del ajuste de aspecto; recortarlas por color cortaba juegos con fondo negro
        val shot = lastFrame ?: return toast("No se pudo capturar")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { saveImage(shot) }.isSuccess }
            toast(if (ok) "Captura guardada en Imágenes/DeltaDroid" else "No se pudo guardar la captura")
        }
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
        if (!::retro.isInitialized || !ready) return
        runCatching { retro.serializeSRAM().takeIf { it.isNotEmpty() }?.let(srm::writeBytes) }
        // respaldo automático como el de Delta: se sobrescribe cada vez que se sale o se minimiza
        runCatching { StateStore.write(this, rom, retro.serializeState(), thumbnail(lastFrame), StateKind.AUTO) }
    }

    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_TITLE = "title"
    }
}
