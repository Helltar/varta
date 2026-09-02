package com.helltar.varta

import com.helltar.varta.docker.Docker
import com.helltar.varta.docker.Service
import com.helltar.varta.docker.ServiceState
import com.helltar.varta.report.StateChange
import com.helltar.varta.report.bootReport
import com.helltar.varta.report.changeReports
import com.helltar.varta.telegram.Telegram
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}
private val MAX_REPORT_RETRY_DELAY = 5.minutes
private const val MAX_PENDING_REPORTS = 100

internal val Service.key get() = "${project.orEmpty()}/$containerName"

/**
 * Compares a fresh reading against the last settled state observed for each service.
 *
 * States a container is only passing through are ignored rather than announced, which is what keeps
 * an ordinary restart from arriving as three messages. A service seen for the first time is only
 * worth a message when it shows up already broken.
 */
internal fun changesSince(observed: Map<String, ServiceState>, services: List<Service>): List<StateChange> =
    services
        .filter { it.state.settled }
        .mapNotNull { service ->
            when (val previous = observed[service.key]) {
                service.state -> null
                null -> StateChange(service, null).takeIf { service.state.wrong }
                else -> StateChange(service, previous)
            }
        }

internal fun observeChanges(observed: MutableMap<String, ServiceState>, services: List<Service>): List<StateChange> {
    val changes = changesSince(observed, services)
    val current = services.mapTo(mutableSetOf()) { it.key }

    observed.keys.retainAll(current)
    services.filter { it.state.settled }.forEach { observed[it.key] = it.state }

    return changes
}

internal class ReportDeliveryQueue(
    private val send: (String) -> Boolean,
    retryDelay: Duration,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    private val initialRetryDelayMillis =
        minOf(retryDelay.inWholeMilliseconds, MAX_REPORT_RETRY_DELAY.inWholeMilliseconds).coerceAtLeast(1)

    private val pending = ArrayDeque<String>()
    private var retryDelayMillis = initialRetryDelayMillis
    private var nextAttemptMillis = 0L

    internal val pendingCount get() = pending.size

    fun enqueue(reports: Iterable<String>) {
        val wasEmpty = pending.isEmpty()

        reports.forEach { report ->
            if (pending.size == MAX_PENDING_REPORTS) {
                val dropped = pending.removeFirst()
                log.warn { "Report delivery queue is full, dropping its oldest report:\n$dropped" }
            }

            pending.addLast(report)
        }
        if (wasEmpty && pending.isNotEmpty()) nextAttemptMillis = nowMillis()
    }

    fun flushIfDue() {
        if (pending.isEmpty() || nowMillis() < nextAttemptMillis) return

        while (pending.isNotEmpty()) {
            if (!send(pending.first())) {
                nextAttemptMillis = nowMillis() + retryDelayMillis
                retryDelayMillis = minOf(retryDelayMillis * 2, MAX_REPORT_RETRY_DELAY.inWholeMilliseconds)
                return
            }

            pending.removeFirst()
        }

        retryDelayMillis = initialRetryDelayMillis
    }
}

internal class Watcher(
    private val docker: Docker,
    private val telegram: Telegram,
    private val settleTimeout: Duration,
    private val pollInterval: Duration,
    private val heartbeatFile: Path,
    private val host: String? = null
) {

    fun run() {
        val snapshot = awaitSettled()
        val delivery = ReportDeliveryQueue(::deliver, pollInterval)

        log.info { "Reporting on ${snapshot.services.size} services, settled=${snapshot.settled}" }
        delivery.enqueue(listOf(bootReport(snapshot.services, snapshot.settled, host)))
        delivery.flushIfDue()

        watch(
            initial = snapshot.services.filter { it.state.settled }.associate { it.key to it.state },
            delivery = delivery
        )
    }

    /**
     * Waits for the stack to stop moving, so the report describes what came up rather than what was
     * still on its way. Its own start is the boot event: nothing else has to detect the reboot.
     */
    private fun awaitSettled(): StackSnapshot {
        val deadline = TimeSource.Monotonic.markNow() + settleTimeout

        while (true) {
            val result = runCatching { docker.services() }
            val services = result.getOrNull()

            if (services != null) {
                markAlive()

                if (services.isNotEmpty() && services.all { it.state.settled }) {
                    return StackSnapshot(services, settled = true)
                }

                if (deadline.hasPassedNow()) return StackSnapshot(services, settled = false)
            } else {
                val failure = result.exceptionOrNull()!!

                log.warn(failure) { "Could not read the docker socket while waiting for the stack to settle" }
                if (deadline.hasPassedNow()) throw failure
            }

            Thread.sleep(pollInterval.inWholeMilliseconds)
        }
    }

    private fun watch(initial: Map<String, ServiceState>, delivery: ReportDeliveryQueue) {
        val observed = initial.toMutableMap()

        while (true) {
            Thread.sleep(pollInterval.inWholeMilliseconds)

            val services = runCatching { docker.services() }
                .onFailure { log.warn(it) { "Could not read the docker socket" } }
                .getOrNull()

            if (services != null) {
                markAlive()

                val changes = observeChanges(observed, services)
                if (changes.isNotEmpty()) {
                    log.info {
                        "State changed: " +
                                changes.joinToString { "${it.service.name} ${it.from ?: "NEW"}->${it.service.state}" }
                    }

                    delivery.enqueue(changeReports(changes, host))
                }
            }

            delivery.flushIfDue()
        }
    }

    // an undelivered report is the one piece of information nobody else holds, so it goes to the log
    // rather than being dropped because Telegram happened to be unreachable
    private fun deliver(text: String): Boolean {
        val delivered = telegram.send(text)

        if (!delivered) log.warn { "Report was not delivered, keeping it here instead:\n$text" }

        return delivered
    }

    // refreshed only after the socket actually answered, so the file going stale means varta stopped
    // watching — the same signal it reads from everything else
    private fun markAlive() {
        runCatching { Files.writeString(heartbeatFile, Instant.now().toString()) }
            .onFailure { log.warn(it) { "Could not write the heartbeat file=[$heartbeatFile]" } }
    }
}

private data class StackSnapshot(val services: List<Service>, val settled: Boolean)
