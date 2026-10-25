package cx.m42.unitool.kit.link

/**
 * DNS messages as multicast DNS (RFC 6762) and DNS-SD (RFC 6763) use them — enough for the computer
 * to ask "who is `_unitool._tcp.local`?" and read the answers (README §6, D547), and for a test
 * phone to answer. No socket here: [query] and [response] make the bytes, [parse] reads them.
 *
 * The phones themselves announce through the platform (`NsdManager`, `NSNetService`); this codec is
 * the CLI's and the tests'.
 */
public object Mdns {
    public const val GROUP_V4: String = "224.0.0.251"
    public const val PORT: Int = 5353

    public const val TYPE_A: Int = 1
    public const val TYPE_PTR: Int = 12
    public const val TYPE_TXT: Int = 16
    public const val TYPE_AAAA: Int = 28
    public const val TYPE_SRV: Int = 33
    public const val TYPE_ANY: Int = 255

    public class Question(public val name: String, public val type: Int, public val unicastResponse: Boolean = false)

    public sealed class Record(public val name: String, public val ttl: Long) {
        public class Ptr(name: String, ttl: Long, public val target: String) : Record(name, ttl)
        public class Srv(name: String, ttl: Long, public val port: Int, public val target: String, public val priority: Int = 0, public val weight: Int = 0) : Record(name, ttl)
        public class Txt(name: String, ttl: Long, public val entries: List<String>) : Record(name, ttl)
        public class A(name: String, ttl: Long, public val address: ByteArray) : Record(name, ttl)
        public class Aaaa(name: String, ttl: Long, public val address: ByteArray) : Record(name, ttl)
    }

    public class Message(public val id: Int, public val isResponse: Boolean, public val questions: List<Question>, public val records: List<Record>)

    /** A service instance put together from PTR, SRV and address records. */
    public class Service(public val instance: String, public val host: String, public val port: Int, public val addresses: List<ByteArray>)

    /** `_unitool._tcp` → `_unitool._tcp.local`. */
    public fun serviceName(type: String): String = "$type.local"

    public fun instanceName(instance: String, type: String): String = "$instance.${serviceName(type)}"

    public fun query(questions: List<Question>, id: Int = 0): ByteArray {
        val w = Writer()
        w.u16(id).u16(0).u16(questions.size).u16(0).u16(0).u16(0)
        for (q in questions) {
            w.name(q.name).u16(q.type).u16(1 or if (q.unicastResponse) 0x8000 else 0)
        }
        return w.bytes()
    }

    /**
     * An authoritative answer with [answers] and [additional] records. [questions] are echoed —
     * which a reply to a legacy unicast query (from a port other than 5353) must do (RFC 6762 §6.7).
     */
    public fun response(answers: List<Record>, additional: List<Record> = emptyList(), questions: List<Question> = emptyList(), id: Int = 0): ByteArray {
        val w = Writer()
        w.u16(id).u16(0x8400).u16(questions.size).u16(answers.size).u16(0).u16(additional.size)
        for (q in questions) w.name(q.name).u16(q.type).u16(1)
        for (r in answers + additional) w.record(r)
        return w.bytes()
    }

    public fun parse(bytes: ByteArray): Message? = runCatching { Reader(bytes).message() }.getOrNull()

    /** Every instance of [type] the records describe, with its port and addresses where they came along. */
    public fun services(records: List<Record>, type: String): List<Service> {
        val service = serviceName(type)
        val instances = records.filterIsInstance<Record.Ptr>().filter { it.name.equals(service, ignoreCase = true) }.map { it.target }.distinctBy { it.lowercase() }
        return instances.mapNotNull { full ->
            val srv = records.filterIsInstance<Record.Srv>().firstOrNull { it.name.equals(full, ignoreCase = true) } ?: return@mapNotNull null
            val addresses = records.filter { it.name.equals(srv.target, ignoreCase = true) }.mapNotNull {
                when (it) {
                    is Record.A -> it.address
                    is Record.Aaaa -> it.address
                    else -> null
                }
            }
            val label = if (full.length > service.length + 1 && full.endsWith(".$service", ignoreCase = true)) full.dropLast(service.length + 1) else full
            Service(label, srv.target, srv.port, addresses)
        }
    }

    // ------------------------------------------------------------ bytes

    private class Writer {
        private val out = ArrayList<Byte>()

        fun u8(v: Int) = apply { out += v.toByte() }
        fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
        fun u32(v: Long) = apply { u16((v ushr 16).toInt() and 0xffff); u16(v.toInt() and 0xffff) }
        fun raw(b: ByteArray) = apply { b.forEach { out += it } }

        /** Names are written whole: no compression, which every reader must accept anyway. */
        fun name(name: String) = apply {
            for (label in name.trimEnd('.').split('.').filter { it.isNotEmpty() }) {
                val bytes = label.encodeToByteArray()
                require(bytes.size in 1..63) { "label too long" }
                u8(bytes.size).raw(bytes)
            }
            u8(0)
        }

        fun record(r: Record) = apply {
            val (type, data) = when (r) {
                is Record.Ptr -> TYPE_PTR to Writer().name(r.target).bytes()
                is Record.Srv -> TYPE_SRV to Writer().u16(r.priority).u16(r.weight).u16(r.port).name(r.target).bytes()
                is Record.Txt -> TYPE_TXT to Writer().apply {
                    if (r.entries.isEmpty()) u8(0)
                    r.entries.forEach { val b = it.encodeToByteArray(); u8(b.size).raw(b) }
                }.bytes()
                is Record.A -> TYPE_A to r.address
                is Record.Aaaa -> TYPE_AAAA to r.address
            }
            // Cache-flush on what is unique to this host (RFC 6762 §10.2); never on the shared PTR.
            val flush = if (r is Record.Ptr) 0 else 0x8000
            name(r.name).u16(type).u16(1 or flush).u32(r.ttl).u16(data.size).raw(data)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Reader(private val b: ByteArray) {
        private var i = 0

        fun message(): Message? {
            val id = u16()
            val flags = u16()
            val qd = u16()
            val an = u16()
            val ns = u16()
            val ar = u16()
            val questions = ArrayList<Question>()
            repeat(qd) {
                val name = name()
                val type = u16()
                val cls = u16()
                questions += Question(name, type, cls and 0x8000 != 0)
            }
            val records = ArrayList<Record>()
            repeat(an + ns + ar) { record()?.let { records += it } }
            return Message(id, flags and 0x8000 != 0, questions, records)
        }

        private fun record(): Record? {
            val name = name()
            val type = u16()
            u16() // class
            val ttl = u32()
            val length = u16()
            val end = i + length
            require(end <= b.size)
            val record = when (type) {
                TYPE_PTR -> Record.Ptr(name, ttl, name())
                TYPE_SRV -> {
                    val priority = u16()
                    val weight = u16()
                    val port = u16()
                    Record.Srv(name, ttl, port, name(), priority, weight)
                }
                TYPE_TXT -> {
                    val entries = ArrayList<String>()
                    while (i < end) {
                        val n = u8()
                        require(i + n <= end)
                        if (n > 0) entries += b.decodeToString(i, i + n)
                        i += n
                    }
                    Record.Txt(name, ttl, entries)
                }
                TYPE_A -> if (length == 4) Record.A(name, ttl, b.copyOfRange(i, end)) else null
                TYPE_AAAA -> if (length == 16) Record.Aaaa(name, ttl, b.copyOfRange(i, end)) else null
                else -> null
            }
            i = end
            return record
        }

        /** A name, following compression pointers (RFC 1035 §4.1.4) — backwards only, so no loop. */
        private fun name(): String {
            val labels = ArrayList<String>()
            var at = i
            var jumped = false
            var guard = 0
            while (true) {
                require(guard++ < 128)
                val len = b[at].toInt() and 0xff
                when {
                    len == 0 -> {
                        at++
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        val target = ((len and 0x3F) shl 8) or (b[at + 1].toInt() and 0xff)
                        require(target < at)
                        if (!jumped) i = at + 2
                        jumped = true
                        at = target
                    }
                    else -> {
                        require(len <= 63 && at + 1 + len <= b.size)
                        labels += b.decodeToString(at + 1, at + 1 + len)
                        at += 1 + len
                    }
                }
            }
            if (!jumped) i = at
            return labels.joinToString(".")
        }

        private fun u8(): Int {
            require(i < b.size)
            return b[i++].toInt() and 0xff
        }

        private fun u16(): Int = (u8() shl 8) or u8()
        private fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    }
}
