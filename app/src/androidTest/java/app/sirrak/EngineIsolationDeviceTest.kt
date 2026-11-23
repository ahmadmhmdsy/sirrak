package app.sirrak

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineReasons
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.engine.llamacpp.isolated.RemoteInferenceEngine
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-4 device evidence (M8.8): the llama.cpp engine runs in `<applicationId>:engine`, streams tokens
 * back over Messenger, and a dead engine process leaves the UI process alive and recoverable.
 *
 * The crash test uses [RemoteInferenceEngine.simulateCrash], which the service honours only in a
 * debuggable build; the death it causes (the engine process exits abruptly) is the same outcome a
 * failed GGML assertion in llama.cpp produces, which is the failure that must never kill the UI.
 *
 * The model is staged from /sdcard/Download like the M8.9 suite, so a Gradle uninstall does not force
 * a multi-hundred-megabyte push per run.
 */
@RunWith(AndroidJUnit4::class)
class EngineIsolationDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun engineRunsInItsOwnProcessAndStreamsTokens() = runBlocking {
        val file = stageModel(GGUF_FILE)
        if (!file.isFile) {
            assumeTrue("Push " + file + " to /sdcard/Download/ first", false)
        }
        assertTrue("staged model is not readable by the app: " + file, file.canRead())
        val engine = RemoteInferenceEngine(context)
        try {
            engine.connect()
            val model = installed(file)
            assertTrue("isolated engine did not load the model", engine.load(model))
            assertTrue("engine did not report Ready: " + engine.status.value, engine.status.value is EngineStatus.Ready)
            assertNotEquals("engine must not run in the instrumentation process", Process.myPid(), engine.remotePid)
            assertTrue(
                "unexpected engine process name: " + engine.remoteProcessName,
                engine.remoteProcessName?.endsWith(":engine") == true,
            )
            val text = generate(engine, "Answer with one short Arabic sentence about the sky.")
            Log.i(TAG, "isolated generation pid=" + engine.remotePid + " process=" + engine.remoteProcessName + " text=" + text)
            assertTrue("isolated engine produced no tokens", text.isNotBlank())
        } finally {
            engine.close()
        }
    }

    @Test
    fun aDeadEngineProcessLeavesTheUiProcessAliveAndTheEngineRecovers() = runBlocking {
        val file = stageModel(GGUF_FILE)
        if (!file.isFile) {
            assumeTrue("Push " + file + " to /sdcard/Download/ first", false)
        }
        assertTrue("staged model is not readable by the app: " + file, file.canRead())
        val engine = RemoteInferenceEngine(context)
        try {
            engine.connect()
            val model = installed(file)
            assertTrue("isolated engine did not load the model", engine.load(model))
            val firstText = generate(engine, "Answer with one short Arabic sentence about the sky.")
            assertTrue("isolated engine produced no tokens before the crash", firstText.isNotBlank())
            val enginePid = engine.remotePid
            val uiPid = Process.myPid()

            engine.simulateCrash()
            withTimeout(DEATH_TIMEOUT_MS) {
                engine.status.first { it is EngineStatus.Failed }
            }
            val failed = engine.status.value as EngineStatus.Failed
            Log.i(TAG, "engine pid=" + enginePid + " died; reported=" + failed.reason + "; UI pid=" + uiPid + " alive=" + (uiPid == Process.myPid()))
            assertEquals(EngineReasons.PROCESS_LOST, failed.reason)

            // The UI process obviously survived to reach this line; a retry must recover in a fresh process.
            engine.close()
            engine.connect()
            assertTrue("engine did not recover in a fresh process", engine.load(model))
            assertNotEquals("recovery must use a new engine process", enginePid, engine.remotePid)
            val recoveredText = generate(engine, "Answer with one short Arabic sentence about the sky.")
            Log.i(TAG, "recovered generation pid=" + engine.remotePid + " text=" + recoveredText)
            assertTrue("recovered engine produced no tokens", recoveredText.isNotBlank())
        } finally {
            engine.close()
        }
    }

    private suspend fun generate(engine: RemoteInferenceEngine, prompt: String): String {
        val options = GenerationOptions(
            params = GenerationParams(maxTokens = 24, temperature = 0f),
        )
        val chunks = withTimeout(GENERATION_TIMEOUT_MS) {
            engine.stream(prompt, emptyList(), options).toList()
        }
        val failed = chunks.filterIsInstance<EngineChunk.Failed>().firstOrNull()
        assertNull("generation failed: " + failed?.reason, failed)
        return chunks.filterIsInstance<EngineChunk.Delta>().joinToString("") { it.text }
    }

    private fun installed(file: File): InstalledModel = InstalledModel(
        id = file.name,
        displayName = "qwen2.5 0.5b isolated",
        path = file.absolutePath,
        sizeBytes = file.length(),
        quantization = "Q4_K_M",
    )

    private fun modelsDir(): File = File(context.getExternalFilesDir(null), "models")

    private fun stageModel(fileName: String): File {
        val target = File(modelsDir(), fileName)
        modelsDir().mkdirs()
        if (!target.isFile) {
            shell("cp /sdcard/Download/" + fileName + " " + target.absolutePath)
        }
        // The shell-owned copy is unreadable to the app uid; chmod in the same shell fixes it even for
        // copies left behind by an earlier failed run.
        shell("chmod 666 " + target.absolutePath)
        return target
    }

    private fun shell(command: String) {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).use { stream -> stream.readBytes() }
        }
    }

    private companion object {
        const val TAG = "SirrakIsolation"
        const val GGUF_FILE = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        const val DEATH_TIMEOUT_MS = 15_000L
        const val GENERATION_TIMEOUT_MS = 180_000L
    }
}
