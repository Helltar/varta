package com.helltar.varta.docker

import kotlinx.serialization.json.Json
import java.nio.file.Path

private const val DOCKER_API_VERSION = "v1.44"

/**
 * Reads the state of every container the daemon socket reveals, filtered down to what this
 * instance was asked to watch.
 */
internal class Docker(socket: Path, private val projects: Set<String>, private val ignored: Set<String>) {

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private val api = DockerApi(socket)

    fun services(): List<Service> =
        json.decodeFromString<List<ContainerSummary>>(api.get("/$DOCKER_API_VERSION/containers/json?all=true"))
            .filterNot { it.isOneOff() }
            .map { it.toService() }
            .filter { it.isWatched() }
            .withDistinctDisplayNames()
            .sortedWith(compareBy({ it.project.orEmpty() }, { it.name }))

    private fun Service.isWatched() =
        (projects.isEmpty() || project in projects) && name !in ignored && containerName !in ignored
}
