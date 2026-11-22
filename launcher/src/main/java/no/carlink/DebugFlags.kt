package no.carlink

/** Brytere som bare brukes av simuleringen for å finne feil (slå av enkeltdeler). */
object DebugFlags {
    @Volatile var noVisualizer = false
    @Volatile var noArt = false
}
