package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** Public catalog owned by the PhoneXR fork. Game and mod JSON files point to HTTPS downloads. */
object GameStore {
    // Account, friends, and Elix still use the existing backend. It is not a catalog source.
    const val URL_BASE = "https://fjiostsfwfennbovpolc.supabase.co"
    const val REPOSITORY = "MetaMysteries8/phonexr-game-store-latitude-fork"
    const val STORE_URL = "https://github.com/$REPOSITORY"
    const val EMPTY_HINT = "Игр пока нет. Добавьте JSON‑файл игры в ваш репозиторий магазина."

    data class Item(
        val title: String,
        val path: String,
        val bucket: String = "",
        val size: Long = -1L,
        val iconPath: String? = null,
        val descriptionPath: String? = null,
        val url: String? = null,
        val iconUrl: String? = null,
        val descriptionText: String? = null,
    ) {
        val extension get() = (url?.substringBefore('?') ?: path).substringAfterLast('.', "apk").lowercase()
            .takeIf { it in setOf("apk", "pxr") } ?: "apk"
    }

    class StoreException(message: String) : Exception(message)

    /** Listing failures propagate so the UI can distinguish an empty catalog from no connection. */
    fun list(): List<Item> = links("") { it in setOf("apk", "pxr") }

    /** Optional minecraft_mods/ folder containing the same JSON link format. */
    fun mods(): List<Item> = runCatching {
        links("minecraft_mods") { MinecraftMods.isMod("x.$it") }
    }.getOrDefault(emptyList())

    private fun links(folder: String, accepts: (String) -> Boolean): List<Item> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val listing = openUrl("https://api.github.com/repos/$REPOSITORY/contents/$folder?ref=main").use {
            JSONArray(it.inputStream.readBytes().toString(Charsets.UTF_8))
        }
        return (0 until listing.length()).map { listing.getJSONObject(it) }
            .filter { it.optString("type") == "file" && it.optString("name").endsWith(".json", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching {
                    val source = file.optString("download_url").takeIf { it.startsWith("https://") }
                        ?: return@runCatching null
                    val text = openUrl(source).use { it.inputStream.readBytes().toString(Charsets.UTF_8) }
                    parseLink(text, prefix + file.getString("name"))?.takeIf { accepts(it.url!!.substringBefore('?').substringAfterLast('.', "").lowercase()) }
                }.getOrNull()
            }.distinctBy { it.url }.sortedBy { it.title.lowercase() }
    }

    private fun parseLink(text: String, path: String): Item? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val url = json.optString("url").takeIf { it.startsWith("https://") } ?: return null
        return Item(
            title = json.optString("name").ifBlank { path.substringAfterLast('/').substringBeforeLast('.') },
            path = path,
            size = json.optLong("size", -1L),
            url = url,
            iconUrl = json.optString("icon").takeIf { it.startsWith("https://") },
            descriptionText = json.optString("description").takeIf { it.isNotBlank() },
        )
    }

    /** Optional pwa.json or models/*.json in the store repository. */
    fun readText(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_./-]+")) && !name.split('/').contains(".."))
        return openUrl("https://raw.githubusercontent.com/$REPOSITORY/main/$name").use {
            it.inputStream.readBytes().toString(Charsets.UTF_8)
        }
    }

    fun description(item: Item): String? = item.descriptionText

    fun icon(item: Item): Bitmap? = item.iconUrl?.let { url ->
        runCatching { openUrl(url).use { BitmapFactory.decodeStream(it.inputStream) } }.getOrNull()
    }

    fun download(item: Item, directory: File, onProgress: (Float) -> Unit): File {
        val address = item.url ?: throw StoreException("Missing download URL")
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
        if (item.size > 0 && directory.usableSpace in 1 until item.size + (64L shl 20)) {
            throw StoreException("Not enough storage: ${megabytes(item.size)} needed, ${megabytes(directory.usableSpace)} free")
        }
        val extension = address.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { MinecraftMods.isMod("x.$it") } ?: item.extension
        val target = File(directory, item.title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") + ".$extension")
        try {
            openUrl(address).use { response ->
                val total = response.connection.contentLengthLong.takeIf { it > 0 } ?: item.size
                response.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                onProgress(if (total > 0) done.toFloat() / total else -1f)
                            }
                        }
                    }
                }
            }
            onProgress(1f)
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private class Response(val connection: HttpURLConnection) : AutoCloseable {
        val inputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    private fun openUrl(address: String): Response {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https") throw StoreException("Store links must use HTTPS")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                url = URL(url, next ?: throw FileNotFoundException("Empty redirect"))
            } else if (code == 200) {
                return Response(connection)
            } else {
                connection.disconnect()
                throw FileNotFoundException("Store file unavailable (HTTP $code): $address")
            }
        }
        throw StoreException("Too many store redirects")
    }

    private fun megabytes(bytes: Long) = "%.0f MB".format(bytes / (1L shl 20).toDouble())
}
