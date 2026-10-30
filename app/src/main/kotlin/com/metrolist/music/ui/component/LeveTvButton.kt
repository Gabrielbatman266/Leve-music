package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.tv.LeveTvLink
import kotlinx.coroutines.launch

/**
 * Botão "TV" do player: manda a música para o Leve Launcher TV
 * e transforma o celular em controle remoto.
 */
@Composable
fun LeveTvButton(
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val link = remember(playerConnection) { playerConnection.service.tvLink }
    val connected by link.connected.collectAsState()
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(link) {
        link.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = if (connected != null) 0.45f else 0.25f))
                .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.leve_tv),
            contentDescription = "Tocar na TV",
            tint = if (connected != null) MaterialTheme.colorScheme.primary else tintColor,
            modifier = Modifier.size(22.dp),
        )
    }

    if (open) LeveTvDialog(link = link, onDismiss = { open = false })
}

@Composable
private fun LeveTvDialog(
    link: LeveTvLink,
    onDismiss: () -> Unit,
) {
    val connected by link.connected.collectAsState()
    val found by link.found.collectAsState()
    val state by link.state.collectAsState()
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(link.lastTv?.host.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(link) {
        link.startDiscovery()
        onDispose { link.stopDiscovery() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.leve_tv), contentDescription = null) },
        title = { Text(if (connected != null) "Tocando na TV" else "Tocar na TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val tv = connected
                if (tv != null) {
                    Text(tv.name, style = MaterialTheme.typography.titleMedium)
                    val s = state
                    Text(
                        when {
                            s == null -> "Conectando…"
                            s.error.isNotEmpty() -> s.error
                            s.loading -> "Carregando na TV…"
                            s.playing -> "Tocando na TV"
                            else -> "Pausado na TV"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Use o player do celular normalmente: pausar, pular e a barra de tempo controlam a TV.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { link.volume(-1) }) { Text("Volume −") }
                        OutlinedButton(onClick = { link.volume(1) }) { Text("Volume +") }
                    }
                } else {
                    Text(
                        "Celular e TV no mesmo Wi-Fi, com o Leve Launcher TV aberto na TV.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (found.isEmpty()) {
                        Text("Procurando TVs…", style = MaterialTheme.typography.bodyMedium)
                    }
                    found.forEach { t ->
                        OutlinedButton(
                            onClick = {
                                link.connect(t)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t.name) }
                    }
                    OutlinedTextField(
                        value = ip,
                        onValueChange = {
                            ip = it
                            error = null
                        },
                        label = { Text("Ou digite o IP que aparece na TV") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (connected != null) {
                TextButton(onClick = {
                    link.disconnect()
                    onDismiss()
                }) { Text("Voltar pro celular") }
            } else {
                TextButton(
                    enabled = !busy && ip.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val tv = link.probe(ip)
                            busy = false
                            if (tv == null) {
                                error = "Não achei a TV nesse IP"
                            } else {
                                link.connect(tv)
                                onDismiss()
                            }
                        }
                    },
                ) { Text(if (busy) "Conectando…" else "Conectar") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
