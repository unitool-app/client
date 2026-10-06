package cx.m42.unitool.cli

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.EPIPE
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.read
import platform.posix.stderr
import platform.posix.stdout
import platform.posix.strerror

/**
 * The process's own streams, through libc. `isatty` answers for each of the three on its own, so the
 * menu (stdin and stderr) and the refusal to print a PNG on the screen (stdout) need no help from
 * outside.
 */
@OptIn(ExperimentalForeignApi::class)
public class SystemIo : Io {
    override val stdinIsTerminal: Boolean = isatty(STDIN_FILENO) == 1
    override val stdoutIsTerminal: Boolean = isatty(STDOUT_FILENO) == 1
    override val stderrIsTerminal: Boolean = isatty(STDERR_FILENO) == 1

    override fun env(name: String): String? = getenv(name)?.toKString()

    override fun readStdin(): ByteArray {
        val chunks = ArrayList<ByteArray>()
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val count = buffer.usePinned { read(STDIN_FILENO, it.addressOf(0), buffer.size.convert()) }
            when {
                count > 0 -> chunks += buffer.copyOf(count.toInt())
                count == 0L -> break
                errno == EINTR -> continue
                else -> fail("cannot read stdin: ${reason()}")
            }
        }
        val all = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(all, offset)
            offset += chunk.size
        }
        return all
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), stdout) }
        if (written.toInt() < bytes.size) broken()
    }

    override fun flush() {
        if (fflush(stdout) != 0) broken()
    }

    override fun say(text: String) {
        flush()
        fputs(text + "\n", stderr)
        fflush(stderr)
    }

    override fun ask(prompt: String): String? {
        flush()
        fputs(prompt, stderr)
        fflush(stderr)
        return readlnOrNull()
    }

    override fun pause(prompt: String): Boolean {
        flush()
        if (stdinIsTerminal) return ask(prompt) != null
        // stdin is the text being encoded, so the keyboard is reached the way `read _ </dev/tty` does.
        val terminal = fopen("/dev/tty", "r") ?: return false
        try {
            fputs(prompt, stderr)
            fflush(stderr)
            return memScoped {
                val line = allocArray<ByteVar>(256)
                fgets(line, 256, terminal) != null
            }
        } finally {
            fclose(terminal)
        }
    }

    override fun writeFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: fail("cannot write $path: ${reason()}")
        try {
            val written = if (bytes.isEmpty()) 0uL else bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }
            if (written.toInt() < bytes.size) fail("cannot write $path: ${reason()}")
        } finally {
            if (fclose(file) != 0) fail("cannot write $path: ${reason()}")
        }
    }

    /** SIGPIPE usually ends the process first; this is for when it is ignored and write fails with EPIPE. */
    private fun broken(): Nothing =
        if (errno == EPIPE) throw StdoutClosed() else fail("cannot write to stdout: ${reason()}")

    private fun reason(): String = strerror(errno)?.toKString() ?: "errno $errno"
}
