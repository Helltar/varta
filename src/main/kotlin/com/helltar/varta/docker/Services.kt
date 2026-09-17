package com.helltar.varta.docker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val COMPOSE_PROJECT_LABEL = "com.docker.compose.project"
private const val COMPOSE_SERVICE_LABEL = "com.docker.compose.service"
private const val COMPOSE_ONEOFF_LABEL = "com.docker.compose.oneoff"

/**
 * What one container looks like in `GET /containers/json`.
 */
@Serializable
internal data class ContainerSummary(
    @SerialName("Id") val id: String = "",
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("State") val state: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("Labels") val labels: Map<String, String> = emptyMap()
)

@Serializable
internal data class ContainerHealth(@SerialName("Status") val status: String = "")

/**
 * What `GET /containers/{id}/json` adds to the list: the health the list cannot be trusted to carry,
 * and how many times the restart policy has brought the container back.
 */
@Serializable
internal data class ContainerDetails(
    @SerialName("State") val state: ContainerRuntimeState = ContainerRuntimeState(),
    @SerialName("RestartCount") val restartCount: Int = 0
)

@Serializable
internal data class ContainerRuntimeState(
    @SerialName("Health") val health: ContainerHealth? = null
)

/**
 * The states a service can be reported in.
 *
 * Declaration order is the order the roll call is printed in — confirmed first, unverified next, then
 * everything that needs looking at. Reordering these constants changes the report.
 */
internal enum class ServiceState(val glyph: String) {

    HEALTHY("✅"),

    // running with no healthcheck declared: honest about being unmeasured rather than counted as fine
    UNMEASURED("⚪"),

    UNHEALTHY("❌"),

    // never read off a single container: it is what `RestartTracker` makes of a service that keeps
    // coming back, which one reading cannot tell from an ordinary restart
    CRASH_LOOPING("💥"),

    STOPPED("⛔"),
    STARTING("🕓"),
    RESTARTING("🔄");

    /** Whether this state is one the stack can be expected to stay in, rather than pass through. */
    val settled get() = this != STARTING && this != RESTARTING

    val wrong get() = this == UNHEALTHY || this == CRASH_LOOPING || this == STOPPED
}

internal data class Service(
    val project: String?,
    val name: String,
    val state: ServiceState,
    val status: String,
    val containerName: String = name,

    // restarts the daemon's restart policy has performed, or null for a container that was not inspected
    val restartCount: Int? = null
)

/**
 * Whether this container came from `docker compose run` rather than the stack itself.
 *
 * Those are meant to end, and they linger in the container list once they have, so counting them
 * would leave the report permanently complaining about work that finished as intended.
 */
internal fun ContainerSummary.isOneOff() = labels[COMPOSE_ONEOFF_LABEL].equals("True", ignoreCase = true)

internal fun ContainerSummary.toService(details: ContainerDetails? = null): Service {
    val containerName = names.firstOrNull()?.removePrefix("/").orEmpty()

    return Service(
        project = labels[COMPOSE_PROJECT_LABEL],
        name = labels[COMPOSE_SERVICE_LABEL] ?: containerName,
        state = resolveState(details?.state?.health),
        status = status,
        containerName = containerName,
        restartCount = details?.restartCount
    )
}

internal fun List<Service>.withDistinctDisplayNames(): List<Service> {
    val counts = groupingBy { it.project to it.name }.eachCount()

    return map { service ->
        if (counts.getValue(service.project to service.name) == 1) service
        else service.copy(name = "${service.name} (${service.containerName})")
    }
}

private fun ContainerSummary.resolveState(health: ContainerHealth?) =
    when {
        state.equals("restarting", ignoreCase = true) -> ServiceState.RESTARTING
        !state.equals("running", ignoreCase = true) -> ServiceState.STOPPED

        // absent for a container without a healthcheck, and "none" is what podman reports instead
        health == null || health.status.equals("none", ignoreCase = true) -> ServiceState.UNMEASURED

        health.status.equals("healthy", ignoreCase = true) -> ServiceState.HEALTHY
        health.status.equals("unhealthy", ignoreCase = true) -> ServiceState.UNHEALTHY
        else -> ServiceState.STARTING
    }
