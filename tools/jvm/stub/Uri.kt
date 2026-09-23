package android.net

/** Stub for the JVM harness. Clips only ever hold one and compare it. */
class Uri private constructor(private val raw: String) {
    override fun toString() = raw
    override fun equals(other: Any?) = other is Uri && other.raw == raw
    override fun hashCode() = raw.hashCode()
    companion object { fun parse(s: String) = Uri(s) }
}
