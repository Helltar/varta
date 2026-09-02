package com.helltar.varta.report

import com.helltar.varta.docker.Service
import com.helltar.varta.docker.ServiceState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {

    @Test
    fun `a settled stack with nothing wrong reports the count`() {
        val report = bootReport(listOf(service("aibot"), service("vusan")), settled = true)

        assertContains(report, "All 2 healthy")
        assertContains(report, "✅ aibot")
    }

    @Test
    fun `a broken service is counted and named with its docker status`() {
        val services = listOf(service("aibot"), service("dlpbot", ServiceState.UNHEALTHY, "Up 3 hours (unhealthy)"))
        val report = bootReport(services, settled = true)

        assertContains(report, "1 of 2 needs attention")
        assertContains(report, "❌ dlpbot — Up 3 hours (unhealthy)")
    }

    @Test
    fun `services without a healthcheck are called unverified rather than counted as fine`() {
        val report = bootReport(listOf(service("aibot"), service("nginx", ServiceState.UNMEASURED)), settled = true)

        assertContains(report, "✅ <b>1 healthy</b> · ⚪ <b>1 unverified</b>")
        assertContains(report, "⚪ nginx — no healthcheck")
        assertContains(report, "1 service has no healthcheck — it is unverified, not confirmed.")
    }

    @Test
    fun `a stack with no healthchecks does not get a healthy header`() {
        val services = listOf(service("nginx", ServiceState.UNMEASURED), service("dozzle", ServiceState.UNMEASURED))
        val report = bootReport(services, settled = true)

        assertContains(report, "All 2 unverified")
        assertContains(report, "2 services have no healthcheck — they are unverified, not confirmed.")
    }

    @Test
    fun `a plural attention count uses the plural verb`() {
        val services =
            listOf(
                service("aibot"),
                service("dlpbot", ServiceState.UNHEALTHY),
                service("dozzle", ServiceState.STOPPED)
            )

        assertContains(bootReport(services, settled = true), "2 of 3 need attention")
    }

    @Test
    fun `a stack that never settled says so instead of claiming everything is up`() {
        val report = bootReport(listOf(service("aibot"), service("vusan", ServiceState.STARTING)), settled = false)

        assertContains(report, "Still starting")
        assertFalse("All 2 healthy" in report)
    }

    @Test
    fun `the roll call puts the confirmed first, the unverified next, and problems last`() {
        val services =
            listOf(
                service("nginx", ServiceState.UNMEASURED),
                service("dlpbot", ServiceState.UNHEALTHY),
                service("vusan"),
                service("dozzle", ServiceState.STOPPED),
                service("aibot")
            )

        val names =
            bootReport(services, settled = true)
                .lines()
                // the header carries a glyph too, so bold marks it out as not being a roll call line
                .filter { line -> "<b>" !in line && ServiceState.entries.any { line.startsWith("${it.glyph} ") } }
                .map { it.substringAfter(' ').substringBefore(" —") }

        assertEquals(listOf("aibot", "vusan", "nginx", "dlpbot", "dozzle"), names)
    }

    @Test
    fun `the project name is followed by a blank line`() {
        assertContains(bootReport(listOf(service("aibot")), settled = true), "<b>netcup</b>\n\n✅ aibot")
    }

    @Test
    fun `the footer is separated from the roll call by a blank line`() {
        val report = bootReport(listOf(service("nginx", ServiceState.UNMEASURED)), settled = true)

        assertContains(report, "no healthcheck\n\n<i>1 service has no healthcheck")
    }

    @Test
    fun `services are grouped by compose project`() {
        val services = listOf(service("aibot", project = "netcup"), service("blog", project = "web"))
        val report = bootReport(services, settled = true)

        assertContains(report, "<b>netcup</b>")
        assertContains(report, "<b>web</b>")
    }

    @Test
    fun `an oversized roll call is dropped but the failures are kept`() {
        val many = (1..400).map { service("service-$it") } + service("dlpbot", ServiceState.UNHEALTHY)
        val report = bootReport(many, settled = true)

        assertTrue(report.length <= 4000, "message must fit in one telegram message, was ${report.length}")
        assertContains(report, "❌ dlpbot")
        assertFalse("service-200" in report)
    }

    @Test
    fun `too many failures are summarised within one telegram message`() {
        val failures = (1..400).map { service("service-$it", ServiceState.UNHEALTHY) }
        val report = bootReport(failures, settled = true)

        assertTrue(report.length <= 4000, "message must fit in one telegram message, was ${report.length}")
        assertContains(report, "more problems not shown")
    }

    @Test
    fun `an empty watch list is reported as a configuration problem`() {
        assertContains(bootReport(emptyList(), settled = true), "No containers to watch")
    }

    @Test
    fun `recovery is worded differently from a first pass`() {
        val recovered = StateChange(service("dlpbot"), from = ServiceState.UNHEALTHY)

        assertContains(changeReports(listOf(recovered)).single(), "healthy again")
    }

    @Test
    fun `markup in a service name cannot break the message`() {
        val report = bootReport(listOf(service("<b>evil")), settled = true)

        assertContains(report, "&lt;b&gt;evil")
    }

    @Test
    fun `a change carries the docker status underneath it`() {
        val change =
            StateChange(
                service("dlpbot", ServiceState.UNHEALTHY, "Up 3 hours (unhealthy)"),
                ServiceState.HEALTHY
            )

        assertEquals(
            "❌ <b>dlpbot</b> — unhealthy\n<i>Up 3 hours (unhealthy)</i>",
            changeReports(listOf(change)).single()
        )
    }

    @Test
    fun `many simultaneous changes are split into telegram-sized messages`() {
        val changes =
            (1..400).map { number ->
                StateChange(service("service-$number", ServiceState.UNHEALTHY), ServiceState.HEALTHY)
            }

        val reports = changeReports(changes)

        assertTrue(reports.size > 1)
        assertTrue(reports.all { it.length <= 4000 })
        assertContains(reports.joinToString(), "service-400")
    }

    @Test
    fun `one hostile docker entry cannot exceed the telegram limit after escaping`() {
        val hostile = "&".repeat(10_000)
        val service = service(hostile, ServiceState.UNHEALTHY, status = hostile)

        assertTrue(bootReport(listOf(service), settled = true).length <= 4000)
        assertTrue(changeReports(listOf(StateChange(service, ServiceState.HEALTHY))).single().length <= 4000)
    }

    @Test
    fun `a host label names the machine a report came from`() {
        val report = bootReport(listOf(service("aibot")), settled = true, host = "maia")

        assertContains(report, "<b>maia</b>")
        assertContains(report, "All 1 healthy")
    }

    @Test
    fun `a blank line separates the host from the report it heads`() {
        val boot = bootReport(listOf(service("aibot")), settled = true, host = "maia")
        val change = changeReports(listOf(StateChange(service("aibot", ServiceState.STOPPED), null)), host = "maia")

        assertContains(boot, "<b>maia</b>\n\n✅")
        assertContains(change.single(), "<b>maia</b>\n\n")
    }

    @Test
    fun `without a host label the report is unchanged`() {
        val services = listOf(service("aibot"), service("vusan"))

        assertEquals(bootReport(services, settled = true), bootReport(services, settled = true, host = null))
    }

    @Test
    fun `a blank host label is treated as no label`() {
        val services = listOf(service("aibot"))

        assertEquals(bootReport(services, settled = true), bootReport(services, settled = true, host = "   "))
    }

    @Test
    fun `an empty stack still says which host it is`() {
        assertContains(bootReport(emptyList(), settled = true, host = "maia"), "<b>maia</b>")
    }

    @Test
    fun `markup in a host label cannot break the message`() {
        assertContains(bootReport(listOf(service("aibot")), settled = true, host = "<b>evil"), "&lt;b&gt;evil")
    }

    @Test
    fun `every change message carries the host, not only the first`() {
        val changes =
            (1..400).map { number ->
                StateChange(service("service-$number", ServiceState.UNHEALTHY), ServiceState.HEALTHY)
            }

        val reports = changeReports(changes, host = "maia")

        assertTrue(reports.size > 1)
        assertTrue(reports.all { it.contains("<b>maia</b>") })
    }

    @Test
    fun `a labelled change report still fits the telegram limit`() {
        val changes =
            (1..400).map { number ->
                StateChange(service("service-$number", ServiceState.UNHEALTHY), ServiceState.HEALTHY)
            }

        assertTrue(changeReports(changes, host = "a".repeat(200)).all { it.length <= 4000 })
    }

    private fun service(
        name: String,
        state: ServiceState = ServiceState.HEALTHY,
        status: String = "Up 2 hours (healthy)",
        project: String? = "netcup"
    ) = Service(project, name, state, status)
}
