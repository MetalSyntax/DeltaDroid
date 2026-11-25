package com.deltadroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.clickable
import androidx.core.content.pm.ShortcutManagerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryActivity : ComponentActivity() {
    private var games by mutableStateOf<List<Game>?>(null)
    private val snackbar = SnackbarHostState()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val msgs = withContext(Dispatchers.IO) { uris.map(::import) }
            reload()
            snackbar.showSnackbar(msgs.joinToString("\n"))
        }
    }

    /** Juego sobre el que actúan los selectores de archivo del menú contextual. */
    private var target: Game? = null

    private val pickCover = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val g = target ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        io("Carátula cambiada") { contentResolver.openInputStream(uri)!!.use { i -> File(appDir("covers"), g.file.name).outputStream().use { i.copyTo(it) } } }
    }
    private val exportSave = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val g = target ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        io("Partida exportada") { contentResolver.openOutputStream(uri)!!.use { o -> g.srm.inputStream().use { it.copyTo(o) } } }
    }
    private val importSave = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val g = target ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        io("Partida importada") {
            g.srm.parentFile!!.mkdirs()
            contentResolver.openInputStream(uri)!!.use { i -> g.srm.outputStream().use { i.copyTo(it) } }
        }
    }

    private fun io(ok: String, block: () -> Unit) = lifecycleScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching(block) }
        reload()
        snackbar.showSnackbar(r.exceptionOrNull()?.let { "Error: ${it.message}" } ?: ok)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LibraryScreen() }
    }

    // también al volver de Ajustes (un respaldo importado puede traer juegos nuevos)
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { reload() }
    }

    private suspend fun reload() {
        games = withContext(Dispatchers.IO) { Library.load(this@LibraryActivity) }
    }

    private fun play(g: Game) = startActivity(Intent(this, GameActivity::class.java)
        .putExtra(GameActivity.EXTRA_ROM, g.file.path)
        .putExtra(GameActivity.EXTRA_SYSTEM, g.system.name)
        .putExtra(GameActivity.EXTRA_TITLE, g.title))

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LibraryScreen() {
        val all = games
        val systems = all.orEmpty().map { it.system }.distinct().sortedBy { it.ordinal }
        val pager = rememberPagerState { systems.size }
        val current = systems.getOrNull(pager.currentPage)
        val accent by animateColorAsState(Color(current?.color ?: GameSystem.GBA.color), label = "accent")
        var query by rememberSaveable { mutableStateOf("") }
        var toDelete by remember { mutableStateOf<Game?>(null) }
        var actions by remember { mutableStateOf<Game?>(null) }
        var renaming by remember { mutableStateOf<Game?>(null) }
        var skinFor by remember { mutableStateOf<Game?>(null) }

        DeltaTheme(accent) {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color(0xFF0E0E10), 1f to accent.copy(alpha = 0.45f))
            )) {
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(current?.label ?: "DeltaDroid") },
                            navigationIcon = { IconButton({ startActivity(Intent(this@LibraryActivity, SettingsActivity::class.java)) }) { Icon(Icons.Outlined.Settings, "Ajustes", tint = accent) } },
                            actions = { IconButton({ pick.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Importar", tint = accent) } },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                        )
                    },
                    bottomBar = { if (systems.size > 1) PageDots(systems.size, pager.currentPage, accent) },
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { pad ->
                    Column(Modifier.padding(pad)) {
                        TextField(
                            query, { query = it },
                            placeholder = { Text("Buscar") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        when {
                            all == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                            systems.isEmpty() -> EmptyState()
                            else -> HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                                val list = all.filter { it.system == systems[page] && it.title.contains(query, true) }
                                LazyVerticalGrid(
                                    GridCells.Adaptive(100.dp),
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(list, key = { it.file.name }) { g -> GameTile(g, accent, { play(g) }, { actions = g }) }
                                }
                            }
                        }
                    }
                }
            }
            toDelete?.let { g ->
                AlertDialog(
                    onDismissRequest = { toDelete = null },
                    title = { Text("¿Borrar «${g.title}»?") },
                    text = { Text("También se borran su partida guardada y su estado.") },
                    confirmButton = {
                        TextButton({
                            toDelete = null
                            ShortcutManagerCompat.removeDynamicShortcuts(this@LibraryActivity, listOf(g.file.name))
                            lifecycleScope.launch { withContext(Dispatchers.IO) { Library.delete(this@LibraryActivity, g) }; reload() }
                        }) { Text("Borrar") }
                    },
                    dismissButton = { TextButton({ toDelete = null }) { Text("Cancelar") } },
                )
            }
            actions?.let { g ->
                GameActionsSheet(g, onDismiss = { actions = null }) { action ->
                    actions = null
                    target = g
                    when (action) {
                        GameAction.PLAY -> play(g)
                        GameAction.RENAME -> renaming = g
                        GameAction.COVER -> pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        GameAction.COVER_RESET -> io("Carátula restaurada") { File(filesDir, "covers/${g.file.name}").delete() }
                        GameAction.SKIN -> skinFor = g
                        GameAction.EXPORT_SAVE -> if (g.srm.exists()) exportSave.launch("${g.file.nameWithoutExtension}.sav")
                            else lifecycleScope.launch { snackbar.showSnackbar("Este juego todavía no tiene partida guardada") }
                        GameAction.IMPORT_SAVE -> importSave.launch(arrayOf("*/*"))
                        GameAction.DELETE -> toDelete = g
                    }
                }
            }
            renaming?.let { g ->
                var text by remember(g) { mutableStateOf(g.title) }
                AlertDialog(
                    onDismissRequest = { renaming = null },
                    title = { Text("Renombrar") },
                    text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                    confirmButton = { TextButton({ renaming = null; io("Renombrado") { Library.rename(this, g, text) } }) { Text("Guardar") } },
                    dismissButton = { TextButton({ renaming = null; io("Nombre original") { Library.rename(this, g, "") } }) { Text("Original") } },
                )
            }
            skinFor?.let { g ->
                val skins = remember(g) { SkinStore.list(this, g.system) }
                AlertDialog(
                    onDismissRequest = { skinFor = null },
                    title = { Text("Skin para «${g.title}»") },
                    text = {
                        Column {
                            Text("La de la consola", Modifier.fillMaxWidth().clickable {
                                SkinStore.choose(this@LibraryActivity, g.system, g.file, null); skinFor = null
                            }.padding(vertical = 12.dp))
                            skins.forEach { sk ->
                                Text(sk.name, Modifier.fillMaxWidth().clickable {
                                    SkinStore.choose(this@LibraryActivity, g.system, g.file, sk.file.name); skinFor = null
                                }.padding(vertical = 12.dp))
                            }
                        }
                    },
                    confirmButton = { TextButton({ skinFor = null }) { Text("Cancelar") } },
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GameTile(g: Game, accent: Color, onClick: () -> Unit, onLongClick: () -> Unit) {
        Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick), horizontalAlignment = Alignment.CenterHorizontally) {
            // si una fuente de carátula falla se prueba la siguiente; mientras carga o sin ninguna, un recuadro con el sistema
            var source by remember(g) { mutableIntStateOf(0) }
            var loaded by remember(g) { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().aspectRatio(1f), Alignment.Center) {
                if (!loaded) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
                    Text(g.system.label, style = MaterialTheme.typography.titleMedium, color = accent)
                }
                if (source < g.covers.size) AsyncImage(
                    g.covers[source], g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    onSuccess = { loaded = true }, onError = { source++ },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(g.title, style = MaterialTheme.typography.labelMedium, color = accent, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun PageDots(count: Int, current: Int, accent: Color) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), Arrangement.Center) {
            repeat(count) {
                Box(Modifier.padding(4.dp).size(8.dp).clip(CircleShape)
                    .background(if (it == current) accent else Color.White.copy(alpha = 0.35f)))
            }
        }
    }

    @Composable
    private fun EmptyState() = Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sin juegos", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Importa ROMs de NES, SNES, GB/GBC, GBA, N64, DS o Genesis, o skins .deltaskin.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button({ pick.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Importar") }
    }

    /** Copia el archivo elegido a la app: ROM a roms/, .deltaskin a skins/ (reemplaza la del sistema). */
    private fun import(uri: Uri): String {
        // nombre que da el proveedor: solo se usa la última parte, nunca una ruta
        val name = File(displayName(uri) ?: return "Archivo sin nombre").name
        if (name.endsWith(".deltaskin", true)) {
            val tmp = File(cacheDir, "import.deltaskin")
            copy(uri, tmp)
            val system = SkinStore.import(this, tmp, name)
            tmp.delete()
            return system?.let { "Skin de ${it.label} agregada (elígela en Ajustes > Skins)" } ?: "$name: skin no válida o de un sistema no soportado"
        }
        GameSystem.byExt(name) ?: return "$name: formato no soportado"
        copy(uri, File(Library.romsDir(this), name))
        return "$name importado"
    }

    private fun copy(uri: Uri, dest: File) =
        contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }

    private fun displayName(uri: Uri) = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}

enum class GameAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PLAY("Jugar", Icons.Default.PlayArrow),
    RENAME("Renombrar", Icons.Outlined.Edit),
    COVER("Cambiar carátula", Icons.Outlined.Image),
    COVER_RESET("Carátula original", Icons.Outlined.HideImage),
    SKIN("Skin para este juego", Icons.Outlined.Gamepad),
    EXPORT_SAVE("Exportar partida (.sav)", Icons.Outlined.Upload),
    IMPORT_SAVE("Importar partida (.sav)", Icons.Outlined.Download),
    DELETE("Borrar", Icons.Outlined.Delete),
}

/** Menú contextual del juego (Delta lo muestra con Haptic Touch; aquí con pulsación larga). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameActionsSheet(g: Game, onDismiss: () -> Unit, onAction: (GameAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(g.title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
        Text(g.system.label, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        GameAction.entries.forEach { a ->
            ListItem(
                headlineContent = { Text(a.label, color = if (a == GameAction.DELETE) MaterialTheme.colorScheme.error else Color.Unspecified) },
                leadingContent = { Icon(a.icon, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onAction(a) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
