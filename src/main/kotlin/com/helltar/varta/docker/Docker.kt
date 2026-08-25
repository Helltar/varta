package com.helltar.varta.docker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

private val MIN_SUPPORTED_API_VERSION = DockerApiVersion(1, 24)
private val MAX_SUPPORTED_API_VERSION = DockerApiVersion(1, 55)

@Serializable
internal data class DockerVersionInfo(
    @SerialName("ApiVersion") val apiVersion: String = "",
    @SerialName("MinAPIVersion") val minApiVersion: String = "1.24"
)

internal data class DockerApiVersion(val major: Int, val minor: Int) : Comparable<DockerApiVersion> {

    init {
        require(major >= 0 && minor >= 0) { "docker api version components must not be negative" }
    }

    override fun compareTo(other: DockerApiVersion) =
        compareValuesBy(this, other, DockerApiVersion::major, DockerApiVersion::minor)

    override fun toString() = "$major.$minor"

    companion object {
        fun parse(value: String): DockerApiVersion {
            val parts = value.removePrefix("v").split('.')
            val major = parts.getOrNull(0)?.toIntOrNull()
            val minor = parts.getOrNull(1)?.toIntOrNull()

            require(major != null && minor != null) { "invalid docker api version [$value]" }

            return DockerApiVersion(major, minor)
        }
    }
}

internal fun DockerVersionInfo.negotiateApiVersion(): DockerApiVersion {
    val serverMaximum = DockerApiVersion.parse(apiVersion)
    val serverMinimum = DockerApiVersion.parse(minApiVersion)
    val minimum = maxOf(serverMinimum, MIN_SUPPORTED_API_VERSION)
    val maximum = minOf(serverMaximum, MAX_SUPPORTED_API_VERSION)

    require(minimum <= maximum) {
        "docker supports api $serverMinimum..$serverMaximum, varta supports " +
                "$MIN_SUPPORTED_API_VERSION..$MAX_SUPPORTED_API_VERSION"
    }

    return maximum
}

/**
 * Reads the state of every container the daemon socket reveals, filtered down to what this
 * instance was asked to watch.
 */
internal class Docker(socket: Path, private val projects: Set<String>, private val ignored: Set<String>) {

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private val api = DockerApi(socket)
    private val apiVersion by lazy {
        json.decodeFromString<DockerVersionInfo>(api.get("/version")).negotiateApiVersion()
    }

    fun services(): List<Service> {
        val summaries =
            json.decodeFromString<List<ContainerSummary>>(get("/containers/json?all=true"))
                .filterNot { it.isOneOff() }
                .map { it to it.toService() }
                .filter { (_, service) -> service.isWatched() }

        return summaries
            .map { (summary, _) -> summary.toService(summary.health()) }
            .withDistinctDisplayNames()
            .sortedWith(compareBy({ it.project.orEmpty() }, { it.name }))
    }

    private fun ContainerSummary.health(): ContainerHealth? {
        if (!state.equals("running", ignoreCase = true)) return null

        require(id.isNotBlank()) { "docker returned a running container without an id" }

        return json.decodeFromString<ContainerDetails>(get("/containers/$id/json")).state.health
    }

    private fun get(path: String) = api.get("/v$apiVersion$path")

    private fun Service.isWatched() =
        (projects.isEmpty() || project in projects) && name !in ignored && containerName !in ignored
}
