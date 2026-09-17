package com.helltar.varta

import com.helltar.varta.docker.Service
import com.helltar.varta.docker.ServiceState
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// fewer than this is a restart, or a service waiting out a dependency, not a loop
private const val MIN_RESTARTS = 3

/**
 * Tells a crash loop from a restart, which no single reading can.
 *
 * A container that keeps dying under a restart policy never leaves the states varta treats as
 * passing through — it alternates between restarting and starting, or between restarting and
 * running — so on readings alone it would stay silent forever. What does give it away is the
 * daemon's restart count climbing, so that is what this follows.
 *
 * A service is looping once it has been restarted [MIN_RESTARTS] times or more and its first and
 * latest restart lie at least [patience] apart: the same allowance a booting stack gets to settle,
 * so a service that crashes a few times waiting for a dependency and then stays up is never called
 * broken. It stops looping once it has gone [patience] without a sign of the loop. Entering on one
 * condition and leaving on a stricter one is what keeps a slow loop from flapping between the two.
 *
 * A sign of the loop is the count going up or the container being seen waiting for its next
 * restart. The count alone is not enough to leave on: the daemon raises it when the container
 * dies, not when it comes back, and backs off for up to a minute in between — so a container caught
 * in the second it is up again would look like one that has stayed up since its last restart.
 */
internal class RestartTracker(
    private val patience: Duration,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic
) {

    private class Episode(var lastCount: Int) {
        var restarts = 0
        var startedAt: ComparableTimeMark? = null
        var lastSignAt: ComparableTimeMark? = null

        fun end() {
            restarts = 0
            startedAt = null
            lastSignAt = null
        }
    }

    private val episodes = mutableMapOf<String, Episode>()

    /** Returns [services] with every looping one reported as [ServiceState.CRASH_LOOPING]. */
    fun apply(services: List<Service>): List<Service> {
        episodes.keys.retainAll(services.mapTo(mutableSetOf()) { it.key })

        return services.map { service ->
            val looping = observe(service)

            // a loop that ended in a stopped container is better described as what it is now
            if (looping == null || service.state == ServiceState.STOPPED) service
            else service.copy(state = ServiceState.CRASH_LOOPING, status = looping)
        }
    }

    // what to say about the loop this service is in, or null when it is not in one
    private fun observe(service: Service): String? {
        val count = service.restartCount

        // the first sighting only sets the baseline: a count that was already high says nothing
        // about what is happening now
        val episode = episodes[service.key] ?: run {
            if (count != null) episodes[service.key] = Episode(count)
            return null
        }

        if (count != null) {
            // a count that went down belongs to a recreated or manually started container, which
            // is a new baseline and not a restart
            val restarts = (count - episode.lastCount).coerceAtLeast(0)
            episode.lastCount = count

            if (restarts > 0) {
                if (episode.startedAt == null) episode.startedAt = timeSource.markNow()
                episode.restarts += restarts
            }

            // waiting for the next restart only counts inside an episode: on its own it is what an
            // ordinary restart looks like too
            val sign = restarts > 0 || (episode.startedAt != null && service.state == ServiceState.RESTARTING)
            if (sign) episode.lastSignAt = timeSource.markNow()
        }

        val startedAt = episode.startedAt ?: return null
        val lastSignAt = episode.lastSignAt ?: return null

        if (lastSignAt.elapsedNow() >= patience) {
            episode.end()
            return null
        }

        // measured between signs rather than up to now, so a burst that stopped long ago does not
        // turn into a loop merely by being waited out
        val lasted = lastSignAt - startedAt

        return "restarted ${episode.restarts} times in ${lasted.inWholeSeconds.seconds}"
            .takeIf { episode.restarts >= MIN_RESTARTS && lasted >= patience }
    }
}
