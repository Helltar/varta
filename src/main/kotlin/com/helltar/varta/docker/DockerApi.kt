package com.helltar.varta.docker

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val DEFAULT_REQUEST_TIMEOUT = 30.seconds

/**
 * Minimal HTTP/1.1 client for the Docker daemon socket.
 *
 * The JDK has spoken Unix domain sockets natively since 16, and everything this needs is a `GET`
 * returning JSON — so an HTTP library would be a dependency carried, and kept up to date, for a
 * request that fits on a screen.
 */
internal class DockerApi(
    private val socket: Path,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT
) {

    init {
        require(requestTimeout.inWholeMilliseconds > 0) { "docker request timeout must be at least one millisecond" }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 64 * 1024

        val executor = Executors.newVirtualThreadPerTaskExecutor()
    }

    fun get(path: String): String {
        val request = executor.submit<String> { blockingGet(path) }

        return try {
            request.get(requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (exception: TimeoutException) {
            request.cancel(true)
            throw SocketTimeoutException("docker socket request timed out after $requestTimeout").apply {
                initCause(exception)
            }
        } catch (exception: InterruptedException) {
            request.cancel(true)
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
    }

    private fun blockingGet(path: String): String =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket))

            val request = StandardCharsets.US_ASCII.encode(request(path))
            while (request.hasRemaining()) channel.write(request)

            decodeHttpResponse(channel.readToEnd())
        }

    // `Connection: close` makes the daemon end the stream when the body is done, so the response is
    // simply everything read up to EOF and there is no need to honor keep-alive framing
    private fun request(path: String) =
        "GET $path HTTP/1.1\r\n" +
                "Host: docker\r\n" +
                "Accept: application/json\r\n" +
                "Connection: close\r\n\r\n"

    private fun SocketChannel.readToEnd(): ByteArray {
        val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
        val bytes = ByteArrayOutputStream()

        while (read(buffer) >= 0) {
            buffer.flip()
            bytes.write(buffer.array(), buffer.position(), buffer.remaining())
            buffer.clear()
        }

        return bytes.toByteArray()
    }
}
