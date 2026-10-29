/**
 * Leve Music — o YouTube às vezes só entrega o vídeo em "pedaços" (Range limitado).
 * Esta fonte pede pedaço por pedaço e emenda tudo, para o ExoPlayer enxergar um arquivo contínuo.
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

@androidx.annotation.OptIn(UnstableApi::class)
class ChunkedRangeDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val chunkSize: Long,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = ChunkedRangeDataSource(upstream.createDataSource(), chunkSize)
}

@androidx.annotation.OptIn(UnstableApi::class)
private class ChunkedRangeDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long,
) : DataSource {
    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkLeft = 0L
    private var chunkRequested = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        openChunk()
        return remaining
    }

    private fun openChunk(): Boolean {
        if (opened) {
            upstream.close()
            opened = false
        }
        if (remaining == 0L) return false
        val want = if (remaining == C.LENGTH_UNSET.toLong()) chunkSize else minOf(chunkSize, remaining)
        val spec = requireNotNull(baseSpec).subrange(position - requireNotNull(baseSpec).position, want)
        val got = upstream.open(spec)
        opened = true
        chunkRequested = want
        chunkLeft = if (got == C.LENGTH_UNSET.toLong()) want else got
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val read = upstream.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkLeft -= read
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
                return read
            }
            // Pedaço acabou: se veio inteiro, ainda pode ter mais arquivo
            val chunkWasFull = chunkLeft <= 0L
            if (!chunkWasFull || remaining == 0L) return C.RESULT_END_OF_INPUT
            val more =
                try {
                    openChunk()
                } catch (_: java.io.IOException) {
                    // 416 / fim do arquivo exatamente na borda do pedaço
                    false
                }
            if (!more) return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) {
            upstream.close()
            opened = false
        }
    }
}
