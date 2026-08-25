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

@Serializable
internal data class ContainerDetails(
    @SerialName("State") val state: ContainerRuntimeState = ContainerRuntimeState()
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
    STOPPED("⛔"),
    STARTING("🕓"),
    RESTARTING("🔄");

    /** Whether this state is one the stack can be expected to stay in, rather than pass through. */
    val settled get() = this != STARTING && this != RESTARTING

    val wrong get() = this == UNHEALTHY || this == STOPPED
}

internal data class Service(
    val project: String?,
    val name: String,
    val state: ServiceState,
    val status: String,
    val containerName: String = name
)

/**
 * Whether this container came from `docker compose run` rather than the stack itself.
 *
 * Those are meant to end, and they linger in the container list once they have, so counting them
 * would leave the report permanently complaining about work that finished as intended.
 */
internal fun ContainerSummary.isOneOff() = labels[COMPOSE_ONEOFF_LABEL].equals("True", ignoreCase = true)

internal fun ContainerSummary.toService(health: ContainerHealth? = null): Service {
    val containerName = names.firstOrNull()?.removePrefix("/").orEmpty()

    return Service(
        project = labels[COMPOSE_PROJECT_LABEL],
        name = labels[COMPOSE_SERVICE_LABEL] ?: containerName,
        state = resolveState(health),
        status = status,
        containerName = containerName
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
