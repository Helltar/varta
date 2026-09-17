package com.helltar.varta

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeartbeatFileTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `a file left by the previous run is removed`() {
        val path = directory.resolve("health")
        Files.writeString(path, "left behind")

        HeartbeatFile(path).clear()

        assertFalse(Files.exists(path))
    }

    @Test
    fun `clearing when nothing was left behind is not an error`() {
        HeartbeatFile(directory.resolve("health")).clear()
    }

    @Test
    fun `touching creates the file`() {
        val path = directory.resolve("health")

        HeartbeatFile(path).touch()

        assertTrue(Files.exists(path))
    }

    @Test
    fun `a file that cannot be written does not take the watcher down`() {
        HeartbeatFile(directory.resolve("missing").resolve("health")).touch()
    }
}
