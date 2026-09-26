package cx.m42.services.ssh.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks

/*
 * The CoreFoundation glue every Security.framework call here needs (07 §3, question 84): bytes to
 * CFData and back, a dictionary with CF's own retain callbacks. Shared by the keyring's Secure
 * Enclave backend and, through its dependency on this module, by `services/vault`.
 */

@OptIn(ExperimentalForeignApi::class)
public fun ByteArray.toCFData(): CFDataRef =
    usePinned { pinned ->
        CFDataCreate(null, if (isEmpty()) null else pinned.addressOf(0).reinterpret<UByteVar>(), size.convert())
    } ?: error("CFDataCreate failed")

@OptIn(ExperimentalForeignApi::class)
public fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    return CFDataGetBytePtr(this)!!.readBytes(length)
}

/** A CF dictionary with CF's own retain callbacks, so the values may be released after this. */
@OptIn(ExperimentalForeignApi::class)
public fun cfDictionary(vararg entries: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef {
    val dictionary = CFDictionaryCreateMutable(null, entries.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        ?: error("CFDictionaryCreateMutable failed")
    entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
    return dictionary
}
