package com.helltar.varta

import com.helltar.varta.docker.Docker
import com.helltar.varta.telegram.Telegram
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

fun main() {
    val socket = Path.of(Config.dockerSocket)
    val docker = Docker(socket, Config.projects, Config.ignored)

    // a socket that cannot be read is a mount or permission mistake, and a watchdog that stays up
    // saying nothing about it is the exact failure it exists to prevent
    val watched =
        runCatching { docker.services() }
            .getOrElse {
                log.error(it) {
                    "Cannot read Docker at [$socket] — check the socket mount, permissions, and API 1.44 support"
                }
                exitProcess(1)
            }

    log.info {
        "Watching ${watched.size} services" +
                Config.projects.takeIf { it.isNotEmpty() }?.let { " in projects=[${it.joinToString()}]" }.orEmpty() +
                ", settle timeout=${Config.settleTimeout}, poll every ${Config.pollInterval}"
    }

    Watcher(
        docker = docker,
        telegram = Telegram(Config.botToken, Config.chatId),
        settleTimeout = Config.settleTimeout,
        pollInterval = Config.pollInterval,
        heartbeatFile = Path.of(Config.heartbeatFile)
    ).run()
}
