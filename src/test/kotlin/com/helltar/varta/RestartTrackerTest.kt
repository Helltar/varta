package com.helltar.varta

import com.helltar.varta.docker.Service
import com.helltar.varta.docker.ServiceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RestartTrackerTest {

    private val time = TestTimeSource()
    private val tracker = RestartTracker(patience = 5.minutes, timeSource = time)

    // one reading of a single service, after letting some time pass
    private fun read(
        restartCount: Int?,
        state: ServiceState = ServiceState.RESTARTING,
        after: Duration = Duration.ZERO
    ): Service {
        time += after
        return tracker.apply(listOf(service(state, restartCount))).single()
    }

    @Test
    fun `a count that was already high when first seen is not a loop`() {
        assertEquals(ServiceState.HEALTHY, read(57, ServiceState.HEALTHY).state)
        assertEquals(ServiceState.HEALTHY, read(57, ServiceState.HEALTHY, after = 10.minutes).state)
    }

    @Test
    fun `an ordinary restart is not a loop however long ago it was`() {
        read(0, ServiceState.HEALTHY)

        assertEquals(ServiceState.RESTARTING, read(1, after = 15.seconds).state)
        assertEquals(ServiceState.HEALTHY, read(1, ServiceState.HEALTHY, after = 4.minutes).state)
        assertEquals(ServiceState.HEALTHY, read(1, ServiceState.HEALTHY, after = 10.minutes).state)
    }

    @Test
    fun `a burst of restarts that stops is not turned into a loop by waiting it out`() {
        read(0)
        read(5, after = 15.seconds)
        read(9, after = 15.seconds)

        // still inside the quiet period that would end the episode, and past the patience: the
        // restarts themselves only spanned seconds
        assertEquals(ServiceState.HEALTHY, read(9, ServiceState.HEALTHY, after = 4.minutes + 50.seconds).state)
        assertEquals(ServiceState.HEALTHY, read(9, ServiceState.HEALTHY, after = 5.minutes).state)
    }

    @Test
    fun `restarts that keep coming past the patience are a crash loop`() {
        read(0)
        read(3, after = 15.seconds)

        assertEquals(ServiceState.RESTARTING, read(8, after = 4.minutes).state)

        val looping = read(10, after = 1.minutes)

        assertEquals(ServiceState.CRASH_LOOPING, looping.state)
        assertEquals("restarted 10 times in 5m", looping.status)
    }

    @Test
    fun `a loop stays one state whatever the container is passing through underneath`() {
        loop()

        assertEquals(ServiceState.CRASH_LOOPING, read(11, ServiceState.STARTING, after = 15.seconds).state)
        assertEquals(ServiceState.CRASH_LOOPING, read(11, ServiceState.UNMEASURED, after = 15.seconds).state)
        assertEquals(ServiceState.CRASH_LOOPING, read(12, ServiceState.RESTARTING, after = 15.seconds).state)
    }

    @Test
    fun `a loop is over once the service has stayed up for the patience`() {
        loop()

        assertEquals(ServiceState.CRASH_LOOPING, read(10, ServiceState.HEALTHY, after = 4.minutes).state)
        assertEquals(ServiceState.HEALTHY, read(10, ServiceState.HEALTHY, after = 1.minutes).state)

        // and a single restart later on starts from nothing, not from where the loop left off
        assertEquals(ServiceState.RESTARTING, read(11, after = 1.minutes).state)
    }

    @Test
    fun `a loop is not over while the daemon is only backing off before the next restart`() {
        loop()

        assertEquals(ServiceState.CRASH_LOOPING, read(10, ServiceState.RESTARTING, after = 6.minutes).state)
        assertEquals(ServiceState.CRASH_LOOPING, read(11, ServiceState.UNMEASURED, after = 15.seconds).state)
    }

    @Test
    fun `a loop that ended in a stopped container is reported as stopped`() {
        loop()

        assertEquals(ServiceState.STOPPED, read(null, ServiceState.STOPPED, after = 15.seconds).state)
    }

    @Test
    fun `a count that went down is a new baseline, not a restart`() {
        read(40, ServiceState.HEALTHY)
        read(0, ServiceState.HEALTHY, after = 15.seconds)

        assertEquals(ServiceState.HEALTHY, read(0, ServiceState.HEALTHY, after = 10.minutes).state)
        assertEquals(ServiceState.RESTARTING, read(1, after = 15.seconds).state)
    }

    @Test
    fun `a service that disappeared is forgotten`() {
        loop()
        tracker.apply(emptyList())

        assertEquals(ServiceState.RESTARTING, read(13).state)
    }

    @Test
    fun `services are followed apart`() {
        loop()

        val services =
            tracker.apply(listOf(service(ServiceState.RESTARTING, 10), service(ServiceState.HEALTHY, 0, name = "other")))

        assertEquals(listOf(ServiceState.CRASH_LOOPING, ServiceState.HEALTHY), services.map { it.state })
    }

    private fun loop() {
        read(0)
        read(3, after = 15.seconds)
        read(10, after = 5.minutes)
    }

    private fun service(state: ServiceState, restartCount: Int?, name: String = "vusan") =
        Service("netcup", name, state, "Restarting (1) 5 seconds ago", restartCount = restartCount)
}
