package cx.m42.services.ssh

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import org.junit.Assume

/**
 * The real tools — `ssh-keygen`, `ssh-add`, `age` — for the interop tests (03 §4, D158). A test that
 * needs one and does not find it is *skipped by name* (`SKIP interop: no age`), never passed: CI
 * installs them, and a green run that tested nothing is worse than a red one.
 */
internal object Tools {

    fun has(tool: String): Boolean = runCatching {
        val process = ProcessBuilder("sh", "-c", "command -v $tool").redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    fun require(vararg tools: String) {
        tools.forEach { Assume.assumeTrue("SKIP interop: no $it", has(it)) }
    }

    class Result(val exit: Int, val out: ByteArray, val err: String) {
        val text: String get() = out.decodeToString()
    }

    fun run(command: List<String>, input: ByteArray? = null, env: Map<String, String> = emptyMap(), dir: File? = null, timeoutSeconds: Long = 120): Result {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(env)
        dir?.let { builder.directory(it) }
        if (input == null) builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val process = builder.start()
        if (input != null) process.outputStream.use { it.write(input) }
        val out = process.inputStream.readAllBytes()
        val err = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "$command timed out" }
        return Result(process.exitValue(), out, err)
    }

    fun ok(command: List<String>, input: ByteArray? = null, env: Map<String, String> = emptyMap()): Result =
        run(command, input, env).also { check(it.exit == 0) { "$command failed (${it.exit}): ${it.err}" } }

    /** Unit on purpose: a JUnit 4 test written as `= Tools.tempDir { … }` has to return void. */
    fun tempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ssh-interop").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 0600, or `ssh-keygen` refuses to read a private key ("UNPROTECTED PRIVATE KEY FILE"). */
    fun ownerOnly(file: File): File = file.also {
        Files.setPosixFilePermissions(it.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }

    /** `ssh-keygen -t …` into [dir]; the private file, with `.pub` next to it. */
    fun keygen(dir: File, name: String, type: List<String>, passphrase: String = "", extra: List<String> = emptyList()): File {
        val file = File(dir, name)
        ok(listOf("ssh-keygen", "-q", "-N", passphrase, "-C", "$name@test", "-f", file.path) + type + extra)
        return file
    }
}
