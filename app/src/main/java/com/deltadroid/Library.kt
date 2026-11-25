package com.deltadroid

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** covers: File (carátula propia) o URL, en orden de preferencia. */
data class Game(val file: File, val system: GameSystem, val title: String, val covers: List<Any>) {
    val srm get() = File(file.parentFile!!.parentFile, "saves/${file.name}.srm")
}

/** ROMs importadas + título/carátula: openvgdb (la base de Delta, por SHA-1) y libretro-thumbnails (por nombre No-Intro). */
object Library {
    fun romsDir(ctx: Context) = File(ctx.filesDir, "roms").apply { mkdirs() }
    private fun metaFile(ctx: Context, rom: File) = File(ctx.filesDir, "meta/${rom.name}.json")

    /** Lento la primera vez por ROM (calcula SHA-1): llamar fuera del hilo principal. */
    fun load(ctx: Context): List<Game> = romsDir(ctx).listFiles().orEmpty().mapNotNull { f ->
        val system = GameSystem.byExt(f.name) ?: return@mapNotNull null
        val metaFile = metaFile(ctx, f)
        val meta = if (metaFile.exists()) JSONObject(metaFile.readText())
        else lookup(ctx, f).also { metaFile.parentFile!!.mkdirs(); metaFile.writeText(it.toString()) }
        val libretro = "https://raw.githubusercontent.com/libretro-thumbnails/${system.thumbRepo(f.name)}" +
            "/master/Named_Boxarts/${Uri.encode(libretroName(f.nameWithoutExtension))}.png"
        val custom = File(ctx.filesDir, "covers/${f.name}").takeIf { it.exists() }
        Game(f, system, meta.optString("customTitle").ifEmpty { meta.optString("title") }.ifEmpty { cleanTitle(f.nameWithoutExtension) },
            listOfNotNull(custom, libretro, meta.optString("cover").ifEmpty { null }))
    }.sortedBy { it.title.lowercase() }

    fun delete(ctx: Context, game: Game) {
        val n = game.file.name
        game.file.delete()
        metaFile(ctx, game.file).delete()
        game.srm.delete()
        File(ctx.filesDir, "states/$n").deleteRecursively()
        File(ctx.filesDir, "cheats/$n.json").delete()
        File(ctx.filesDir, "covers/$n").delete()
        Prefs.of(ctx).edit().remove("skin_rom_$n").apply()
    }

    /** Título propio (vacío = volver al de la base de datos). */
    fun rename(ctx: Context, game: Game, title: String) = updateMeta(ctx, game.file) { put("customTitle", title.trim()) }

    private fun updateMeta(ctx: Context, rom: File, block: JSONObject.() -> Unit) {
        val f = metaFile(ctx, rom)
        val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
        f.parentFile!!.mkdirs()
        f.writeText(o.apply(block).toString())
    }

    private fun lookup(ctx: Context, rom: File): JSONObject {
        val md = MessageDigest.getInstance("SHA-1")
        rom.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val sha1 = md.digest().joinToString("") { "%02X".format(it) }
        val json = JSONObject()
        db(ctx)?.rawQuery(
            "SELECT l.releaseTitleName, l.releaseCoverFront FROM RELEASES l JOIN ROMs r ON r.romID = l.romID WHERE r.romHashSHA1 = ? LIMIT 1",
            arrayOf(sha1),
        )?.use { c -> if (c.moveToFirst()) json.put("title", c.getString(0)).put("cover", c.getString(1)) }
        return json
    }

    private var database: SQLiteDatabase? = null

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase? {
        database?.let { return it }
        val f = ctx.getDatabasePath("openvgdb.sqlite")
        if (!f.exists()) runCatching {
            f.parentFile!!.mkdirs()
            ctx.assets.open("openvgdb.sqlite").use { i -> f.outputStream().use { i.copyTo(it) } }
        }.onFailure { f.delete(); return null } // sin la base solo hay títulos por nombre de archivo
        return SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { database = it }
    }
}

/** "Mario Kart (USA) [!]" -> "Mario Kart" */
fun cleanTitle(stem: String) = stem.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim().ifEmpty { stem }

/** libretro-thumbnails reemplaza estos caracteres por "_" en los nombres de archivo. */
fun libretroName(stem: String) = stem.replace(Regex("""[&*/:`<>?\\|"]"""), "_")
