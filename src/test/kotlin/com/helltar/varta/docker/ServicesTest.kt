package com.helltar.varta.docker

import kotlin.test.Test
import kotlin.test.assertEquals

class ServicesTest {

    @Test
    fun `a running container with a passing healthcheck is healthy`() {
        assertEquals(ServiceState.HEALTHY, summary(state = "running", health = "healthy").toService().state)
    }

    @Test
    fun `a running container without a healthcheck is unmeasured, not healthy`() {
        assertEquals(ServiceState.UNMEASURED, summary(state = "running", health = null).toService().state)
    }

    @Test
    fun `podman reports no healthcheck as none`() {
        assertEquals(ServiceState.UNMEASURED, summary(state = "running", health = "none").toService().state)
    }

    @Test
    fun `a failing healthcheck is unhealthy`() {
        assertEquals(ServiceState.UNHEALTHY, summary(state = "running", health = "unhealthy").toService().state)
    }

    @Test
    fun `a container still inside its start period is passing through, not settled`() {
        val state = summary(state = "running", health = "starting").toService().state

        assertEquals(ServiceState.STARTING, state)
        assertEquals(false, state.settled)
    }

    @Test
    fun `restarting outranks the health it last reported`() {
        val state = summary(state = "restarting", health = "healthy").toService().state

        assertEquals(ServiceState.RESTARTING, state)
        assertEquals(false, state.settled)
    }

    @Test
    fun `anything not running is stopped`() {
        assertEquals(ServiceState.STOPPED, summary(state = "exited", health = null).toService().state)
    }

    @Test
    fun `compose labels name the service and its project`() {
        val service =
            summary(state = "running", health = "healthy")
                .copy(
                    names = listOf("/aibot-container"),
                    labels = mapOf(
                        "com.docker.compose.project" to "netcup",
                        "com.docker.compose.service" to "aibot"
                    )
                )
                .toService()

        assertEquals("netcup", service.project)
        assertEquals("aibot", service.name)
        assertEquals("aibot-container", service.containerName)
    }

    @Test
    fun `a container outside compose falls back to its own name`() {
        val service = summary(state = "running", health = null).copy(names = listOf("/lonely")).toService()

        assertEquals(null, service.project)
        assertEquals("lonely", service.name)
    }

    @Test
    fun `a one-off container from compose run is recognised so it can be left out`() {
        val oneOff = summary(state = "exited", health = null)
            .copy(labels = mapOf("com.docker.compose.oneoff" to "True"))

        assertEquals(true, oneOff.isOneOff())
        assertEquals(false, summary(state = "running", health = "healthy").isOneOff())
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

    private fun summary(state: String, health: String?) =
        ContainerSummary(
            names = listOf("/whatever"),
            state = state,
            status = "Up 2 minutes",
            health = health?.let { ContainerHealth(it) }
        )
}
