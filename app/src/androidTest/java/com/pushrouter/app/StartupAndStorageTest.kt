package com.pushrouter.app

import android.content.ContextWrapper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.pushrouter.app.data.EncryptedStateFile
import com.pushrouter.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Runs with empty app data on a disposable emulator; no real bot token and no network requests. */
class StartupAndStorageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun firstLaunchAndNavigation() {
        compose.onNodeWithText("Listening for new notifications").assertDoesNotExist()
        compose.onNodeWithText("Notification access is off").assertIsDisplayed()
        compose.onNodeWithText("Routes").performClick()
        compose.onNodeWithText("Create route").assertIsDisplayed()
        compose.onNodeWithText("Pairings").performClick()
        compose.onNodeWithText("Configure bot first").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Notification access").assertIsDisplayed()
        compose.onNodeWithText("No bot configured").assertIsDisplayed()
    }

    @Test fun encryptedSnapshotRoundTripsWithoutPlaintextCredentials() {
        withTestFile { directory, storage ->
            val snapshot = RouterState(bot = Bot(10, "fake_bot", "private-test-credential"),
                routes = listOf(Route(name = "Payments", packageName = "com.bank", profile = "0", pairingIds = setOf("p1"))),
                pairings = listOf(Pairing(id = "p1", label = "Work", botId = 10, userId = 20, chatId = 20, displayName = "Test recipient", username = null)))
            storage.write(snapshot)
            assertEquals(snapshot, storage.read())
            val bytes = File(directory, "router-state.enc").readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("private-test-credential"))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("Test recipient"))
        }
    }

    @Test fun tamperedSavedStateFailsClosed() {
        withTestFile { directory, storage ->
            storage.write(RouterState(paused = true))
            val file = File(directory, "router-state.enc")
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertThrows(Exception::class.java) { storage.read() }
        }
    }

    private fun withTestFile(block: (File, EncryptedStateFile) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "encrypted-state-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(target) { override fun getNoBackupFilesDir(): File = directory }
        try { block(directory, EncryptedStateFile(isolated)) } finally { directory.deleteRecursively() }
    }
}
