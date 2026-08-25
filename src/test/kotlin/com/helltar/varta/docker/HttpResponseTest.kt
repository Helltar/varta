package com.helltar.varta.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpResponseTest {

    @Test
    fun `reads a body sent with content-length`() {
        val response =
            ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: 13\r\n\r\n" +
                    """[{"Id":"a"}]""").toByteArray()

        assertEquals("""[{"Id":"a"}]""", decodeHttpResponse(response))
    }

    @Test
    fun `decodes a chunked body without cutting multi-byte characters`() {
        // "варта" is five characters but ten bytes, so a decoder working on characters would take the
        // chunk apart in the wrong place — the sizes here are byte counts, as the protocol says
        val response =
            ("HTTP/1.1 200 OK\r\n" +
                    "Transfer-Encoding: chunked\r\n\r\n" +
                    "a\r\nварта\r\n" +
                    "2\r\nok\r\n" +
                    "0\r\n\r\n").toByteArray()

        assertEquals("вартаok", decodeHttpResponse(response))
    }

    @Test
    fun `rejects a non-200 status line`() {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()

        assertFailsWith<IllegalArgumentException> { decodeHttpResponse(response) }
    }

    @Test
    fun `rejects a response with no header separator`() {
        assertFailsWith<IllegalArgumentException> { decodeHttpResponse("HTTP/1.1 200 OK".toByteArray()) }
    }

    @Test
    fun `rejects truncated chunk data`() {
        val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabc".toByteArray()

        assertFailsWith<IllegalArgumentException> { decodeHttpResponse(response) }
    }

    @Test
    fun `rejects a chunk without its terminating crlf`() {
        val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc0\r\n\r\n".toByteArray()

        assertFailsWith<IllegalArgumentException> { decodeHttpResponse(response) }
    }

    @Test
    fun `rejects a chunked response without a terminating chunk`() {
        val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n".toByteArray()

        assertFailsWith<IllegalArgumentException> { decodeHttpResponse(response) }
    }
}
