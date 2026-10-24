package com.piyushbodaa.autark.devicetest

import android.app.ActivityManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piyushbodaa.autark.engine.LlamaEngine
import com.piyushbodaa.autark.models.Catalog
import com.piyushbodaa.autark.models.CatalogModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

private const val TAG = "AutarkDeviceTest"
private const val MIB = 1024 * 1024L

/**
 * Measures Autark's engine on a real phone, for when there is none at hand: Firebase Test Lab
 * runs these on its devices (tools/device-test/run.sh). Each test runs in a process of its own
 * (Android Test Orchestrator), because a process only ever loads one CPU backend variant.
 *
 * Results go to logcat (tag AutarkDeviceTest) and to device-test/ in the app's external files
 * directory, which run.sh downloads. The model is the app's.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceTest {
    @get:Rule
    val testName = TestName()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.getExternalFilesDir(null), "device-test").apply { mkdirs() }
    private val threads = LlamaEngine.defaultThreads()
    private var started = 0L

    private val model: CatalogModel = Catalog.model

    // The order Engine::init tries them in (CPU_VARIANTS in engine.cpp): the first that loads wins.
    private val variants = if (Build.SUPPORTED_ABIS.first() == "arm64-v8a") {
        listOf(
            "android_armv9.2_2", "android_armv9.2_1", "android_armv8.6_1",
            "android_armv8.2_2", "android_armv8.2_1", "android_armv8.0_1",
        )
    } else {
        listOf(
            "sapphirerapids", "zen4", "cooperlake", "icelake", "cascadelake", "cannonlake", "skylakex",
            "alderlake", "haswell", "piledriver", "ivybridge", "sandybridge", "sse42", "x64",
        )
    }
    private val appVariant by lazy { variants.first { DeviceTools.score(it) > 0 } }

    @Before
    fun start() {
        started = SystemClock.elapsedRealtime()
        log("${testName.methodName}: starting, ${thermal()}")
    }

    @After
    fun end() {
        log("${testName.methodName}: took ${(SystemClock.elapsedRealtime() - started) / 1000} s, ${thermal()}")
    }

    /** What the phone is, and what the app makes of it. Also gets the model onto it. */
    @Test
    fun a_device() {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        report("device", buildString {
            appendLine(
                "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT}), kernel ${System.getProperty("os.version")}",
            )
            if (Build.VERSION.SDK_INT >= 31) appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}; page size ${Os.sysconf(OsConstants._SC_PAGESIZE) / 1024} KB")
            appendLine("Memory: ${memory.totalMem / MIB} MiB, ${memory.availMem / MIB} MiB available; testing ${model.name}")
            appendLine("Cores (capacity, max MHz): ${cores()}")
            appendLine("Threads the app uses: ${threads.generation} to generate, ${threads.batch} for prompts")
            appendLine("CPU variants (score): " + variants.joinToString { "$it ${DeviceTools.score(it)}" })
            appendLine("The app loads: $appVariant")
            appendLine()
            append(File("/proc/cpuinfo").readText())
        })
        modelFile()  // downloaded here, so later tests don't count it
    }

    // Every variant the CPU can run, at the app's thread counts. The app takes the first that
    // loads; these show whether that is also the fastest. Each runs twice, in one order and then
    // the other, so that the phone warming up in between evens out.
    @Test
    fun b1_armv9_2_2() = variant("android_armv9.2_2", 1)

    @Test
    fun b2_armv8_6_1() = variant("android_armv8.6_1", 1)

    @Test
    fun b3_armv8_2_2() = variant("android_armv8.2_2", 1)

    @Test
    fun b4_armv8_2_2() = variant("android_armv8.2_2", 2)

    @Test
    fun b5_armv8_6_1() = variant("android_armv8.6_1", 2)

    @Test
    fun b6_armv9_2_2() = variant("android_armv9.2_2", 2)

    /**
     * Every thread count from 2 up, with the variant the app loads. Generation is bound by memory
     * bandwidth and prompts by arithmetic, so the two peak at different counts. Phones throttle
     * as they warm up, which would favor whichever counts ran first, so three rounds visit them
     * in different orders (summarize.py averages them).
     */
    @Test
    fun c_threads() {
        assertTrue(DeviceTools.useVariant(appVariant))
        val counts = (2..Runtime.getRuntime().availableProcessors()).toList()
        listOf(counts, counts.reversed(), counts.shuffled(Random(1))).forEachIndexed { round, order ->
            bench("threads-${round + 1}", "-t", order.joinToString(","), "-r", "1")
        }
    }

    /** The conversations of tools/engine-test: follow-ups, Retry, Stop, reopening, a saved session. */
    @Test
    fun d_engine() {
        Os.setenv("TMPDIR", context.cacheDir.path, true)
        val text = File(out, "engine.txt")
        val errors = File(out, "engine.log")
        val args = arrayOf(
            modelFile().path,
            "--threads", "${threads.generation}",
            "--threads-batch", "${threads.batch}",
            "--max-tokens", "128",
        )
        val rc = sampled("engine") { DeviceTools.engineTest(args, text.path, errors.path) }
        show("engine", text.readText())
        assertEquals("engine-test failed: ${errors.readText().takeLast(2000)}", 0, rc)
    }

    /** The app's own way in, through LlamaEngine and libautark.so: load, reply, save the chat, let go. */
    @Test
    fun e_app() = sampled("app") {
        val session = File(context.cacheDir, "device-test-session.bin").apply { delete() }
        val engine = LlamaEngine(context.applicationInfo.nativeLibraryDir, session)
        runBlocking {
            val loadStart = SystemClock.elapsedRealtime()
            assertTrue(engine.load(model.id, modelFile().path, 4096, threads))
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val ready = engine.state.value
            val loaded = memory()
            val events = engine.generate(
                listOf("system", "user"),
                listOf("You are Autark, a helpful assistant.", "What can you do without the internet?"),
                LlamaEngine.Options(
                    thinking = false, temperature = 0.7f, topP = 0.95f, topK = 40, minP = 0.05f,
                    presencePenalty = 0f, maxTokens = 96,
                ),
            ).toList()
            val reply = events.filterIsInstance<LlamaEngine.Event.Text>().joinToString("") { it.text }
            val finished = events.filterIsInstance<LlamaEngine.Event.Finished>().single()
            engine.release { false }
            report("app", buildString {
                appendLine("$ready")
                appendLine("loaded in $loadMs ms; memory then: $loaded")
                appendLine("%d tokens in %.1f s (%.1f tok/s)".format(finished.tokens, finished.seconds, finished.tokens / finished.seconds))
                appendLine("session saved: ${session.length() / MIB} MiB")
                appendLine("reply: $reply")
            })
            assertTrue("empty reply", reply.isNotBlank())
            assertTrue("no saved session", session.length() > 0)
        }
    }

    private fun variant(name: String, pass: Int) {
        assumeTrue("$name can't run on this CPU", DeviceTools.score(name) > 0)
        assertTrue(DeviceTools.useVariant(name))
        bench("variant-$name-$pass", "-t", "${threads.generation},${threads.batch}", "-r", "2")
    }

    // llama-bench with the app's batch sizes, prompts the length of a typical first message and
    // replies long enough to time; JSON results, with a readable table for the log.
    private fun bench(name: String, vararg args: String) {
        val json = File(out, "$name.json")
        val table = File(out, "$name.md")
        val common = arrayOf("-m", modelFile().path, "-b", "512", "-ub", "512", "-p", "128", "-n", "32", "-o", "json", "-oe", "md")
        val rc = DeviceTools.llamaBench(arrayOf(*common, *args), json.path, table.path)
        show(name, table.readText())
        assertEquals("llama-bench failed", 0, rc)
    }

    // run.sh pushes the models onto the phone before the tests start; downloading them is the
    // fallback, and a slow one on Test Lab's network.
    private fun modelFile(): File {
        val file = File(context.getExternalFilesDir("test-models"), model.fileName)
        if (file.length() != model.sizeBytes) download(model, file)
        return file
    }

    // Straight from Hugging Face, checked against the catalog's SHA-256. Connections
    // there stall now and then, so it picks up where it stopped.
    private fun download(model: CatalogModel, file: File) {
        val partial = File(file.path + ".part")
        val start = SystemClock.elapsedRealtime()
        for (attempt in 1..5) {
            val connection = URL(model.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            if (partial.length() > 0) connection.setRequestProperty("Range", "bytes=${partial.length()}-")
            try {
                val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                connection.inputStream.use { input ->
                    FileOutputStream(partial, resumed).use { output -> input.copyTo(output, 1 shl 20) }
                }
                break
            } catch (e: IOException) {
                log("download stopped at ${partial.length() / MIB} MiB (attempt $attempt): $e")
            } finally {
                connection.disconnect()
            }
        }
        assertEquals("size of ${model.fileName}", model.sizeBytes, partial.length())
        assertEquals("checksum of ${model.fileName}", model.sha256, sha256(partial))
        assertTrue(partial.renameTo(file))
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        log("downloaded ${model.fileName} in %.0f s (%.1f MB/s)".format(seconds, file.length() / 1e6 / seconds))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cores(): String {
        val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
        return cpus.sortedBy { it.name.removePrefix("cpu").toInt() }.joinToString { cpu ->
            val capacity = read(File(cpu, "cpu_capacity"))
            val mhz = read(File(cpu, "cpufreq/cpuinfo_max_freq")).toLongOrNull()?.div(1000) ?: "?"
            "$capacity/$mhz"
        }
    }

    private fun memory(): String {
        val status = File("/proc/self/status").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        return "${status["VmRSS"]} (anonymous ${status["RssAnon"]}, files ${status["RssFile"]})"
    }

    // Writes this process's memory every 50 ms while [block] runs to device-test/NAME-memory.txt
    // (milliseconds, then MiB anonymous and from files), to see when a peak happens and why.
    private fun <T> sampled(name: String, block: () -> T): T {
        val samples = StringBuilder()
        val start = SystemClock.elapsedRealtime()
        val sampler = thread(name = "memory") {
            try {
                while (true) {
                    val status = File("/proc/self/status").readLines()
                    val kb = { key: String -> status.first { it.startsWith("$key:") }.filter(Char::isDigit).toLong() / 1024 }
                    samples.appendLine("${SystemClock.elapsedRealtime() - start} ${kb("RssAnon")} ${kb("RssFile")}")
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) {
            }
        }
        try {
            return block()
        } finally {
            sampler.interrupt()
            sampler.join()
            File(out, "$name-memory.txt").writeText(samples.toString())
        }
    }

    private fun thermal(): String {
        if (Build.VERSION.SDK_INT < 30) return "thermal state unknown"
        val power = context.getSystemService(PowerManager::class.java)
        return "thermal status ${power.currentThermalStatus}, headroom %.2f".format(power.getThermalHeadroom(0))
    }

    private fun read(file: File) = runCatching { file.readText().trim() }.getOrDefault("?")

    private fun report(name: String, text: String) {
        File(out, "$name.txt").writeText(text)
        show(name, text)
    }

    // llama-bench first looks for backends in directories an app may not list, and says so.
    private fun show(name: String, text: String) =
        text.lineSequence().filterNot { it.startsWith("ggml_backend_load_best") }.forEach { log("$name: $it") }

    private fun log(line: String) {
        Log.i(TAG, line)
    }
}
