package com.helltar.varta

import com.helltar.varta.docker.Service
import com.helltar.varta.docker.ServiceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WatcherTest {

    @Test
    fun `an unchanged stack produces nothing to say`() {
        val services = listOf(service("aibot"), service("vusan"))

        assertTrue(changesSince(observed(services), services).isEmpty())
    }

    @Test
    fun `falling from healthy to unhealthy is reported with where it came from`() {
        val before = listOf(service("aibot"))
        val after = listOf(service("aibot", ServiceState.UNHEALTHY))

        val changes = changesSince(observed(before), after)

        assertEquals(1, changes.size)
        assertEquals(ServiceState.HEALTHY, changes.single().from)
        assertEquals(ServiceState.UNHEALTHY, changes.single().service.state)
    }

    @Test
    fun `recovery is reported too`() {
        val before = listOf(service("aibot", ServiceState.UNHEALTHY))
        val after = listOf(service("aibot"))

        assertEquals(ServiceState.UNHEALTHY, changesSince(observed(before), after).single().from)
    }

    @Test
    fun `states the container is only passing through are not announced`() {
        val before = listOf(service("aibot"))

        // this is what an ordinary restart looks like from outside; announcing each step would turn one
        // event into three messages
        assertTrue(changesSince(observed(before), listOf(service("aibot", ServiceState.RESTARTING))).isEmpty())
        assertTrue(changesSince(observed(before), listOf(service("aibot", ServiceState.STARTING))).isEmpty())
    }

    @Test
    fun `a restart that ends where it began stays quiet`() {
        val observed = observed(listOf(service("aibot")))

        assertTrue(changesSince(observed, listOf(service("aibot", ServiceState.RESTARTING))).isEmpty())
        assertTrue(changesSince(observed, listOf(service("aibot", ServiceState.STARTING))).isEmpty())
        assertTrue(changesSince(observed, listOf(service("aibot"))).isEmpty())
    }

    @Test
    fun `a crash loop is announced once and stays quiet while it goes on`() {
        val before = listOf(service("vusan"))
        val looping = listOf(service("vusan", ServiceState.CRASH_LOOPING))
        val observed = observed(before).toMutableMap()

        val change = observeChanges(observed, looping).single()

        assertEquals(ServiceState.HEALTHY, change.from)
        assertEquals(ServiceState.CRASH_LOOPING, change.service.state)
        assertTrue(observeChanges(observed, looping).isEmpty())
    }

    @Test
    fun `a service appearing healthy for the first time is not news`() {
        assertTrue(changesSince(emptyMap(), listOf(service("newcomer"))).isEmpty())
    }

    @Test
    fun `a service appearing already broken is news`() {
        val changes = changesSince(emptyMap(), listOf(service("newcomer", ServiceState.STOPPED)))

        assertEquals(null, changes.single().from)
        assertEquals(ServiceState.STOPPED, changes.single().service.state)
    }

    @Test
    fun `a new healthy service is remembered before its next change`() {
        val observed = mutableMapOf<String, ServiceState>()
        val healthy = service("newcomer")

        assertTrue(observeChanges(observed, listOf(healthy)).isEmpty())
        assertEquals(ServiceState.HEALTHY, observed.getValue(healthy.key))

        val changes = observeChanges(observed, listOf(healthy.copy(state = ServiceState.UNMEASURED)))

        assertEquals(ServiceState.HEALTHY, changes.single().from)
        assertEquals(ServiceState.UNMEASURED, changes.single().service.state)
    }

    @Test
    fun `containers that disappear are removed from observed state`() {
        val present = service("present")
        val removed = service("removed")
        val observed = observed(listOf(present, removed)).toMutableMap()

        observeChanges(observed, listOf(present))

        assertEquals(mapOf(present.key to ServiceState.HEALTHY), observed)
    }

    @Test
    fun `services in different projects with the same name are tracked apart`() {
        val before = listOf(service("bot", project = "one"), service("bot", project = "two"))
        val after = listOf(service("bot", project = "one"), service("bot", ServiceState.UNHEALTHY, project = "two"))

        val changed = changesSince(observed(before), after).single()

        assertEquals("two", changed.service.project)
    }

    @Test
    fun `replicas of one compose service are tracked apart by container name`() {
        val first = service("bot", containerName = "stack-bot-1")
        val second = service("bot", containerName = "stack-bot-2")
        val observed = observed(listOf(first, second))

        val changed = changesSince(observed, listOf(first, second.copy(state = ServiceState.UNHEALTHY))).single()

        assertEquals("stack-bot-2", changed.service.containerName)
    }

    @Test
    fun `an undelivered report is retried with exponential backoff`() {
        var now = 0L
        var succeeds = false
        val attempts = mutableListOf<String>()
        val delivery =
            ReportDeliveryQueue(
                send = { report ->
                    attempts += report
                    succeeds
                },
                retryDelay = 1.seconds,
                nowMillis = { now }
            )

        delivery.enqueue(listOf("first", "second"))
        delivery.flushIfDue()

        assertEquals(2, delivery.pendingCount)
        assertEquals(listOf("first"), attempts)

        now = 1_000
        delivery.flushIfDue()
        now = 2_999
        succeeds = true
        delivery.flushIfDue()

        assertEquals(2, attempts.size)
        assertEquals(2, delivery.pendingCount)

        now = 3_000
        delivery.flushIfDue()

        assertEquals(listOf("first", "first", "first", "second"), attempts)
        assertEquals(0, delivery.pendingCount)
    }

    @Test
    fun `every report held back by an outage reaches the log once`() {
        var now = 0L
        var succeeds = false
        val logged = mutableListOf<String>()
        val delivery =
            ReportDeliveryQueue(
                send = { succeeds },
                retryDelay = 1.seconds,
                nowMillis = { now },
                logUndelivered = { logged += it }
            )

        delivery.enqueue(listOf("first", "second"))
        delivery.flushIfDue()

        // the one behind the head too: it would otherwise exist only in memory
        assertEquals(listOf("first", "second"), logged)

        delivery.enqueue(listOf("third"))
        now = 1_000
        delivery.flushIfDue()

        assertEquals(listOf("first", "second", "third"), logged)

        succeeds = true
        now = 3_000
        delivery.flushIfDue()
        delivery.enqueue(listOf("fourth"))
        delivery.flushIfDue()

        assertEquals(0, delivery.pendingCount)
        assertEquals(listOf("first", "second", "third"), logged)
    }

    @Test
    fun `the delivery queue stays bounded during a long outage`() {
        val delivery = ReportDeliveryQueue(send = { false }, retryDelay = 1.seconds)

        delivery.enqueue((1..101).map { "report-$it" })

        assertEquals(100, delivery.pendingCount)
    }

    private fun observed(services: List<Service>) = services.associate { it.key to it.state }

    private fun service(
        name: String,
        state: ServiceState = ServiceState.HEALTHY,
        project: String? = "netcup",
        containerName: String = name
    ) = Service(project, name, state, "Up 2 hours", containerName)
}
