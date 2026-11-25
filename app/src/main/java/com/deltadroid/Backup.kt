package com.deltadroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Respaldo local (en lugar de Delta Sync): todo lo del usuario en un .zip. */
object Backup {
    val DIRS = listOf("roms", "saves", "states", "cheats", "skins", "meta", "covers", "system")
    private const val SETTINGS = "settings.json"

    fun export(ctx: Context, out: OutputStream) = export(ctx.filesDir, Prefs.of(ctx).all, out)
    fun import(ctx: Context, input: InputStream) = import(ctx.filesDir, input) { restorePrefs(ctx, it) }

    fun export(root: File, prefs: Map<String, *>, out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            for (d in DIRS) File(root, d).walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry(); n++
            }
            z.putNextEntry(ZipEntry(SETTINGS))
            z.write(prefsToJson(prefs).toByteArray())
            z.closeEntry()
        }
        return n
    }

    /** Restaura sobre lo actual. Devuelve cuántos archivos se escribieron. */
    fun import(root: File, input: InputStream, applyPrefs: (String) -> Unit): Int {
        var n = 0
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == SETTINGS) { applyPrefs(z.readBytes().decodeToString()); continue }
                val dest = safeDest(root, e.name) ?: continue
                dest.parentFile!!.mkdirs()
                dest.outputStream().use { z.copyTo(it) }
                n++
            }
        }
        return n
    }

    fun prefsToJson(all: Map<String, *>) = JSONObject().apply {
        all.forEach { (k, v) ->
            val (t, value) = when (v) {
                is Boolean -> "b" to v; is Float -> "f" to v.toDouble(); is Int -> "i" to v
                is Long -> "l" to v; is String -> "s" to v; is Set<*> -> "S" to JSONArray(v.toList())
                else -> return@forEach
            }
            put(k, JSONObject().put("t", t).put("v", value))
        }
    }.toString()

    private fun restorePrefs(ctx: Context, json: String) {
        val o = JSONObject(json)
        Prefs.of(ctx).edit().apply {
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> putBoolean(k, e.getBoolean("v")); "f" -> putFloat(k, e.getDouble("v").toFloat())
                    "i" -> putInt(k, e.getInt("v")); "l" -> putLong(k, e.getLong("v")); "s" -> putString(k, e.getString("v"))
                    "S" -> putStringSet(k, e.getJSONArray("v").let { a -> List(a.length()) { a.getString(it) }.toSet() })
                }
            }
        }.apply()
    }
}

/** Destino seguro de una entrada del zip: solo dentro de las carpetas del respaldo (evita "zip slip"). */
fun safeDest(root: File, name: String): File? {
    if (name.substringBefore('/') !in Backup.DIRS) return null
    val base = root.canonicalFile
    val f = File(base, name).canonicalFile
    return f.takeIf { it.path.startsWith(base.path + File.separator) }
}
