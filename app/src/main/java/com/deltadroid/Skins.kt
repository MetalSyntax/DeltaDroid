package com.deltadroid

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

fun Context.appDir(name: String) = File(filesDir, name).apply { mkdirs() }

data class Skin(val file: File, val name: String)

/**
 * Varias skins por sistema en files/skins/<sistema>/ (archivos .deltaskin). La Standard de Delta (assets) se copia
 * como standard.deltaskin. Activa: la elegida para el juego, si no la del sistema, si no la Standard.
 */
object SkinStore {
    private fun dir(ctx: Context, s: GameSystem) = ctx.appDir("skins/${s.id}")

    private fun standard(ctx: Context, s: GameSystem): File {
        val f = File(dir(ctx, s), "standard.deltaskin")
        if (!f.exists()) ctx.assets.open("skins/${s.id}.deltaskin").use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    fun list(ctx: Context, s: GameSystem): List<Skin> {
        runCatching { standard(ctx, s) }
        return dir(ctx, s).listFiles { f -> f.extension == "deltaskin" }.orEmpty()
            .map { Skin(it, skinName(it) ?: it.nameWithoutExtension) }
            .sortedWith(compareBy({ it.file.name != "standard.deltaskin" }, { it.name.lowercase() }))
    }

    fun active(ctx: Context, s: GameSystem, rom: File?): File {
        val p = Prefs.of(ctx)
        val chosen = rom?.let { p.getString("skin_rom_${it.name}", null) } ?: p.getString("skin_${s.id}", null)
        return chosen?.let { File(dir(ctx, s), it) }?.takeIf { it.exists() } ?: standard(ctx, s)
    }

    /** name = null quita la elección (vuelve a la del sistema o a la Standard). */
    fun choose(ctx: Context, s: GameSystem, rom: File?, fileName: String?) = Prefs.of(ctx).edit().apply {
        val key = if (rom != null) "skin_rom_${rom.name}" else "skin_${s.id}"
        if (fileName == null) remove(key) else putString(key, fileName)
    }.apply()

    /** Valida el .deltaskin y lo agrega a la lista de su sistema. Devuelve el sistema o null si no es válido. */
    fun import(ctx: Context, tmp: File, originalName: String): GameSystem? {
        val system = runCatching {
            ZipFile(tmp).use { z -> GameSystem.byDeltaIdentifier(readInfo(z).getString("gameTypeIdentifier")) }
        }.getOrNull() ?: return null
        val base = File(originalName).nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "skin" }
        var dest = File(dir(ctx, system), "$base.deltaskin")
        var n = 2
        while (dest.exists() || dest.name == "standard.deltaskin") dest = File(dir(ctx, system), "${base}_${n++}.deltaskin")
        tmp.copyTo(dest)
        return system
    }

    fun delete(ctx: Context, s: GameSystem, skin: Skin) {
        if (skin.file.name == "standard.deltaskin") return
        skin.file.delete()
        val p = Prefs.of(ctx)
        p.edit().apply { p.all.filter { it.value == skin.file.name && it.key.startsWith("skin_") }.keys.forEach(::remove) }.apply()
    }

    private fun readInfo(z: ZipFile) = JSONObject(z.getInputStream(z.getEntry("info.json")).reader().readText())
    private fun skinName(f: File) = runCatching { ZipFile(f).use { readInfo(it).optString("name") } }.getOrNull()?.ifBlank { null }
}
