package com.helltar.varta

import io.github.cdimascio.dotenv.dotenv
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object Config {

    private val dotenv = dotenv { ignoreIfMissing = true }

    val botToken = readEnv("BOT_TOKEN")
    val chatId = readEnv("CHAT_ID")

    val dockerSocket = readEnv("DOCKER_SOCKET", "/var/run/docker.sock")

    // varta is the one container nobody else vouches for, so it leaves the same kind of trace it
    // looks for in others: a file whose age its own healthcheck reads
    val heartbeatFile = readEnv("HEARTBEAT_FILE", "/tmp/health")

    // the boot report waits for the stack to settle rather than describing it mid-start
    val settleTimeout = readSeconds("SETTLE_TIMEOUT_SECONDS", fallback = 300)
    val pollInterval = readSeconds("POLL_INTERVAL_SECONDS", fallback = 15)

    // compose projects to watch; empty means every container the socket reveals
    val projects = readSet("PROJECTS")
    val ignored = readSet("IGNORE")

    private fun readEnv(env: String) =
        readEnvOrNull(env) ?: throw IllegalArgumentException("environment variable $env is missing or blank")

    private fun readEnv(env: String, fallback: String) = readEnvOrNull(env) ?: fallback

    private fun readEnvOrNull(env: String) = dotenv[env]?.takeIf { it.isNotBlank() }?.trim()

    private fun readSeconds(env: String, fallback: Long): Duration {
        val value = readEnvOrNull(env) ?: return fallback.seconds

        return value.toLongOrNull()?.takeIf { it > 0 }?.seconds
            ?: throw IllegalArgumentException("environment variable $env must be a positive number of seconds")
    }

    private fun readSet(env: String) =
        readEnvOrNull(env)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
}
