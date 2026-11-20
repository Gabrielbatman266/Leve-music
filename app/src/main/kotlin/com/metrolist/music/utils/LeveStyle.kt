/**
 * Leve Music — detector de estilo (local, sem rede): olha título + artista e diz
 * se é ASMR, meditação, dormir, podcast, games ou um gênero de música.
 * Usado para montar mixes coerentes (mix de meditação só tem meditação).
 */

package com.metrolist.music.utils

enum class LeveStyle(
    val label: String,
    val isMusic: Boolean,
    /** Busca usada para completar o mix quando o rádio não tem itens suficientes desse estilo. */
    val query: String,
    val keywords: List<String>,
) {
    // Não-música primeiro (prioridade na detecção)
    ASMR("ASMR", false, "asmr", listOf("asmr", "tingle", "tingles", "whispering", "sussurr", "mouth sounds", "tapping", "scratching", "role play", "roleplay", "soft spoken")),
    MEDITACAO("Meditação", false, "meditação guiada", listOf("medita", "mindfulness", "432hz", "528hz", "binaural", "solfeggio", "chakra", "yoga", "mantra", "frequência", "frequency", "oração guiada", "respiração", "breathwork", "breathing exercise", "guided breathing", "wim hof", "pranayama", "guided", "guiada", "guiado", "hypnosis", "hipnose", "afirmações", "affirmations", "relaxamento", "body scan")),
    DORMIR("Dormir e relaxar", false, "som para dormir", listOf("sleep", "dormir", "=sono", "pra dormir", "para dormir", "rain sounds", "som de chuva", "barulho de chuva", "white noise", "ruído branco", "brown noise", "ruído marrom", "ambience", "ambiente", "fan noise", "ventilador", "ocean waves", "ondas do mar")),
    PODCAST("Podcasts", false, "podcast", listOf("palestra", "motivação", "motivacional", "motivational", "=speech", "sermão", "pregação", "=aula", "=lesson", "audiobook", "audiolivro", "podcast", "podcats", "entrevista", "=cortes", "episódio", "episode", "ep.", "flow podcast", "podpah", "inteligência ltda")),
    GAMES("Games", false, "gameplay", listOf("gameplay", "minecraft", "free fire", "=gta", "fortnite", "roblox", "speedrun", "jogando", "detonado", "walkthrough", "let's play")),

    // Gêneros de música
    LOFI("Lo-fi", true, "lofi hip hop", listOf("lofi", "lo-fi", "chillhop", "study beats", "beats to")),
    FUNK("Funk", true, "funk", listOf("funk", "mtg", "brega", "bregadeira", "beat de", "montagem")),
    RAP("Rap e Trap", true, "rap", listOf("=rap", "=raps", "=trap", "=drill", "freestyle", "boom bap", "cypher")),
    GOSPEL("Gospel", true, "louvor gospel", listOf("gospel", "louvor", "worship", "=hino", "=hinos", "harpa cristã", "adoração", "aleluia")),
    SERTANEJO("Sertanejo", true, "sertanejo", listOf("sertanejo", "modão", "moda de viola", "agronejo")),
    PAGODE("Pagode e samba", true, "pagode", listOf("pagode", "samba", "pagodinho")),
    FORRO("Forró e piseiro", true, "piseiro", listOf("forró", "forro", "piseiro", "arrocha", "vaquejada", "xote")),
    ELETRONICA("Eletrônica", true, "eletrônica", listOf("remix", "=house", "techno", "=edm", "phonk", "slowed", "sped up", "eletrônica", "eletronica", "=trance", "dubstep", "daft punk", "alok", "avicii", "david guetta")),
    ROCK("Rock", true, "rock", listOf("rock", "metal", "punk", "grunge", "hardcore", "metalcore")),
    KPOP("K-pop", true, "kpop", listOf("kpop", "k-pop", "=bts", "blackpink", "stray kids", "newjeans", "aespa", "le sserafim")),
    CLASSICA("Clássica", true, "música clássica", listOf("mozart", "beethoven", "=bach", "chopin", "vivaldi", "sinfonia", "symphony", "concerto", "piano sonata")),
    MUSICA("Música", true, "", emptyList()),

    /** Vídeo longo que não é música e não caiu em nenhum estilo: mix de vídeos parecidos. */
    VIDEOS("Vídeos parecidos", false, "", emptyList()),
    ;

    companion object {
        private val separators = Regex("[^\\p{L}\\p{N}']+")

        /** Nomes de bandas/músicas que enganam o detector ("Sleep Token" é rock, não som pra dormir). */
        private val falseFriends = listOf("sleep token", "careless whisper", "uptown funk", "funk wav", "think twice")

        /**
         * Regras das palavras-chave:
         *  - "=palavra": só a palavra exata ("=rap" não pega "rapaz", "=sono" não pega "sonora")
         *  - com espaço ou ponto: a frase, começando numa palavra ("ep." não pega "deep.")
         *  - o resto: palavra que começa com a chave ("medita" pega "meditação")
         */
        private fun matches(t: String, words: List<String>, keyword: String): Boolean =
            when {
                keyword.startsWith("=") -> {
                    val k = keyword.substring(1)
                    words.any { it == k }
                }
                keyword.contains(' ') || keyword.contains('.') -> {
                    var i = t.indexOf(keyword)
                    var found = false
                    while (i >= 0 && !found) {
                        found = i == 0 || !t[i - 1].isLetterOrDigit()
                        i = t.indexOf(keyword, i + 1)
                    }
                    found
                }
                else -> words.any { it.startsWith(keyword) }
            }

        fun detect(text: String): LeveStyle {
            var t = text.lowercase()
            for (f in falseFriends) if (t.contains(f)) t = t.replace(f, " ")
            val words = t.split(separators).filter { it.isNotEmpty() }
            return entries.firstOrNull { s -> s.keywords.any { matches(t, words, it) } } ?: MUSICA
        }

        /** Esconder tema: casa palavra/frase inteira ("rap" escondido não some com "terapia"). */
        fun containsTopic(text: String, topic: String): Boolean {
            val t = topic.trim().lowercase().removePrefix("=")
            if (t.isEmpty()) return false
            val lower = text.lowercase()
            return matches(lower, lower.split(separators).filter { it.isNotEmpty() }, t)
        }

        /**
         * Categorias do topo da home, usando o MESMO detector dos mixes
         * (antes "Calma" e "Rainha" sumiam de Música e iam parar em "Dormir").
         */
        fun homeAccepts(category: String, text: String): Boolean {
            val s = detect(text)
            return when (category) {
                "ALL" -> true
                "MUSIC" -> s.isMusic
                "ASMR" -> s == ASMR
                "SLEEP" -> s == DORMIR || s == MEDITACAO || s == LOFI
                "PODCAST" -> s == PODCAST
                "GAMES" -> s == GAMES
                "FUNK" -> s == FUNK
                "RAP" -> s == RAP
                "GOSPEL" -> s == GOSPEL
                "BRASIL" -> s == SERTANEJO || s == PAGODE || s == FORRO
                "ROCK" -> s == ROCK
                "ELETRONICA" -> s == ELETRONICA
                else -> true
            }
        }

        /** Com a duração: vídeo de 10+ min sem estilo reconhecido não é tratado como música. */
        fun detect(text: String, durationSec: Int?): LeveStyle {
            val s = detect(text)
            return if (s == MUSICA && (durationSec ?: 0) >= 600) VIDEOS else s
        }

        fun detect(title: String, artists: List<String>): LeveStyle = detect(title + " " + artists.joinToString(" "))
    }
}
