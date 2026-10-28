/**
 * Leve Music — arquivos do celular tocam direto do armazenamento,
 * sem passar pelo resolvedor/cache do YouTube.
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

@androidx.annotation.OptIn(UnstableApi::class)
class LocalAwareDataSourceFactory(
    private val context: Context,
    private val remoteFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        LocalAwareDataSource(
            local = DefaultDataSource.Factory(context).createDataSource(),
            remote = remoteFactory.createDataSource(),
        )
}

@androidx.annotation.OptIn(UnstableApi::class)
private class LocalAwareDataSource(
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val source = if (scheme == "content" || scheme == "file") local else remote
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
