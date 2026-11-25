package com.deltadroid

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** BIOS opcionales que leen los núcleos desde la carpeta system/. */
private val BIOS = linkedMapOf(
    "bios7.bin" to "DS (ARM7)", "bios9.bin" to "DS (ARM9)", "firmware.bin" to "DS (firmware)",
    "gba_bios.bin" to "GBA", "gb_bios.bin" to "Game Boy", "gbc_bios.bin" to "Game Boy Color", "disksys.rom" to "Famicom Disk System",
)

private sealed interface Page {
    data object Main : Page
    data class Skins(val s: GameSystem) : Page
    data class Controls(val s: GameSystem) : Page
    data object Bios : Page
}

class SettingsActivity : ComponentActivity() {
    private val snackbar = SnackbarHostState()
    private var page by mutableStateOf<Page>(Page.Main)
    /** Input que espera un botón físico en la pantalla de controles. */
    private var capturing by mutableStateOf<String?>(null)
    private var controls by mutableStateOf(emptyMap<Int, String>())
    private var refresh by mutableIntStateOf(0)

    private fun message(msg: String) = lifecycleScope.launch { snackbar.showSnackbar(msg) }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@registerForActivityResult
        io("Respaldo exportado") { contentResolver.openOutputStream(uri)!!.use { Backup.export(this, it) } }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        io("Respaldo restaurado") { contentResolver.openInputStream(uri)!!.use { Backup.import(this, it) } }
    }
    private val importSkin = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        io("Skins importadas") { uris.forEach(::importSkinFile) }
    }
    private val importBios = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        io("BIOS importados") {
            uris.forEach { uri ->
                val name = displayName(uri)?.lowercase() ?: return@forEach
                if (name !in BIOS) error("$name no es un BIOS conocido")
                contentResolver.openInputStream(uri)!!.use { i -> File(appDir("system"), name).outputStream().use { i.copyTo(it) } }
            }
        }
    }

    private fun io(ok: String, block: () -> Unit) = lifecycleScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching(block) }
        refresh++
        snackbar.showSnackbar(r.exceptionOrNull()?.message?.let { "Error: $it" } ?: ok)
    }

    private fun importSkinFile(uri: Uri) {
        val tmp = File(cacheDir, "import.deltaskin")
        contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        SkinStore.import(this, tmp, displayName(uri) ?: "skin") ?: error("skin no válida")
        tmp.delete()
    }

    private fun displayName(uri: Uri) = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }?.let { File(it).name }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DeltaTheme(Color(GameSystem.GBA.color)) { Screen() } }
    }

    // captura del botón para el mapeo (los mandos envían KeyEvents a la ventana con foco)
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val target = capturing
        val p = page
        if (target == null || p !is Page.Controls || e.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_UP) {
            controls = controls + (e.keyCode to target) // una tecla = un input; reemplaza su uso anterior
            Controls.save(this, p.s, controls)
            capturing = null
        }
        return true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        BackHandler(page != Page.Main) { page = Page.Main }
        val title = when (val p = page) {
            Page.Main -> "Ajustes"; is Page.Skins -> "Skins de ${p.s.label}"
            is Page.Controls -> "Controles de ${p.s.label}"; Page.Bios -> "BIOS"
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton({ if (page == Page.Main) finish() else page = Page.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                key(refresh) {
                    when (val p = page) {
                        Page.Main -> MainPage()
                        is Page.Skins -> SkinsPage(p.s)
                        is Page.Controls -> ControlsPage(p.s)
                        Page.Bios -> BiosPage()
                    }
                }
            }
        }
    }

    @Composable
    private fun MainPage() {
        val prefs = Prefs.of(this)
        var haptics by remember { mutableStateOf(Prefs.haptics(this)) }
        var hideSkin by remember { mutableStateOf(Prefs.hideSkinWithController(this)) }
        var opacity by remember { mutableFloatStateOf(Prefs.opacity(this)) }
        var speed by remember { mutableIntStateOf(Prefs.fastForwardSpeed(this)) }
        var pickSystem by remember { mutableStateOf<((GameSystem) -> Page)?>(null) }

        LazyColumn {
            item { Header("Juego") }
            item {
                SwitchRow("Vibración al pulsar", haptics) { haptics = it; prefs.edit { putBoolean("haptics", it) } }
                SwitchRow("Ocultar la skin al usar un mando", hideSkin) { hideSkin = it; prefs.edit { putBoolean("hideSkin", it) } }
                ListItem(
                    headlineContent = { Text("Opacidad del control (horizontal): ${(opacity * 100).toInt()} %") },
                    supportingContent = {
                        Slider(opacity, { opacity = it }, valueRange = 0.2f..1f, onValueChangeFinished = { prefs.edit { putFloat("opacity", opacity) } })
                    },
                )
                ListItem(
                    headlineContent = { Text("Velocidad del avance rápido") },
                    supportingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 3, 4, 8).forEach { x ->
                                FilterChip(speed == x, { speed = x; prefs.edit { putInt("ffSpeed", x) } }, { Text("${x}x") })
                            }
                        }
                    },
                )
            }
            item { Header("Personalizar") }
            item {
                NavRow("Skins", "Elige o importa skins .deltaskin por consola") { pickSystem = { Page.Skins(it) } }
                NavRow("Controles", "Asigna botones del mando o teclado, y Guardado/Carga rápida") { pickSystem = { Page.Controls(it) } }
                NavRow("BIOS", "Opcionales: DS, GBA, Game Boy, Famicom Disk System") { page = Page.Bios }
            }
            item { Header("Respaldo") }
            item {
                NavRow("Exportar respaldo", "Juegos, partidas, estados, trucos, skins y ajustes en un .zip") {
                    exportBackup.launch("DeltaDroid-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip")
                }
                NavRow("Importar respaldo", "Restaura un .zip exportado (reemplaza lo que coincida)") {
                    importBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
        }
        pickSystem?.let { toPage ->
            AlertDialog(
                onDismissRequest = { pickSystem = null },
                title = { Text("Consola") },
                text = {
                    Column {
                        GameSystem.entries.forEach { s ->
                            Text(s.label, Modifier.fillMaxWidth().clickable {
                                pickSystem = null
                                if (toPage(s) is Page.Controls) controls = Controls.load(this@SettingsActivity, s)
                                page = toPage(s)
                            }.padding(vertical = 12.dp))
                        }
                    }
                },
                confirmButton = { TextButton({ pickSystem = null }) { Text("Cancelar") } },
            )
        }
    }

    @Composable
    private fun SkinsPage(s: GameSystem) {
        val skins = remember { SkinStore.list(this, s) }
        var active by remember { mutableStateOf(SkinStore.active(this, s, null).name) }
        LazyColumn {
            items(skins, key = { it.file.name }) { skin ->
                ListItem(
                    headlineContent = { Text(skin.name) },
                    supportingContent = { Text(if (skin.file.name == "standard.deltaskin") "Incluida" else skin.file.name) },
                    leadingContent = { RadioButton(active == skin.file.name, { SkinStore.choose(this@SettingsActivity, s, null, skin.file.name); active = skin.file.name }) },
                    trailingContent = {
                        if (skin.file.name != "standard.deltaskin") IconButton({ SkinStore.delete(this@SettingsActivity, s, skin); refresh++ }) {
                            Icon(Icons.Outlined.Delete, "Borrar")
                        }
                    },
                    modifier = Modifier.clickable { SkinStore.choose(this@SettingsActivity, s, null, skin.file.name); active = skin.file.name },
                )
            }
            item {
                Button({ importSkin.launch(arrayOf("*/*")) }, Modifier.padding(16.dp)) { Text("Importar .deltaskin") }
                Text("Cada juego puede usar otra skin: mantén pulsado el juego en la biblioteca.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun ControlsPage(s: GameSystem) {
        LazyColumn {
            item {
                Text("Toca una acción y pulsa el botón del mando o la tecla que quieras asignarle.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(Controls.inputs(s)) { input ->
                val keys = controls.filterValues { it == input }.keys
                ListItem(
                    headlineContent = { Text(Controls.label(input)) },
                    supportingContent = {
                        Text(if (capturing == input) "Pulsa un botón…" else keys.joinToString { Controls.keyName(it) }.ifEmpty { "Sin asignar" },
                            color = if (capturing == input) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        if (keys.isNotEmpty()) IconButton({
                            controls = controls.filterValues { it != input }; Controls.save(this@SettingsActivity, s, controls)
                        }) { Icon(Icons.Outlined.Delete, "Quitar") }
                    },
                    modifier = Modifier.clickable { capturing = input },
                )
            }
            item {
                OutlinedButton({ Controls.save(this@SettingsActivity, s, null); controls = Controls.defaults(s) }, Modifier.padding(16.dp)) {
                    Text("Restablecer")
                }
            }
        }
    }

    @Composable
    private fun BiosPage() {
        val dir = appDir("system")
        LazyColumn {
            items(BIOS.toList()) { (file, label) ->
                val present = File(dir, file).exists()
                ListItem(
                    headlineContent = { Text(file) },
                    supportingContent = { Text(label) },
                    trailingContent = { if (present) Icon(Icons.Default.Check, "Instalado", tint = MaterialTheme.colorScheme.primary) },
                )
            }
            item {
                Button({ importBios.launch(arrayOf("*/*")) }, Modifier.padding(16.dp)) { Text("Importar BIOS") }
                Text("Ninguno es obligatorio: los núcleos traen BIOS de reemplazo. El nombre del archivo debe coincidir.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun Header(t: String) = Text(t, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

    @Composable
    private fun SwitchRow(t: String, on: Boolean, set: (Boolean) -> Unit) =
        ListItem(headlineContent = { Text(t) }, trailingContent = { Switch(on, set) }, modifier = Modifier.clickable { set(!on) })

    @Composable
    private fun NavRow(t: String, sub: String, onClick: () -> Unit) = ListItem(
        headlineContent = { Text(t) }, supportingContent = { Text(sub) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
