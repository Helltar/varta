package com.helltar.varta.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Sends the reports.
 *
 * Deliberately its own bot token: a watchdog that reported through one of the bots it watches would
 * go quiet in exactly the case worth hearing about.
 */
internal class Telegram(private val botToken: String, private val chatId: String) {

    private companion object {
        val REQUEST_TIMEOUT = 30.seconds

        val log = KotlinLogging.logger {}
    }

    private val http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT.toJavaDuration()).build()

    fun send(text: String): Boolean {
        val body =
            form(
                "chat_id" to chatId,
                "text" to text,
                "parse_mode" to "HTML",
                "disable_web_page_preview" to "true"
            )

        val request =
            HttpRequest.newBuilder(URI("https://api.telegram.org/bot$botToken/sendMessage"))
                .timeout(REQUEST_TIMEOUT.toJavaDuration())
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()

        return runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onFailure { log.error(it) { "Could not reach the Telegram API" } }
            .map { response ->
                (response.statusCode() == 200).also {
                    if (!it) log.error { "Telegram rejected the report: ${response.statusCode()} ${response.body()}" }
                }
            }
            .getOrDefault(false)
    }

    private fun form(vararg fields: Pair<String, String>) =
        fields.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
}
