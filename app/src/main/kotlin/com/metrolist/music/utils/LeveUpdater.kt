/**
 * Leve Music — atualização pelo próprio app (baixa o APK das Releases do GitHub)
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.metrolist.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LeveUpdate(
    val build: Int,
    val apkUrl: String,
    val notes: String,
)

object LeveUpdater {
    private const val REPO = "Gabrielbatman266/Leve-music"
    private const val TAG_PREFIX = "leve-music-"
    private val json = Json { ignoreUnknownKeys = true }

    /** Número da compilação instalada (versionCode = 1000 + número da compilação no GitHub). */
    val currentBuild: Int
        get() = (BuildConfig.VERSION_CODE - 1000).coerceAtLeast(0)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LeveMusic")
        }

    suspend fun check(): Result<LeveUpdate?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = open("https://api.github.com/repos/$REPO/releases?per_page=30")
                check(conn.responseCode == 200) {
                    if (conn.responseCode == 403) "GitHub pediu para esperar um pouco (limite de consultas)" else "GitHub respondeu ${conn.responseCode}"
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                newestFrom(text, currentBuild)
            }
        }

    /** Escolhe a compilação mais nova com APK (ignora tags de outros apps e releases sem APK). */
    internal fun newestFrom(text: String, current: Int): LeveUpdate? {
        val releases = json.parseToJsonElement(text) as? JsonArray ?: return null
        val newest =
            releases.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val tag = (obj["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (!tag.startsWith(TAG_PREFIX)) return@mapNotNull null
                val build = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return@mapNotNull null
                val apk =
                    (obj["assets"] as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true }
                        ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull }
                        ?: return@mapNotNull null
                LeveUpdate(build, apk, (obj["body"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }.maxByOrNull { it.build }
        return newest?.takeIf { it.build > current }
    }

    suspend fun download(
        context: Context,
        update: LeveUpdate,
        onProgress: (Float) -> Unit,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "LeveMusic-${update.build}.apk")
                downloadTo(update.apkUrl, file, onProgress)
                file
            }
        }

    /**
     * Baixa conferindo tudo: resposta de erro não vira "APK", download cortado no meio
     * é apagado e o arquivo precisa começar como um APK de verdade (zip "PK").
     * Antes um download interrompido gerava "Erro ao analisar o pacote" no instalador.
     */
    internal fun downloadTo(url: String, file: File, onProgress: (Float) -> Unit) {
        val part = File(file.path + ".part")
        try {
            val conn = open(url).apply { setRequestProperty("Accept", "application/octet-stream") }
            check(conn.responseCode == 200) { "Download falhou (${conn.responseCode})" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            check(total <= 0 || done == total) { "Download cortado no meio ($done de $total bytes)" }
            val magic = part.inputStream().use { val b = ByteArray(2); if (it.read(b) == 2) String(b) else "" }
            check(magic == "PK") { "O arquivo baixado não é um APK" }
            file.delete()
            check(part.renameTo(file)) { "Não deu para salvar o APK" }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    /** Abre o instalador do Android. Se faltar permissão, abre a tela pra liberar. */
    fun install(
        context: Context,
        file: File,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
