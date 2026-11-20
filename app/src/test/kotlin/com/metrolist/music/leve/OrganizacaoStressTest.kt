package com.metrolist.music.leve

import com.metrolist.music.leve.Placar.budget
import com.metrolist.music.leve.Placar.fail
import com.metrolist.music.leve.Placar.run
import com.metrolist.music.leve.Placar.timed
import com.metrolist.music.utils.AudioKind
import com.metrolist.music.utils.LeveStyle
import com.metrolist.music.utils.LocalMusic
import com.metrolist.music.utils.LocalSong
import com.metrolist.music.viewmodels.DeviceSort
import com.metrolist.music.viewmodels.DeviceTab
import com.metrolist.music.viewmodels.groupedBy
import com.metrolist.music.viewmodels.sortedForDevice
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import kotlin.random.Random

/**
 * Músicas do celular (organização) e detector de estilos — do fácil (N1) ao impossível (N10).
 * Roda na JVM pura: rápido, sem celular.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OrganizacaoStressTest {
    private var nextId = 1L

    private fun song(
        title: String?,
        artist: String? = "Artista",
        album: String? = "Álbum",
        albumId: Long = 1,
        path: String = "/storage/emulated/0/Music/${title ?: "x"}.mp3",
        durationMs: Long = 200_000,
        date: Long = 1_700_000_000,
        size: Long = 5_000_000,
        year: Int = 2020,
        track: Int = 0,
        isMusic: Boolean = true,
    ): LocalSong =
        LocalMusic.song(nextId++, path, title, artist, album, albumId, durationMs, date, size, year, track, isMusic)

    private fun searchFilter(list: List<LocalSong>, query: String): List<LocalSong> {
        // mesmo filtro da tela "No celular"
        val q = LocalMusic.norm(query)
        return if (q.isEmpty()) list else list.filter { it.titleKey.contains(q) || it.artistKey.contains(q) || it.albumKey.contains(q) }
    }

    private fun library(n: Int, seed: Int = 1): List<LocalSong> {
        val r = Random(seed)
        val artists = List(maxOf(10, n / 15)) { i -> listOf("Anitta", "Matuê", "Henrique & Juliano", "Ludmilla", "Djavan", "Iron Maiden", "Zé Neto", "Ávine", "Éden", "Coldplay")[i % 10] + if (i >= 10) " $i" else "" }
        val words = listOf("amor", "saudade", "coração", "noite", "água", "festa", "você", "céu", "mar", "fé", "zona", "baile", "lua", "ônibus", "estrela")
        return List(n) { i ->
            val a = artists[r.nextInt(artists.size)]
            val title = List(1 + r.nextInt(4)) { words[r.nextInt(words.size)] }.joinToString(" ").replaceFirstChar { it.uppercase() } + " $i"
            val albumIdx = r.nextInt(maxOf(5, n / 10))
            song(
                title, a, "Álbum $albumIdx", albumIdx.toLong() + 1,
                path = "/storage/emulated/0/Music/pasta${r.nextInt(50)}/$i.mp3",
                durationMs = 30_000L + r.nextInt(400_000), date = 1_600_000_000L + r.nextInt(100_000_000),
                size = r.nextLong(500_000, 15_000_000), year = if (r.nextInt(10) == 0) 0 else 1970 + r.nextInt(56),
                track = (1 + r.nextInt(2)) * 1000 + r.nextInt(20),
            )
        }
    }

    // ============================================================ N1 fácil
    @Test fun n01_facil() {
        val list = listOf(song("Banana"), song("Abacaxi"), song("Caju", artist = "Anitta"), song("Damasco", artist = "Anitta"))
        run(1, "Organização", "A–Z simples") {
            val t = list.sortedForDevice(DeviceSort.A_Z).map { it.title }
            if (t == listOf("Abacaxi", "Banana", "Caju", "Damasco")) "ok" else fail(t.toString())
        }
        run(1, "Organização", "agrupar por artista") {
            val g = list.groupedBy(DeviceTab.ARTISTS)
            val anitta = g.firstOrNull { it.name == "Anitta" }
            if (g.size == 2 && anitta?.songs?.size == 2) "2 artistas" else fail(g.map { "${it.name}=${it.songs.size}" }.toString())
        }
        run(1, "Organização", "busca simples") {
            val f = searchFilter(list, "anitta")
            if (f.size == 2) "ok" else fail("achou ${f.size}")
        }
        run(1, "Estilos", "ASMR é ASMR, Lo-fi é Lo-fi") {
            val a = LeveStyle.detect("ASMR tapping sounds for sleep")
            val b = LeveStyle.detect("lofi hip hop radio - beats to relax/study to")
            if (a == LeveStyle.ASMR && b == LeveStyle.LOFI) "ok" else fail("$a / $b")
        }
    }

    // ============================================================ N3 médio: organização de verdade
    @Test fun n03_organizacao_real() {
        run(3, "Organização", "A–Z com acento (Água no A, não depois do Z)") {
            val l = listOf("Zebra", "Água de Beber", "abc", "Ébano", "Ouro", "Óculos", "Ímã", "uva").map { song(it) }
            val t = l.sortedForDevice(DeviceSort.A_Z).map { it.title }
            val want = listOf("abc", "Água de Beber", "Ébano", "Ímã", "Óculos", "Ouro", "uva", "Zebra")
            if (t == want) "ok" else fail(t.joinToString())
        }
        run(3, "Organização", "Z–A é o contrário do A–Z") {
            val l = library(300)
            val az = l.sortedForDevice(DeviceSort.A_Z).map { it.mediaStoreId }
            val za = l.sortedForDevice(DeviceSort.Z_A).map { it.mediaStoreId }
            if (az == za.reversed()) "ok" else fail("ordens não batem")
        }
        run(3, "Organização", "mesmo artista escrito diferente vira 1 só") {
            val l = listOf("Anitta", "ANITTA", "anitta ", " Anitta", "Anítta").map { song("x$it", artist = it) }
            val g = l.groupedBy(DeviceTab.ARTISTS)
            if (g.size == 1) "1 grupo \"${g[0].name}\"" else fail("${g.size} grupos: ${g.map { it.name }}")
        }
        run(3, "Organização", "álbuns com o mesmo nome de artistas diferentes não se misturam") {
            val l = listOf(
                song("A1", artist = "Queen", album = "Greatest Hits", albumId = 10),
                song("A2", artist = "Queen", album = "Greatest Hits", albumId = 10),
                song("B1", artist = "ABBA", album = "Greatest Hits", albumId = 20),
            )
            val g = l.groupedBy(DeviceTab.ALBUMS)
            if (g.size == 2 && g.map { it.key }.toSet().size == 2) "2 álbuns (${g.joinToString { "${it.name} · ${it.subtitle}" }})"
            else fail("${g.size} álbum(ns)")
        }
        run(3, "Organização", "álbum duplo: CD 1 inteiro antes do CD 2") {
            val l = listOf(2002, 1002, 2001, 1001).map { t -> song("faixa $t", album = "Duplo", albumId = 5, track = t) }
            val g = l.groupedBy(DeviceTab.ALBUMS).single()
            val order = g.songs.map { it.trackRaw }
            if (order == listOf(1001, 1002, 2001, 2002)) "ok" else fail(order.toString())
        }
        run(3, "Organização", "pastas com o mesmo nome em lugares diferentes") {
            val l = listOf(
                song("a", path = "/storage/emulated/0/Music/Rock/a.mp3"),
                song("b", path = "/storage/emulated/0/Download/Rock/b.mp3"),
                song("c", path = "/storage/emulated/0/Music/Rock/c.mp3"),
            )
            val g = l.groupedBy(DeviceTab.FOLDERS)
            val names = g.map { it.name }
            if (g.size == 2 && names.toSet().size == 2) names.joinToString() else fail(names.toString())
        }
        run(3, "Organização", "busca sem acento acha com acento") {
            val l = listOf(song("Coração", artist = "João Gomes"), song("Água", artist = "X"), song("Outra", artist = "Y"))
            val a = searchFilter(l, "joao").size
            val b = searchFilter(l, "AGUA").size
            val c = searchFilter(l, "coracao").size
            if (a == 1 && b == 1 && c == 1) "ok" else fail("joao=$a agua=$b coracao=$c")
        }
        run(3, "Organização", "ano desconhecido vai pro fim") {
            val l = listOf(song("a", year = 0), song("b", year = 2001), song("c", year = 2024))
            val t = l.sortedForDevice(DeviceSort.YEAR).map { it.year }
            if (t == listOf(2024, 2001, 0)) "ok" else fail(t.toString())
        }
        run(3, "Organização", "mesma lista sempre na mesma ordem") {
            val base = library(500)
            val ok = DeviceSort.entries.all { s ->
                base.shuffled(Random(1)).sortedForDevice(s).map { it.mediaStoreId } == base.shuffled(Random(2)).sortedForDevice(s).map { it.mediaStoreId }
            }
            if (ok) "todas as 10 ordens estáveis" else fail("ordem muda entre aberturas")
        }
        val cases = listOf(
            Triple("/storage/emulated/0/Music/Call Me Maybe.mp3", 200_000L, AudioKind.MUSIC),
            Triple("/storage/emulated/0/Music/Def Jam Records/Kanye - Stronger.mp3", 200_000L, AudioKind.MUSIC),
            Triple("/storage/emulated/0/Music/Recordações/musica.mp3", 200_000L, AudioKind.MUSIC),
            Triple("/storage/emulated/0/Download/Recordings by Studio/faixa.mp3", 200_000L, AudioKind.MUSIC),
            Triple("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202611/PTT-1.opus", 15_000L, AudioKind.WHATSAPP),
            Triple("/storage/emulated/0/MIUI/sound_recorder/Gravação 1.mp3", 300_000L, AudioKind.RECORDING),
            Triple("/storage/emulated/0/Recordings/Call/Chamada 1.m4a", 300_000L, AudioKind.RECORDING),
            Triple("/storage/emulated/0/Ringtones/toque.mp3", 30_000L, AudioKind.OTHER),
            Triple("/storage/emulated/0/Telegram/Telegram Audio/a.ogg", 300_000L, AudioKind.OTHER),
        )
        for ((path, dur, want) in cases) {
            run(3, "Organização", "tipo de áudio: …${path.takeLast(42)}") {
                val k = song(path.substringAfterLast('/'), path = path, durationMs = dur).kind
                if (k == want) "${k.label}" else fail("virou ${k.label} (esperado ${want.label})")
            }
        }
        run(3, "Organização", "tags sujas viram texto legível") {
            val a = LocalMusic.song(1, "/x/Minha Música.mp3", null, "<unknown>", "  ", 0, 100_000, 0, 0, 20235, -5, true)
            val b = LocalMusic.song(2, "/x/y.mp3", "  Título\u0000 ", "Art\u0000ista", null, 0, -1, 0, -9, 1999, 3, true)
            val problems = listOfNotNull(
                "título vazio".takeIf { a.title != "Minha Música" },
                "artista <unknown>".takeIf { a.artist != "Artista desconhecido" },
                "álbum vazio".takeIf { a.album != "Álbum desconhecido" },
                "ano 20235".takeIf { a.year != 0 },
                "faixa -5".takeIf { a.track != 0 },
                "caractere nulo".takeIf { '\u0000' in b.title || '\u0000' in b.artist },
                "duração negativa".takeIf { b.durationMs < 0 },
            )
            if (problems.isEmpty()) "ok" else fail(problems.joinToString())
        }
    }

    // ============================================================ N4 estilos (mixes e categorias da home)
    @Test fun n04_estilos() {
        val data = listOf(
            "ASMR Sleep Clinic Roleplay" to LeveStyle.ASMR, "ASMR mouth sounds" to LeveStyle.ASMR, "sussurrando até você dormir" to LeveStyle.ASMR,
            "Meditação guiada para ansiedade" to LeveStyle.MEDITACAO, "432Hz cura" to LeveStyle.MEDITACAO, "Yoga para iniciantes" to LeveStyle.MEDITACAO,
            "Som de chuva para dormir 10 horas" to LeveStyle.DORMIR, "White noise 8h" to LeveStyle.DORMIR, "Brown noise for focus" to LeveStyle.DORMIR,
            "Flow Podcast #500 - convidado" to LeveStyle.PODCAST, "Podpah - Cortes engraçados" to LeveStyle.PODCAST, "Palestra motivacional" to LeveStyle.PODCAST,
            "Minecraft hardcore 100 dias" to LeveStyle.GAMES, "GTA 6 gameplay" to LeveStyle.GAMES, "Jogando Roblox com inscritos" to LeveStyle.GAMES,
            "lofi girl - beats to relax" to LeveStyle.LOFI, "MC Ryan SP - Funk 2026" to LeveStyle.FUNK, "Montagem do baile" to LeveStyle.FUNK,
            "Matuê - Trap de Cria" to LeveStyle.RAP, "Racionais - Rap é compromisso" to LeveStyle.RAP, "Drill brasileiro" to LeveStyle.RAP,
            "Louvor e adoração" to LeveStyle.GOSPEL, "Hino da harpa cristã" to LeveStyle.GOSPEL, "Henrique e Juliano - Sertanejo ao vivo" to LeveStyle.SERTANEJO,
            "Pagode do Menos é Mais" to LeveStyle.PAGODE, "Samba de raiz" to LeveStyle.PAGODE, "Piseiro 2026 - João Gomes" to LeveStyle.FORRO,
            "Iron Maiden - The Trooper (heavy metal)" to LeveStyle.ROCK, "Linkin Park rock" to LeveStyle.ROCK, "Daft Punk - Get Lucky" to LeveStyle.ELETRONICA,
            "Alok - Hear Me Now" to LeveStyle.ELETRONICA, "Phonk brasileiro slowed" to LeveStyle.ELETRONICA, "BTS - Dynamite" to LeveStyle.KPOP,
            "Chopin - Nocturne Op. 9" to LeveStyle.CLASSICA, "Bach - Cello Suite" to LeveStyle.CLASSICA,
            // armadilhas (antes caíam no estilo errado)
            "Rapaz, que voz! Tiago Iorc" to LeveStyle.MUSICA, "Raposa e as uvas - história infantil" to LeveStyle.MUSICA,
            "Romeo Santos - Bachata Rosa" to LeveStyle.MUSICA, "Banda Sonora - Lua" to LeveStyle.MUSICA, "Pedro Capó - Calma" to LeveStyle.MUSICA,
            "Naomi Scott - Speechless" to LeveStyle.MUSICA, "George Michael - Careless Whisper" to LeveStyle.MUSICA,
            "Sleep Token - The Summoning" to LeveStyle.MUSICA, "Os Trapalhões - tema" to LeveStyle.MUSICA, "Paula Fernandes - Pássaro de Fogo" to LeveStyle.MUSICA,
            "Coldplay - Fix You" to LeveStyle.MUSICA, "Djavan - Oceano" to LeveStyle.MUSICA, "Deep End (Deep.)" to LeveStyle.MUSICA,
            "Terapia - Matuê" to LeveStyle.MUSICA, "Sonora Santanera - cumbia" to LeveStyle.MUSICA, "Bruno Mars - Uptown Funk" to LeveStyle.MUSICA,
            "Hinode - comercial" to LeveStyle.MUSICA, "Cortes e recortes de papel" to LeveStyle.PODCAST,
        )
        val wrong = data.filter { (t, want) -> LeveStyle.detect(t) != want }
        val acc = 100.0 * (data.size - wrong.size) / data.size
        run(4, "Estilos", "acerto em ${data.size} títulos reais") {
            val d = String.format("%.0f%% certos", acc)
            if (acc >= 95) d else fail("$d; erros: " + wrong.take(8).joinToString { "${it.first} → ${LeveStyle.detect(it.first)}" })
        }
        run(4, "Estilos", "armadilhas: palavras parecidas (rapaz, bachata, sonora, calma…)") {
            val traps = data.drop(35).filter { it.second == LeveStyle.MUSICA }
            val bad = traps.filter { LeveStyle.detect(it.first) != LeveStyle.MUSICA }
            if (bad.isEmpty()) "${traps.size} armadilhas evitadas" else fail(bad.joinToString { "${it.first} → ${LeveStyle.detect(it.first)}" })
        }
        run(4, "Home", "aba Música não esconde \"Calma\" e \"Rainha\"") {
            val songs = listOf("pedro capó - calma", "rainha da sucata - tema", "rainbow - kesha", "relax - trilha")
            val hidden = songs.filterNot { LeveStyle.homeAccepts("MUSIC", it) }
            if (hidden.isEmpty()) "ok" else fail("sumiram: $hidden")
        }
        run(4, "Home", "categorias usam o mesmo detector dos mixes") {
            val pairs = listOf("ASMR" to "asmr tapping", "SLEEP" to "som de chuva para dormir", "PODCAST" to "flow podcast",
                "GAMES" to "minecraft survival", "FUNK" to "funk do baile", "RAP" to "rap nacional", "GOSPEL" to "louvor",
                "BRASIL" to "sertanejo universitário", "ROCK" to "rock nacional", "ELETRONICA" to "alok remix")
            val bad = pairs.filterNot { (c, t) -> LeveStyle.homeAccepts(c, t) }
            if (bad.isEmpty()) "10 categorias ok" else fail(bad.toString())
        }
        run(4, "Home", "esconder tema \"rap\" não some com \"terapia\"") {
            val a = LeveStyle.containsTopic("terapia - matuê", "rap")
            val b = LeveStyle.containsTopic("rap nacional", "rap")
            val c = LeveStyle.containsTopic("sou fã de bbb e reality", "bbb")
            if (!a && b && c) "ok" else fail("terapia=$a rap=$b bbb=$c")
        }
        run(4, "Estilos", "vídeo longo sem estilo = Vídeos parecidos") {
            val a = LeveStyle.detect("Documentário sobre o espaço", 3600)
            val b = LeveStyle.detect("Djavan - Oceano", 300)
            if (a == LeveStyle.VIDEOS && b == LeveStyle.MUSICA) "ok" else fail("$a / $b")
        }
    }

    // ============================================================ N7 velocidade: 20 mil músicas
    @Test fun n07_velocidade_20mil() {
        val lib = library(20_000)
        DeviceSort.entries.forEach { s ->
            run(7, "Organização", "ordenar 20 mil: ${s.label}") {
                lib.sortedForDevice(s)
                val (_, ms) = timed { repeat(3) { lib.sortedForDevice(s) } }
                budget("tempo", ms / 3, 120.0, "ms")
            }
        }
        listOf(DeviceTab.ARTISTS, DeviceTab.ALBUMS, DeviceTab.FOLDERS).forEach { tab ->
            run(7, "Organização", "agrupar 20 mil por ${tab.label}") {
                lib.groupedBy(tab)
                val (g, ms) = timed { lib.groupedBy(tab) }
                if (g.isEmpty()) fail("sem grupos") else budget("${g.size} grupos em", ms, 150.0, "ms")
            }
        }
        run(7, "Organização", "busca por letra em 20 mil") {
            val q = "saudade"
            searchFilter(lib, "s")
            val (_, ms) = timed { for (i in 1..q.length) searchFilter(lib, q.substring(0, i)) }
            budget("por letra", ms / q.length, 16.0, "ms")
        }
        run(7, "Estilos", "detectar estilo de 5 mil títulos (home)") {
            val titles = lib.take(5000).map { it.title + " " + it.artist }
            val (_, ms) = timed { titles.forEach { LeveStyle.detect(it) } }
            budget("total", ms, 150.0, "ms")
        }
    }

    // ============================================================ N9 extremo: 200 mil músicas
    @Test fun n09_extremo_200mil() {
        val m0 = Placar.usedMb()
        val (lib, loadMs) = timed { library(200_000, seed = 9) }
        val mem = Placar.usedMb() - m0
        run(9, "Organização", "200 mil músicas na memória") { budget("RAM", mem.toDouble(), 250.0, "MB") }
        run(9, "Organização", "ordenar 200 mil A–Z") {
            val (_, ms) = timed { lib.sortedForDevice(DeviceSort.A_Z) }
            budget("tempo", ms, 1500.0, "ms")
        }
        run(9, "Organização", "agrupar 200 mil por pasta") {
            val (g, ms) = timed { lib.groupedBy(DeviceTab.FOLDERS) }
            budget("${g.size} pastas em", ms, 1500.0, "ms")
        }
        run(9, "Organização", "busca por letra em 200 mil") {
            val (_, ms) = timed { searchFilter(lib, "coração") }
            budget("por letra", ms, 100.0, "ms")
        }
        println("biblioteca gerada em ${loadMs.toInt()} ms")
    }

    // ============================================================ N10 impossível
    @Test fun n10_impossivel() {
        run(10, "Organização", "nomes absurdos (emoji, 5 mil letras, RTL, vazio)") {
            val weird = listOf("", " ", "😀🔥", "a".repeat(5000), "حبيبي", "東京", "​", "\uD800", "null", "<unknown>", "'; DROP TABLE", "\n\t")
            val l = weird.flatMap { w -> listOf(song(w, artist = w, album = w, path = "/$w/$w.mp3")) }
            DeviceSort.entries.forEach { l.sortedForDevice(it) }
            DeviceTab.entries.forEach { l.groupedBy(it) }
            weird.forEach { searchFilter(l, it) }
            "${l.size} nomes estranhos sem travar"
        }
        run(10, "Organização", "biblioteca vazia e de 1 música") {
            DeviceSort.entries.forEach { emptyList<LocalSong>().sortedForDevice(it); listOf(song("só")).sortedForDevice(it) }
            DeviceTab.entries.forEach { emptyList<LocalSong>().groupedBy(it) }
            "ok"
        }
        run(10, "Estilos", "títulos gigantes e lixo no detector") {
            val big = "asmr ".repeat(20_000)
            val (_, ms) = timed {
                LeveStyle.detect(big); LeveStyle.detect(""); LeveStyle.detect("\u0000\u0001"); LeveStyle.detect("ep.ep.ep.")
                LeveStyle.containsTopic(big, "asmr"); LeveStyle.containsTopic("x", "   ")
            }
            budget("tempo", ms, 200.0, "ms")
        }
    }
}
