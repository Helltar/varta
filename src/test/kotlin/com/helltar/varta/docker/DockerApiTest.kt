package com.helltar.varta.docker

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DockerApiTest {

    @Test
    fun `a docker request times out when the socket stops responding`() {
        withServer { socket, server ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker =
                thread(name = "docker-api-timeout-server", isDaemon = true) {
                    runCatching {
                        server.accept().use {
                            accepted.countDown()
                            release.await()
                        }
                    }
                }

            try {
                val failure =
                    assertFailsWith<SocketTimeoutException> {
                        DockerApi(socket, requestTimeout = 100.milliseconds).get("/containers/json")
                    }

                assertContains(failure.message.orEmpty(), "timed out")
                assertTrue(accepted.await(1, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                server.close()
                worker.join(1_000)
                assertEquals(false, worker.isAlive)
            }
        }
    }

    @Test
    fun `docker negotiates api 1_44 and reads health from container inspect`() {
        withServer { socket, server ->
            val received = mutableListOf<String>()
            val responses =
                listOf(
                    """{"ApiVersion":"1.44","MinAPIVersion":"1.24"}""",
                    """[{"Id":"abc","Names":["/varta"],"State":"running","Status":"Up 2 minutes (healthy)","Labels":{}}]""",
                    """{"State":{"Health":{"Status":"healthy"}}}"""
                )
            val worker =
                thread(name = "docker-api-response-server", isDaemon = true) {
                    responses.forEach { body ->
                        server.accept().use { channel ->
                            received += channel.readRequest()
                            channel.writeResponse(body)
                        }
                    }
                }

            val services = Docker(socket, emptySet(), emptySet()).services()

            worker.join(1_000)
            assertEquals(false, worker.isAlive)
            assertEquals(ServiceState.HEALTHY, services.single().state)
            assertContains(received[0], "GET /version HTTP/1.1")
            assertContains(received[1], "GET /v1.44/containers/json?all=true HTTP/1.1")
            assertContains(received[2], "GET /v1.44/containers/abc/json HTTP/1.1")
        }
    }

    @Test
    fun `the daemon's host name is read from the Name field of docker info`() {
        val json = Json { ignoreUnknownKeys = true }

        assertEquals("maia", json.decodeFromString<DockerInfo>("""{"Name":"maia","NCPU":1}""").name)
        assertEquals("", json.decodeFromString<DockerInfo>("""{"NCPU":1}""").name)
    }

    @Test
    fun `docker caps negotiation at the newest api varta understands`() {
        val version = DockerVersionInfo(apiVersion = "1.60", minApiVersion = "1.44").negotiateApiVersion()

        assertEquals(DockerApiVersion(1, 55), version)
    }

    @Test
    fun `docker rejects a daemon with no compatible api version`() {
        assertFailsWith<IllegalArgumentException> {
            DockerVersionInfo(apiVersion = "1.23", minApiVersion = "1.12").negotiateApiVersion()
        }
        assertFailsWith<IllegalArgumentException> {
            DockerVersionInfo(apiVersion = "1.60", minApiVersion = "1.56").negotiateApiVersion()
        }
    }

    private fun SocketChannel.readRequest(): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(1024)

        while (true) {
            read(buffer)
            buffer.flip()
            bytes.write(buffer.array(), buffer.position(), buffer.remaining())
            buffer.clear()

            if ("\r\n\r\n" in bytes.toString(StandardCharsets.US_ASCII)) {
                return bytes.toString(StandardCharsets.US_ASCII)
            }
        }
    }

    private fun SocketChannel.writeResponse(body: String) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val head =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n\r\n"
        val response = ByteBuffer.wrap(head.toByteArray(StandardCharsets.US_ASCII) + bodyBytes)

        while (response.hasRemaining()) write(response)
    }

    private fun withServer(test: (Path, ServerSocketChannel) -> Unit) {
        val directory = Files.createTempDirectory("varta-docker-api-")
        val socket = directory.resolve("docker.sock")

        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                test(socket, server)
            }
        } finally {
            Files.deleteIfExists(socket)
            Files.deleteIfExists(directory)
        }
    }
}
