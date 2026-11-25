package com.deltadroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Cheat(val name: String, val code: String, val enabled: Boolean = true)

/** Trucos por juego en files/cheats/<rom>.json. */
object CheatStore {
    fun load(file: File): List<Cheat> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { a.getJSONObject(it).run { Cheat(getString("name"), getString("code"), optBoolean("enabled", true)) } }
    }.getOrDefault(emptyList())

    fun save(file: File, cheats: List<Cheat>) {
        file.parentFile!!.mkdirs()
        file.writeText(JSONArray(cheats.map { JSONObject().put("name", it.name).put("code", it.code).put("enabled", it.enabled) }).toString())
    }
}

/** libretro separa las líneas de un truco con "+"; el usuario puede pegarlas en varias líneas. */
fun normalizeCheatCode(raw: String) = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("+")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatsSheet(cheats: List<Cheat>, onChange: (List<Cheat>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Trucos", style = MaterialTheme.typography.titleLarge)
            Text("Game Genie, Action Replay o GameShark, según lo que acepte el núcleo de la consola.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                itemsIndexed(cheats) { i, c ->
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text(c.code, fontFamily = FontFamily.Monospace, maxLines = 2) },
                        leadingContent = {
                            IconButton({ onChange(cheats.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Outlined.Delete, "Borrar") }
                        },
                        trailingContent = {
                            Switch(c.enabled, { on -> onChange(cheats.mapIndexed { j, x -> if (j == i) x.copy(enabled = on) else x }) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            if (cheats.isEmpty()) Text("Todavía no hay trucos para este juego.", Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nombre") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Código") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), minLines = 2)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onChange(cheats + Cheat(name.trim().ifEmpty { "Truco ${cheats.size + 1}" }, normalizeCheatCode(code)))
                    name = ""; code = ""
                },
                enabled = normalizeCheatCode(code).isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Agregar truco") }
        }
    }
}
