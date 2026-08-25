package com.helltar.varta.docker

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    fun `docker uses api 1_44 and sends the complete request`() {
        withServer { socket, server ->
            val received = CompletableFuture<String>()
            val worker =
                thread(name = "docker-api-response-server", isDaemon = true) {
                    runCatching {
                        server.accept().use { channel ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteBuffer.allocate(1024)

                            while (true) {
                                channel.read(buffer)
                                buffer.flip()
                                bytes.write(buffer.array(), buffer.position(), buffer.remaining())
                                buffer.clear()

                                if ("\r\n\r\n" in bytes.toString(StandardCharsets.US_ASCII)) break
                            }

                            received.complete(bytes.toString(StandardCharsets.US_ASCII))

                            val response =
                                StandardCharsets.US_ASCII.encode(
                                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]"
                                )
                            while (response.hasRemaining()) channel.write(response)
                        }
                    }.onFailure(received::completeExceptionally)
                }

            assertEquals(emptyList(), Docker(socket, emptySet(), emptySet()).services())
            assertContains(received.get(1, TimeUnit.SECONDS), "GET /v1.44/containers/json?all=true HTTP/1.1")

            worker.join(1_000)
            assertEquals(false, worker.isAlive)
        }
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
