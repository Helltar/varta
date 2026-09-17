package com.helltar.varta

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * The file whose age varta's own `HEALTHCHECK` reads.
 *
 * varta is the one container nobody else vouches for, so it leaves the same kind of trace it looks
 * for in others. A failure to touch the file is logged and nothing more: the healthcheck going
 * stale already says everything there is to say about it.
 */
internal class HeartbeatFile(private val path: Path) {

    /**
     * Removes what a previous run left behind. `/tmp` outlives a container restart, so without this
     * the old file would vouch for a run that has not read the socket even once.
     */
    fun clear() {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { log.warn(it) { "Could not remove the previous heartbeat file=[$path]" } }
    }

    fun touch() {
        runCatching { Files.writeString(path, Instant.now().toString()) }
            .onFailure { log.warn(it) { "Could not write the heartbeat file=[$path]" } }
    }
}
