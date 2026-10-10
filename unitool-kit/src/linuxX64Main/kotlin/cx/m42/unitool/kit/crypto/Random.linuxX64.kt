package cx.m42.unitool.kit.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.linux.SYS_getrandom
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.syscall

/**
 * `getrandom(2)` through `syscall`: the libc wrapper came in glibc 2.25, and Kotlin/Native links
 * against 2.19 (.ai-notes/crypt 02 §1.1). Flags 0 waits until the kernel's pool is initialised, and
 * a short read or `EINTR` is simply read again. There is no `/dev/urandom` fallback: on a kernel
 * older than 3.17 the call fails, and so does the encryption.
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val count = bytes.usePinned { syscall(SYS_getrandom.toLong(), it.addressOf(filled), (size - filled).toLong(), 0) }
        when {
            count > 0 -> filled += count.toInt()
            errno == EINTR -> continue
            else -> error("getrandom failed: errno $errno")
        }
    }
    return bytes
}
