package no.carlink

import java.util.concurrent.TimeUnit

/** Kommandoer med root på bilskjermen («su -c sh» og kommandoene på standard inn). */
object Root {
    fun run(script: String, timeoutS: Long = 8): String? = runCatching {
        val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroy(); return@runCatching null }
        p.inputStream.bufferedReader().readText()
    }.getOrNull()

    fun available(): Boolean = run("id")?.contains("uid=0") == true

    /** Slår på CarLinks tilgjengelighetstjeneste uten å røre andre som allerede er på. */
    fun enableAccessibility(component: String): Boolean {
        val d = "$"
        val out = run("""
            cur=${d}(settings get secure enabled_accessibility_services)
            case "${d}cur" in
              *$component*) ;;
              null|"") settings put secure enabled_accessibility_services $component ;;
              *) settings put secure enabled_accessibility_services "${d}cur:$component" ;;
            esac
            settings put secure accessibility_enabled 1
            settings get secure enabled_accessibility_services
        """.trimIndent())
        return out?.contains(component) == true
    }
}
