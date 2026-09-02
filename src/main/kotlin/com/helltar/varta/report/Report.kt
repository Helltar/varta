package com.helltar.varta.report

import com.helltar.varta.docker.Service
import com.helltar.varta.docker.ServiceState

// telegram rejects anything longer; the roll call is dropped before any problem lines are omitted
private const val MAX_MESSAGE_CHARS = 4000
private const val MAX_TEXT_FIELD_CHARS = 300

private const val NO_PROJECT = "standalone"

internal data class StateChange(
    val service: Service,
    val from: ServiceState?
)

/**
 * The message sent once the stack has settled after a boot or a deploy — the roll call that replaces
 * reading logs and messaging each bot by hand.
 */
internal fun bootReport(services: List<Service>, settled: Boolean, host: String? = null): String {
    if (services.isEmpty()) {
        return hostLine(host) + "⚠️ <b>No containers to watch</b>\nCheck <code>PROJECTS</code> and <code>IGNORE</code>."
    }

    val wrong = services.filter { it.state.wrong }
    val healthy = services.count { it.state == ServiceState.HEALTHY }
    val unmeasured = services.count { it.state == ServiceState.UNMEASURED }

    val header =
        hostLine(host) + when {
            !settled -> "⏳ <b>Still starting</b> — reporting anyway, ${services.size} watched"
            wrong.isNotEmpty() -> {
                val verb = if (wrong.size == 1) "needs" else "need"
                "❌ <b>${wrong.size} of ${services.size} $verb attention</b>"
            }
            unmeasured == 0 -> "✅ <b>All ${services.size} healthy</b>"
            healthy == 0 -> "⚪ <b>All ${services.size} unverified</b>"
            else -> "✅ <b>$healthy healthy</b> · ⚪ <b>$unmeasured unverified</b>"
        }

    val footer =
        when (unmeasured) {
            0 -> ""
            1 -> "\n\n<i>1 service has no healthcheck — it is unverified, not confirmed.</i>"
            else -> "\n\n<i>$unmeasured services have no healthcheck — they are unverified, not confirmed.</i>"
        }

    val full = header + "\n\n" + rollCall(services) + footer

    // a stack big enough to overflow is exactly when the failures matter more than the roll call
    return if (full.length <= MAX_MESSAGE_CHARS) full else compactBootReport(header, wrong, footer)
}

/** The messages sent when services move between settled states while varta is watching. */
internal fun changeReports(changes: List<StateChange>, host: String? = null): List<String> {
    val entries = changes.map { (service, from) ->
        "${service.state.glyph} <b>${service.name.forMessage().escapeHtml()}</b> — ${verb(from, service.state)}" +
                "\n<i>${service.status.forMessage().escapeHtml()}</i>"
    }

    if (entries.isEmpty()) return emptyList()

    // the label goes on every message, not only the first: they arrive hours apart and out of any
    // context. it is charged against the budget so a labelled message cannot overflow.
    val prefix = hostLine(host)
    val budget = MAX_MESSAGE_CHARS - prefix.length

    val reports = mutableListOf<String>()
    var current = entries.first()

    for (entry in entries.drop(1)) {
        val combined = "$current\n$entry"

        if (combined.length <= budget) current = combined
        else {
            reports += current
            current = entry
        }
    }

    reports += current
    return reports.map { prefix + it }
}

// which machine the report is about. two hosts running the same stack produce messages that are
// otherwise identical, down to the compose project name.
private fun hostLine(host: String?) =
    host?.takeIf { it.isNotBlank() }
        ?.let { "\uD83D\uDDA5 <b>${it.forMessage().escapeHtml()}</b>\n" }
        .orEmpty()

private fun verb(from: ServiceState?, to: ServiceState) =
    when (to) {
        ServiceState.HEALTHY if from?.wrong == true -> "healthy again"
        ServiceState.HEALTHY -> "healthy"
        ServiceState.UNHEALTHY -> "unhealthy"
        ServiceState.STOPPED -> "stopped"
        ServiceState.UNMEASURED -> "running, no healthcheck"
        else -> to.name.lowercase()
    }

// confirmed first, then the unverified, then whatever needs attention — see ServiceState, whose
// declaration order this follows
private val reportOrder = compareBy<Service>({ it.state.ordinal }, { it.name })

private fun rollCall(services: List<Service>) =
    services
        .groupBy { it.project ?: NO_PROJECT }
        .map { (project, members) ->
            val heading = "<b>${project.forMessage().escapeHtml()}</b>\n\n"
            heading + members.sortedWith(reportOrder).joinToString("\n") { line(it) }
        }
        .joinToString("\n\n")

private fun line(service: Service): String {
    val detail =
        when (service.state) {
            ServiceState.HEALTHY -> ""
            ServiceState.UNMEASURED -> " — no healthcheck"
            else -> " — ${service.status.forMessage().escapeHtml()}"
        }

    return "${service.state.glyph} ${service.name.forMessage().escapeHtml()}$detail"
}

private fun compactBootReport(header: String, wrong: List<Service>, footer: String): String {
    if (wrong.isEmpty()) return header + footer

    val lines = wrong.map { line(it) }
    val body = StringBuilder()
    var shown = 0

    for (line in lines) {
        val nextBodyLength = body.length + if (body.isEmpty()) line.length else line.length + 1
        val omission = omittedProblems(lines.size - shown - 1)
        val omissionSeparatorLength = if (omission.isEmpty()) 0 else 2
        val candidateLength =
            header.length + 2 + nextBodyLength + omissionSeparatorLength + omission.length + footer.length

        if (candidateLength > MAX_MESSAGE_CHARS) break

        if (body.isNotEmpty()) body.append('\n')
        body.append(line)
        shown++
    }

    val omission = omittedProblems(lines.size - shown)

    return buildString {
        append(header)
        append("\n\n")
        append(body)

        if (omission.isNotEmpty()) {
            if (body.isNotEmpty()) append("\n\n")
            append(omission)
        }

        append(footer)
    }
}

private fun omittedProblems(count: Int) =
    when (count) {
        0 -> ""
        1 -> "<i>1 more problem not shown.</i>"
        else -> "<i>$count more problems not shown.</i>"
    }

private fun String.forMessage() =
    if (length <= MAX_TEXT_FIELD_CHARS) this else take(MAX_TEXT_FIELD_CHARS - 1) + "…"

private fun String.escapeHtml() =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
