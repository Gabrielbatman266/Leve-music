package com.metrolist.music.leve

import java.io.File
import java.util.Locale

/** Placar dos testes de estresse do Leve Music: cada verificação vira uma linha em app/build/stress/music.tsv. */
object Placar {
    private val out: File by lazy {
        File(System.getProperty("leve.stress.out") ?: "build/stress", "music.tsv").also { it.parentFile?.mkdirs() }
    }

    @Synchronized
    fun check(level: Int, area: String, name: String, ok: Boolean, detail: String) {
        val clean = detail.replace('\t', ' ').replace('\n', ' ').take(400)
        val n = name.replace('\t', ' ').replace('\n', ' ')
        out.appendText("$level\t$area\t$n\t${if (ok) "PASS" else "FAIL"}\t$clean\n")
        println((if (ok) "  ✔ " else "  ✘ ") + "N$level [$area] $name — $clean")
    }

    /** Roda a verificação; exceção = falha (o resto continua). Devolver "FAIL:..." também é falha. */
    fun run(level: Int, area: String, name: String, block: () -> String?) {
        try {
            val d = block()
            val ok = d == null || !d.startsWith("FAIL:")
            check(level, area, name, ok, d?.removePrefix("FAIL:") ?: "ok")
        } catch (t: Throwable) {
            var r: Throwable = t
            while (r.cause != null && r.cause !== r) r = r.cause!!
            val at = r.stackTrace.firstOrNull { it.className.startsWith("com.metrolist") }
            check(level, area, name, false, "CRASH $r${at?.let { " em $it" } ?: ""}")
        }
    }

    fun fail(why: String) = "FAIL:$why"

    fun budget(what: String, value: Double, max: Double, unit: String): String {
        val d = String.format(Locale.ROOT, "%s %.1f %s (limite %.0f)", what, value, unit, max)
        return if (value <= max) d else fail(d)
    }

    inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val t = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t) / 1e6
    }

    fun usedMb(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(30) }
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }
}
