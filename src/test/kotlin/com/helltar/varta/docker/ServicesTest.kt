package com.helltar.varta.docker

import kotlin.test.Test
import kotlin.test.assertEquals

class ServicesTest {

    @Test
    fun `a running container with a passing healthcheck is healthy`() {
        assertEquals(ServiceState.HEALTHY, summary("running").service("healthy").state)
    }

    @Test
    fun `a running container without a healthcheck is unmeasured, not healthy`() {
        assertEquals(ServiceState.UNMEASURED, summary("running").service(null).state)
    }

    @Test
    fun `podman reports no healthcheck as none`() {
        assertEquals(ServiceState.UNMEASURED, summary("running").service("none").state)
    }

    @Test
    fun `a failing healthcheck is unhealthy`() {
        assertEquals(ServiceState.UNHEALTHY, summary("running").service("unhealthy").state)
    }

    @Test
    fun `a container still inside its start period is passing through, not settled`() {
        val state = summary("running").service("starting").state

        assertEquals(ServiceState.STARTING, state)
        assertEquals(false, state.settled)
    }

    @Test
    fun `restarting outranks the health it last reported`() {
        val state = summary("restarting").service("healthy").state

        assertEquals(ServiceState.RESTARTING, state)
        assertEquals(false, state.settled)
    }

    @Test
    fun `a container that died between the list and the inspect is not called unhealthy`() {
        // the daemon stamps "unhealthy" on a container the moment it dies, so the list's "running"
        // next to that health would report a failing healthcheck that never ran
        val inspected = ContainerRuntimeState(status = "restarting", health = ContainerHealth("unhealthy"))

        assertEquals(ServiceState.RESTARTING, summary("running").toService(ContainerDetails(inspected)).state)
    }

    @Test
    fun `anything not running is stopped`() {
        assertEquals(ServiceState.STOPPED, summary("exited").service(null).state)
    }

    @Test
    fun `compose labels name the service and its project`() {
        val service =
            summary("running")
                .copy(
                    names = listOf("/aibot-container"),
                    labels = mapOf(
                        "com.docker.compose.project" to "netcup",
                        "com.docker.compose.service" to "aibot"
                    )
                )
                .service("healthy")

        assertEquals("netcup", service.project)
        assertEquals("aibot", service.name)
        assertEquals("aibot-container", service.containerName)
    }

    @Test
    fun `a container outside compose falls back to its own name`() {
        val service = summary("running").copy(names = listOf("/lonely")).service(null)

        assertEquals(null, service.project)
        assertEquals("lonely", service.name)
    }

    @Test
    fun `a one-off container from compose run is recognised so it can be left out`() {
        val oneOff = summary("exited")
            .copy(labels = mapOf("com.docker.compose.oneoff" to "True"))

        assertEquals(true, oneOff.isOneOff())
        assertEquals(false, summary("running").isOneOff())
    }

    @Test
    fun `compose replicas get distinct display names`() {
        val replicas =
            listOf(
                Service("stack", "bot", ServiceState.HEALTHY, "Up", "stack-bot-1"),
                Service("stack", "bot", ServiceState.HEALTHY, "Up", "stack-bot-2")
            ).withDistinctDisplayNames()

        assertEquals(listOf("bot (stack-bot-1)", "bot (stack-bot-2)"), replicas.map { it.name })
    }

    private fun summary(state: String) =
        ContainerSummary(
            id = "container-id",
            names = listOf("/whatever"),
            state = state,
            status = "Up 2 minutes"
        )

    private fun ContainerSummary.service(health: String?) =
        toService(ContainerDetails(ContainerRuntimeState(health = health?.let(::ContainerHealth))))
}
