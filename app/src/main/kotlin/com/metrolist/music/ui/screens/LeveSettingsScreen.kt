/**
 * Leve Music — Ajustes simples: tema, tamanho da letra, downloads e atualização
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.utils.LeveFontScaleKey
import com.metrolist.music.utils.LeveFontScaleOptions
import com.metrolist.music.utils.LeveThemeKey
import com.metrolist.music.utils.LeveThemes
import com.metrolist.music.utils.LeveUpdater
import com.metrolist.music.utils.SaveDownloadsToDeviceKey
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

@Composable
fun LeveSettingsScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var themeKey by rememberPreference(LeveThemeKey, "spotify")
    var fontScale by rememberPreference(LeveFontScaleKey, 1f)
    var saveDownloads by rememberPreference(SaveDownloadsToDeviceKey, false)
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
            .padding(horizontal = 16.dp),
    ) {
        Text("Ajustes", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))

        SectionTitle("Tema")
        LeveThemes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { t ->
                    val selected = t.key == themeKey
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(t.background))
                            .border(2.dp, if (selected) Color(t.accent) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                            .clickable { themeKey = t.key }
                            .padding(12.dp),
                    ) {
                        Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(Color(t.accent)))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            if (selected) Text("Em uso", fontSize = 11.sp, color = Color(t.accent))
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        SectionTitle("Tamanho da letra")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            LeveFontScaleOptions.forEach { (value, label) ->
                Pill(label, selected = fontScale == value, accent = accent, onAccent = onAccent) { fontScale = value }
            }
        }
        Text("Aa  Exemplo de texto", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))

        SectionTitle("Downloads")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { saveDownloads = !saveDownloads }
                .padding(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Guardar downloads no celular", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (saveDownloads) "Cada música baixada também vai pra Music/Leve Music" else "Downloads ficam só dentro do app",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = saveDownloads, onCheckedChange = { saveDownloads = it })
        }

        SectionTitle("Atalhos")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Pill("Configurações avançadas", selected = false, accent = accent, onAccent = onAccent) { navController.navigate("settings") }
            Pill("Histórico", selected = false, accent = accent, onAccent = onAccent) { navController.navigate("history") }
        }

        SectionTitle("Atualização")
        Text("Leve Music #${LeveUpdater.currentBuild}", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Pill("Verificar atualização", selected = true, accent = accent, onAccent = onAccent) {
            updateStatus = "Procurando…"
            scope.launch {
                LeveUpdater.check()
                    .onSuccess { found ->
                        updateStatus =
                            if (found == null) {
                                "Você já está na versão mais nova"
                            } else {
                                "Baixando #${found.build}…"
                            }
                        if (found != null) {
                            LeveUpdater.download(context, found) { }.onSuccess { file ->
                                updateStatus = "Toque em Atualizar na tela do Android"
                                LeveUpdater.install(context, file)
                            }.onFailure { updateStatus = "Falhou ao baixar" }
                        }
                    }.onFailure { updateStatus = "Sem conexão com o GitHub agora" }
            }
        }
        updateStatus?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) onAccent else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
