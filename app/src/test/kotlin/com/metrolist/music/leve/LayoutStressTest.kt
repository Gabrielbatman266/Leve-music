package com.metrolist.music.leve

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height as dpHeight
import androidx.compose.ui.unit.width as dpWidth
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.leve.Placar.fail
import com.metrolist.music.leve.Placar.run
import com.metrolist.music.ui.screens.ChannelActions
import com.metrolist.music.ui.screens.LocalSongRow
import com.metrolist.music.ui.screens.QuickTile
import com.metrolist.music.ui.screens.VideoRow
import com.metrolist.music.ui.screens.formatSeconds
import com.metrolist.music.utils.LocalMusic
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * N6 — layout com letra grande (fonte "Enorme" do app × letra grande do sistema) em telas pequenas.
 * Mede o texto de verdade (gráficos nativos do Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutStressTest {
    @get:Rule val rule = createComposeRule()

    private val scales = listOf(1f to "letra normal", 1.45f to "fonte Enorme", 1.45f * 1.3f to "Enorme + sistema grande")

    // setContent só pode 1 vez por teste: trocamos o conteúdo/tamanho por estado
    private var started = false
    private val width = mutableStateOf(320.dp)
    private val scale = mutableStateOf(1f)
    private val body = mutableStateOf<@Composable () -> Unit>({})

    private fun show(widthDp: Dp, fontScale: Float, content: @Composable () -> Unit) {
        width.value = widthDp
        scale.value = fontScale
        body.value = content
        if (!started) {
            started = true
            rule.setContent {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Box(Modifier.width(width.value)) { body.value() }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult? {
        val list = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(list)
        return list.firstOrNull()
    }

    private fun scenario(area: String, name: String, block: () -> String?) = run(6, area, name, block)

    @Test fun atalhos_da_home_com_letra_grande() {
        val title = "Henrique e Juliano - Liberdade Provisória (Ao Vivo)"
        for ((s, label) in scales) {
            scenario("Home", "atalho rápido · $label · 180dp") {
                show(180.dp, s) { QuickTile(title = title, thumbnail = null, isLikedTile = false, active = false, onClick = {}, onLongClick = {}) }
                val tile = rule.onRoot().getUnclippedBoundsInRoot()
                val text = rule.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
                if (text.bottom <= tile.bottom + 0.5.dp && text.top >= tile.top - 0.5.dp) "título inteiro dentro do atalho (${tile.dpHeight})"
                else fail("título cortado: texto ${text.dpHeight} num atalho de ${tile.dpHeight}")
            }
        }
    }

    @Test fun musica_do_celular_com_letra_grande() {
        val song = LocalMusic.song(1, "/Music/a.mp3", "Faixa de um álbum com nome bem comprido mesmo", "Artista", "Álbum", 1, 3_725_000, 0, 0, 2020, 1012, true)
        for ((s, label) in scales) {
            scenario("No celular", "faixa 12 · duração 1:02:05 · $label · 320dp") {
                show(320.dp, s) { LocalSongRow(song = song, active = false, showTrack = true, onClick = {}, onLongClick = {}) }
                val track = rule.onNodeWithText("12", useUnmergedTree = true).layout()
                val dur = rule.onNodeWithText("1:02:05", useUnmergedTree = true).layout()
                val title = rule.onNodeWithText(song.title, useUnmergedTree = true).getUnclippedBoundsInRoot()
                when {
                    track == null || dur == null -> fail("textos não encontrados")
                    track.lineCount > 1 -> fail("número da faixa quebrou em ${track.lineCount} linhas")
                    dur.lineCount > 1 -> fail("duração quebrou em ${dur.lineCount} linhas")
                    title.dpWidth < 100.dp -> fail("título espremido (${title.dpWidth})")
                    else -> "ok (título com ${title.dpWidth})"
                }
            }
        }
    }

    @Test fun video_com_letra_grande() {
        val v = SongItem(
            id = "dQw4w9WgXcQ", title = "Vídeo com um título enorme que precisa caber em duas linhas sem estourar o layout da lista",
            artists = listOf(Artist(name = "Canal Muito Famoso Oficial", id = "UC123")), duration = 3725, thumbnail = "https://i.ytimg.com/x.jpg",
        )
        for ((s, label) in scales) {
            scenario("Vídeos", "linha de vídeo · $label · 320dp") {
                show(320.dp, s) { VideoRow(video = v, onClick = {}, onLongClick = {}) }
                val t = rule.onNodeWithText(v.title, useUnmergedTree = true).layout()
                val tb = rule.onNodeWithText(v.title, useUnmergedTree = true).getUnclippedBoundsInRoot()
                val dur = rule.onNodeWithText(formatSeconds(3725), useUnmergedTree = true).layout()
                when {
                    t == null -> fail("sem título")
                    t.lineCount > 2 -> fail("título com ${t.lineCount} linhas")
                    tb.dpWidth < 110.dp -> fail("título espremido em ${tb.dpWidth}")
                    dur != null && dur.lineCount > 1 -> fail("duração quebrou linha")
                    else -> "título ${tb.dpWidth} de largura"
                }
            }
        }
    }

    @Test fun botoes_do_canal() {
        for ((s, label) in scales) {
            scenario("Canais", "botões do canal · $label · 320dp") {
                show(320.dp, s) { ChannelActions(enabled = true, onPlayAll = {}, onShuffle = {}, onOpenYouTube = {}) }
                val root = rule.onRoot().getUnclippedBoundsInRoot()
                val bad = mutableListOf<String>()
                for (b in listOf("Ouvir tudo", "Aleatório", "Abrir no YouTube")) {
                    val r = rule.onNodeWithText(b, useUnmergedTree = true).getUnclippedBoundsInRoot()
                    val l = rule.onNodeWithText(b, useUnmergedTree = true).layout()
                    if (r.right > root.right + 0.5.dp || r.left < root.left - 0.5.dp) bad += "$b fora da tela"
                    if (r.dpHeight < 40.dp) bad += "$b com ${r.dpHeight} (pequeno pro dedo)"
                    if (l != null && l.lineCount > 1) bad += "$b quebrou linha"
                }
                if (bad.isEmpty()) "3 botões visíveis e tocáveis" else fail(bad.joinToString())
            }
        }
        scenario("Canais", "canal vazio: só \"Abrir no YouTube\"") {
            show(320.dp, 1f) { ChannelActions(enabled = false, onPlayAll = {}, onShuffle = {}, onOpenYouTube = {}) }
            val n = rule.onAllNodesWithText("Ouvir tudo").fetchSemanticsNodes().size
            if (n == 0) "ok" else fail("mostra \"Ouvir tudo\" sem vídeos")
        }
    }

    @Test fun botoes_respondem() {
        scenario("Botões", "atalho: toque e segurar") {
            var click = 0
            var long = 0
            show(200.dp, 1f) { QuickTile("Músicas curtidas", null, true, false, { click++ }, { long++ }) }
            rule.onNodeWithText("Músicas curtidas").performClickCompat()
            rule.onNodeWithText("Músicas curtidas").performLongClickCompat()
            if (click == 1 && long == 1) "ok" else fail("toque=$click segurar=$long")
        }
        scenario("Botões", "canal: os 3 botões chamam a ação certa") {
            val hits = mutableListOf<String>()
            show(360.dp, 1f) { ChannelActions(true, { hits += "tudo" }, { hits += "aleatorio" }, { hits += "youtube" }) }
            listOf("Ouvir tudo", "Aleatório", "Abrir no YouTube").forEach { rule.onNodeWithText(it).performClickCompat() }
            if (hits == listOf("tudo", "aleatorio", "youtube")) "ok" else fail(hits.toString())
        }
        scenario("Botões", "vídeo: toque assiste, segurar ouve") {
            val v = SongItem(id = "x", title = "Vídeo", artists = listOf(Artist("Canal", "UC1")), thumbnail = "")
            var click = 0
            var long = 0
            show(360.dp, 1f) { VideoRow(v, { click++ }, { long++ }) }
            rule.onNodeWithText("Vídeo").performClickCompat()
            rule.onNodeWithText("Vídeo").performLongClickCompat()
            if (click == 1 && long == 1) "ok" else fail("toque=$click segurar=$long")
        }
    }
}

private fun SemanticsNodeInteraction.performClickCompat() = performTouchInput { click() }

private fun SemanticsNodeInteraction.performLongClickCompat() = performTouchInput { longClick() }
