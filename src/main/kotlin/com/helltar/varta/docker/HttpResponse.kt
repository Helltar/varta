package com.helltar.varta.docker

import java.io.ByteArrayOutputStream

private const val CR: Byte = 13
private const val LF: Byte = 10

private val HEADER_END = byteArrayOf(CR, LF, CR, LF)

/**
 * Splits a raw HTTP/1.1 response into its status line, headers and body, and returns the decoded
 * body.
 *
 * Kept byte-oriented on purpose: chunk sizes are counts of bytes, so decoding a chunked body from
 * a string would cut it in the wrong place the moment a response carries anything outside ASCII.
 */
internal fun decodeHttpResponse(response: ByteArray): String {
    val headerEnd = response.indexOf(HEADER_END)

    require(headerEnd >= 0) { "no header/body separator in the response" }

    val head = response.decodeToString(0, headerEnd)
    val body = response.copyOfRange(headerEnd + HEADER_END.size, response.size)
    val status = head.substringBefore('\r')
    val code = status.split(' ').getOrNull(1)?.toIntOrNull()

    require(code == 200) { "unexpected status line: $status" }

    val chunked =
        head.lineSequence().any {
            it.substringBefore(':').trim().equals("Transfer-Encoding", ignoreCase = true) &&
                    it.substringAfter(':').contains("chunked", ignoreCase = true)
        }

    return if (chunked) dechunk(body).decodeToString() else body.decodeToString()
}

private fun dechunk(body: ByteArray): ByteArray {
    val decoded = ByteArrayOutputStream(body.size)
    var offset = 0

    while (offset < body.size) {
        val lineEnd = body.indexOf(byteArrayOf(CR, LF), from = offset)
        require(lineEnd >= 0) { "unterminated chunk size in the response" }

        // a chunk header may carry extensions after a semicolon, which are not part of the size
        val size = body.decodeToString(offset, lineEnd).substringBefore(';').trim().toIntOrNull(radix = 16)

        require(size != null && size >= 0) { "malformed chunk size in the response" }

        if (size == 0) {
            var trailerOffset = lineEnd + 2

            while (true) {
                val trailerLineEnd = body.indexOf(byteArrayOf(CR, LF), from = trailerOffset)
                require(trailerLineEnd >= 0) { "unterminated chunk trailer in the response" }

                if (trailerLineEnd == trailerOffset) return decoded.toByteArray()

                trailerOffset = trailerLineEnd + 2
            }
        }

        val start = lineEnd + 2
        require(size <= body.size - start) { "truncated chunk data in the response" }

        val end = start + size
        require(end + 1 < body.size && body[end] == CR && body[end + 1] == LF) {
            "missing chunk terminator in the response"
        }

        decoded.write(body, start, size)

        // step over the CRLF that terminates the chunk data
        offset = end + 2
    }

    throw IllegalArgumentException("chunked response has no terminating chunk")
}

private fun ByteArray.indexOf(pattern: ByteArray, from: Int = 0): Int {
    if (pattern.isEmpty() || pattern.size > size) return -1

    outer@ for (start in from..size - pattern.size) {
        for (offset in pattern.indices) {
            if (this[start + offset] != pattern[offset]) continue@outer
        }

        return start
    }

    return -1
}
