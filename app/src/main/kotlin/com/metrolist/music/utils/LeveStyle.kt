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
    ASMR("ASMR", false, "asmr", listOf("asmr", "tingle", "tingles", "whisper", "sussurr", "mouth sounds", "tapping", "scratching", "role play", "roleplay")),
    MEDITACAO("Meditação", false, "meditação guiada", listOf("medita", "mindfulness", "432hz", "528hz", "binaural", "solfeggio", "chakra", "yoga", "mantra", "frequência", "frequency", "oração guiada", "respiração")),
    DORMIR("Dormir e relaxar", false, "som para dormir", listOf("sleep", "dormir", "sono", "rain sounds", "som de chuva", "barulho de chuva", "white noise", "ruído branco", "brown noise", "ruído marrom", "ambience", "ambiente", "fan noise", "ventilador", "ocean waves", "ondas do mar")),
    PODCAST("Podcasts", false, "podcast", listOf("podcast", "podcats", "entrevista", "cortes", "episódio", "episode", "ep.", "flow podcast", "podpah", "inteligência ltda")),
    GAMES("Games", false, "gameplay", listOf("gameplay", "minecraft", "free fire", "gta", "fortnite", "roblox", "speedrun", "jogando", "detonado", "walkthrough", "let's play")),

    // Gêneros de música
    LOFI("Lo-fi", true, "lofi hip hop", listOf("lofi", "lo-fi", "chillhop", "study beats", "beats to")),
    FUNK("Funk", true, "funk", listOf("funk", "mtg", "brega", "bregadeira", "beat de", "montagem")),
    RAP("Rap e Trap", true, "rap", listOf("rap", "trap", "drill", "freestyle", "boom bap", "cypher")),
    GOSPEL("Gospel", true, "louvor gospel", listOf("gospel", "louvor", "worship", "hino", "adoração", "aleluia")),
    SERTANEJO("Sertanejo", true, "sertanejo", listOf("sertanejo", "modão", "moda de viola", "agronejo")),
    PAGODE("Pagode e samba", true, "pagode", listOf("pagode", "samba", "pagodinho")),
    FORRO("Forró e piseiro", true, "piseiro", listOf("forró", "forro", "piseiro", "arrocha", "vaquejada", "xote")),
    ROCK("Rock", true, "rock", listOf("rock", "metal", "punk", "grunge", "hardcore", "metalcore")),
    ELETRONICA("Eletrônica", true, "eletrônica", listOf("remix", "house", "techno", "edm", "phonk", "slowed", "sped up", "eletrônica", "trance", "dubstep")),
    KPOP("K-pop", true, "kpop", listOf("kpop", "k-pop", "bts", "blackpink", "twice", "stray kids", "newjeans")),
    CLASSICA("Clássica", true, "música clássica", listOf("mozart", "beethoven", "bach", "chopin", "vivaldi", "sinfonia", "symphony", "concerto", "piano sonata")),
    MUSICA("Música", true, "", emptyList()),
    ;

    companion object {
        private val words = Regex("[^\\p{L}\\p{N}.']+")

        private fun matches(text: String, keyword: String): Boolean {
            if (keyword.contains(' ') || keyword.contains('.')) return text.contains(keyword)
            // palavra que começa com a chave (evita "rap" casar com "terapia")
            return text.split(words).any { it.startsWith(keyword) }
        }

        fun detect(text: String): LeveStyle {
            val t = text.lowercase()
            return entries.firstOrNull { s -> s.keywords.any { matches(t, it) } } ?: MUSICA
        }

        fun detect(title: String, artists: List<String>): LeveStyle = detect(title + " " + artists.joinToString(" "))
    }
}
