package cx.m42.unitool.cli

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.ECHO
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.EPIPE
import platform.posix.F_OK
import platform.posix.ICANON
import platform.posix.ISIG
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TCSAFLUSH
import platform.posix.VMIN
import platform.posix.VTIME
import platform.posix.access
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.memcpy
import platform.posix.open
import platform.posix.read
import platform.posix.stderr
import platform.posix.stdout
import platform.posix.strerror
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios

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

    override fun secret(prompt: String): String? {
        flush()
        // The terminal itself, as `ssh` and `sudo` do: stdin may be the container coming down a pipe.
        val fd = open("/dev/tty", O_RDWR)
        if (fd < 0) return null
        try {
            fputs(prompt, stderr)
            fflush(stderr)
            val typed = hiddenInput(fd, lines = false) ?: return null
            return typed.decodeToString()
        } finally {
            close(fd)
            fputs("\n", stderr)
            fflush(stderr)
        }
    }

    override fun readStdinHidden(): ByteArray {
        if (!stdinIsTerminal) return readStdin()
        flush()
        try {
            return hiddenInput(STDIN_FILENO, lines = true) ?: ByteArray(0)
        } finally {
            fputs("\n", stderr)
            fflush(stderr)
        }
    }

    override fun trackMouse(point: (x: Int, y: Int) -> Unit, status: () -> String): Boolean {
        flush()
        val fd = open("/dev/tty", O_RDWR)
        if (fd < 0) return false
        try {
            rawTerminal(fd) {
                toTerminal(fd, MOUSE_ON)
                try {
                    var shown = ""
                    fun show() {
                        val line = status()
                        if (line == shown) return
                        shown = line
                        fputs("\r\u001b[K$line", stderr)
                        fflush(stderr)
                    }
                    show()
                    val reader = MouseReports(point)
                    memScoped {
                        val one = allocArray<ByteVar>(1)
                        while (true) {
                            val count = read(fd, one, 1u)
                            if (count < 0L && errno == EINTR) continue
                            if (count <= 0L) break
                            when (reader.feed(one[0].toInt() and 0xff)) {
                                MouseReports.Key.Enter -> break
                                MouseReports.Key.Cancel -> throw Cancelled()
                                MouseReports.Key.None -> show()
                            }
                        }
                    }
                } finally {
                    toTerminal(fd, MOUSE_OFF)
                    fputs("\n", stderr)
                    fflush(stderr)
                }
            }
        } finally {
            close(fd)
        }
        return true
    }

    private fun toTerminal(fd: Int, text: String) {
        val bytes = text.encodeToByteArray()
        bytes.usePinned { platform.posix.write(fd, it.addressOf(0), bytes.size.convert()) }
    }

    /** Runs [block] with [fd], a terminal, in raw mode (no echo, no line editing, no signals), and puts it back after. */
    private inline fun <T> rawTerminal(fd: Int, block: () -> T): T = memScoped {
        val saved = alloc<termios>()
        if (tcgetattr(fd, saved.ptr) != 0) fail("cannot read the terminal: ${reason()}")
        val raw = alloc<termios>()
        memcpy(raw.ptr, saved.ptr, sizeOf<termios>().convert())
        raw.c_lflag = saved.c_lflag and (ECHO or ICANON or ISIG).inv().convert()
        raw.c_cc[VMIN] = 1u
        raw.c_cc[VTIME] = 0u
        if (tcsetattr(fd, TCSAFLUSH, raw.ptr) != 0) fail("cannot set the terminal: ${reason()}")
        try {
            block()
        } finally {
            tcsetattr(fd, TCSAFLUSH, saved.ptr)
        }
    }

    /**
     * Reads [fd], a terminal, with echo, line editing and signals off, and does the little editing
     * itself: Backspace takes back a character, Ctrl-U the line, Ctrl-C cancels (with the terminal
     * put back first — a signal would leave it without echo), Ctrl-D ends. One line, or with
     * [lines] everything up to Ctrl-D. Null at Ctrl-D before anything was typed in one-line mode.
     */
    private fun hiddenInput(fd: Int, lines: Boolean): ByteArray? = rawTerminal(fd) {
        memScoped {
            val typed = ArrayList<Byte>()
            val one = allocArray<ByteVar>(1)
            while (true) {
                val count = read(fd, one, 1u)
                if (count < 0L && errno == EINTR) continue
                if (count <= 0L) break
                when (val byte = one[0].toInt() and 0xff) {
                    0x03 -> throw Cancelled()
                    0x04 -> return@memScoped if (!lines && typed.isEmpty()) null else typed.toByteArray()
                    0x0d, 0x0a -> if (lines) typed += 0x0a else return@memScoped typed.toByteArray()
                    0x7f, 0x08 -> {
                        // A whole UTF-8 character: its continuation bytes, then its first byte.
                        while (typed.isNotEmpty() && typed.last().toInt() and 0xc0 == 0x80) typed.removeAt(typed.size - 1)
                        if (typed.isNotEmpty() && (!lines || typed.last() != 0x0a.toByte())) typed.removeAt(typed.size - 1)
                    }
                    0x15 -> while (typed.isNotEmpty() && typed.last() != 0x0a.toByte()) typed.removeAt(typed.size - 1)
                    else -> typed += byte.toByte()
                }
            }
            typed.toByteArray()
        }
    }

    override fun readFile(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        if (fd < 0) fail("cannot read $path: ${reason()}")
        try {
            val chunks = ArrayList<ByteArray>()
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
                when {
                    count > 0 -> chunks += buffer.copyOf(count.toInt())
                    count == 0L -> break
                    errno == EINTR -> continue
                    else -> fail("cannot read $path: ${reason()}")
                }
            }
            val all = ByteArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(all, offset)
                offset += chunk.size
            }
            return all
        } finally {
            close(fd)
        }
    }

    override fun exists(path: String): Boolean = access(path, F_OK) == 0 || errno != ENOENT

    override fun writeFile(path: String, bytes: ByteArray, private: Boolean, replace: Boolean) {
        val flags = O_WRONLY or O_CREAT or (if (replace) O_TRUNC else O_EXCL)
        val fd = open(path, flags, if (private) 0x180 else 0x1b6)
        if (fd < 0) {
            if (errno == EEXIST) fail("$path exists; it was left as it was")
            fail("cannot write $path: ${reason()}")
        }
        try {
            // A file that was there before keeps its mode through O_TRUNC; a private one must not.
            if (private && fchmod(fd, 0x180u) != 0) fail("cannot make $path private: ${reason()}")
            var written = 0
            while (written < bytes.size) {
                val count = bytes.usePinned { platform.posix.write(fd, it.addressOf(written), (bytes.size - written).convert()) }
                when {
                    count > 0 -> written += count.toInt()
                    errno == EINTR -> continue
                    else -> fail("cannot write $path: ${reason()}")
                }
            }
        } finally {
            if (close(fd) != 0) fail("cannot write $path: ${reason()}")
        }
    }

    /** SIGPIPE usually ends the process first; this is for when it is ignored and write fails with EPIPE. */
    private fun broken(): Nothing =
        if (errno == EPIPE) throw StdoutClosed() else fail("cannot write to stdout: ${reason()}")

    private fun reason(): String = strerror(errno)?.toKString() ?: "errno $errno"
}
