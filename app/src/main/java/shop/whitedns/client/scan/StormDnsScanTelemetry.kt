package shop.whitedns.client.scan

sealed class StormDnsScanTelemetry {
    data class Testing(val resolver: String, val transport: String) : StormDnsScanTelemetry()
    data class Path(val resolver: String, val transport: String, val usable: Boolean) : StormDnsScanTelemetry()
    data class Valid(val resolver: String) : StormDnsScanTelemetry()
    data class Rejected(val resolver: String) : StormDnsScanTelemetry()
    data class Complete(
        val total: Int,
        val valid: Int,
        val rejected: Int,
    ) : StormDnsScanTelemetry()
}

fun parseStormDnsScanLine(line: String): StormDnsScanTelemetry? {
    val cleanLine = line
        .replace(AnsiEscapeRegex, "")
        .trim()
    val markerIndex = cleanLine.indexOf(ScanMarker)
    if (markerIndex < 0) {
        return null
    }

    val fields = ScanFieldRegex.findAll(cleanLine.substring(markerIndex + ScanMarker.length))
        .associate { match -> match.groupValues[1] to match.groupValues[2] }
    return when (fields["event"]) {
        "testing" -> fields["resolver"]?.takeIf(String::isNotBlank)?.let {
            StormDnsScanTelemetry.Testing(it, fields["transport"].orEmpty())
        }
        "path" -> fields["resolver"]?.takeIf(String::isNotBlank)?.let {
            StormDnsScanTelemetry.Path(it, fields["transport"].orEmpty(), fields["usable"] == "true" || fields["transfer_verified"] == "true")
        }
        "valid" -> fields["resolver"]
            ?.takeIf(String::isNotBlank)
            ?.let(StormDnsScanTelemetry::Valid)
        "rejected" -> fields["resolver"]
            ?.takeIf(String::isNotBlank)
            ?.let(StormDnsScanTelemetry::Rejected)
        "complete" -> StormDnsScanTelemetry.Complete(
            total = fields["total"].toIntOrZero(),
            valid = fields["valid"].toIntOrZero(),
            rejected = fields["rejected"].toIntOrZero(),
        )
        else -> null
    }
}

class StormDnsScanPaths {
    private val pending = mutableListOf<String>()
    private val working = linkedSetOf<String>()
    val testingPaths: List<String> get() = pending.toList()
    val workingPaths: List<String> get() = working.toList()
    fun update(event: StormDnsScanTelemetry) {
        fun label(resolver: String, transport: String) =
            resolver + if (transport.isBlank()) "" else " (${transport.uppercase()})"
        when (event) {
            is StormDnsScanTelemetry.Testing -> pending += label(event.resolver, event.transport)
            is StormDnsScanTelemetry.Path -> {
                val path = label(event.resolver, event.transport)
                pending.remove(path)
                if (event.usable) working += path
            }
            is StormDnsScanTelemetry.Complete -> pending.clear()
            else -> Unit
        }
    }
    fun clearTesting() = pending.clear()
}

private fun String?.toIntOrZero(): Int {
    return this?.toIntOrNull() ?: 0
}

private const val ScanMarker = "WD_SCAN"
private val ScanFieldRegex = Regex("""(\w+)=([^\s]+)""")
private val AnsiEscapeRegex = Regex("${27.toChar()}\\[[;?0-9]*[ -/]*[@-~]")
