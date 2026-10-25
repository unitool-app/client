package cx.m42.unitool.kit.link

import cx.m42.unitool.kit.crypto.hex
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.json.FlatJson
import cx.m42.unitool.kit.json.JsonWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The link's pieces around Noise (passwords-browser 01), and the whole exchange in memory. */
class LinkTest {

    @Test
    fun framesComeOutWholeHoweverTheBytesWereSplit() {
        val messages = listOf(ByteArray(0), ByteArray(1) { 7 }, ByteArray(300) { it.toByte() }, ByteArray(Noise.MAX_MESSAGE) { 1 })
        val stream = messages.map(LinkWire::frame).reduce { a, b -> a + b }
        for (chunk in listOf(1, 2, 3, 1000, stream.size)) {
            val reader = FrameReader()
            val out = ArrayList<ByteArray>()
            var at = 0
            while (at < stream.size) {
                val n = minOf(chunk, stream.size - at)
                reader.push(stream, at, n)
                at += n
                while (true) out += reader.next() ?: break
            }
            assertEquals(messages.size, out.size, "chunk $chunk")
            messages.zip(out).forEach { (a, b) -> assertContentEquals(a, b) }
            assertEquals(0, reader.pending)
        }
    }

    @Test
    fun thePreambleNamesTheModeAndNothingElsePasses() {
        for (mode in LinkWire.Mode.entries) assertEquals(mode, LinkWire.mode(LinkWire.preamble(mode)))
        assertNull(LinkWire.mode(byteArrayOf(0x55, 0x4C, 2, 1)))
        assertNull(LinkWire.mode(byteArrayOf(0x55, 0x4C, 1, 3)))
        assertNull(LinkWire.mode("GET ".encodeToByteArray()))
        assertTrue(!LinkWire.prologue(LinkWire.Mode.Pair).contentEquals(LinkWire.prologue(LinkWire.Mode.Session)))
    }

    @Test
    fun messagesSurviveTheWireAndGarbageDoesNot() {
        val id = ByteArray(16) { it.toByte() }
        val all = listOf(
            LinkMessage.Error("clock"),
            LinkMessage.Welcome("Pixel 7", ByteArray(8) { 3 }),
            LinkMessage.Paired,
            LinkMessage.PasswordRequest(id, "https://github.com", "Chrome 141 · Linux", 1_792_000_000_123),
            LinkMessage.PasswordShown(id),
            LinkMessage.PasswordResponse(id, LinkMessage.FILL, ByteArray(200) { 9 }),
        )
        for (m in all) {
            val bytes = LinkMessage.encode(m)
            val back = LinkMessage.decode(bytes)
            assertNotNull(back, m.toString())
            assertContentEquals(bytes, LinkMessage.encode(back))
            assertNull(LinkMessage.decode(bytes + 0), "trailing byte after $m")
            if (bytes.size > 1) assertNull(LinkMessage.decode(bytes.copyOf(bytes.size - 1)), "short $m")
        }
        assertNull(LinkMessage.decode(byteArrayOf(99)))
        assertNull(LinkMessage.decode(ByteArray(0)))
        val request = LinkMessage.decode(LinkMessage.encode(all[3])) as LinkMessage.PasswordRequest
        assertEquals(1_792_000_000_123, request.issuedAt)
        assertEquals("Chrome 141 · Linux", request.browser)
    }

    @Test
    fun aPairingCodeReadsBackAndRefusesWhatIsMissing() {
        val code = PairingCode(ByteArray(32) { 1 }, ByteArray(32) { 2 }, "lap;top=ü,1", ByteArray(51) { 5 }, listOf("192.168.1.5", "fe80::1"))
        val text = code.format()
        assertTrue(text.startsWith("unitool-pair:1:"))
        val back = assertNotNull(PairingCode.parse(text))
        assertEquals("lap;top=ü,1", back.name)
        assertContentEquals(code.computerKey, back.computerKey)
        assertContentEquals(code.psk, back.psk)
        assertContentEquals(code.sshKey, back.sshKey)
        assertEquals(listOf("192.168.1.5", "fe80::1"), back.addresses)
        assertEquals(code.pairingInstance, back.pairingInstance)
        assertEquals(16, code.pairingInstance.length)
        // Unknown fields are skipped; missing or broken needed ones are not.
        assertNotNull(PairingCode.parse("$text;srv=https%3A%2F%2Fx"))
        assertNull(PairingCode.parse(text.replace(";ssh=", ";xsh=")))
        assertNull(PairingCode.parse(text.replace(";name=", ";nom=")))
        assertNull(PairingCode.parse(text.replace("unitool-pair:1:", "unitool-pair:2:")))
        assertNull(PairingCode.parse("unitool-pair:1:AAAA;name=x;ssh=AAAA"))
        assertNull(PairingCode.parse(text.replace(";name=", ";name=%ZZ")))
        assertTrue("redacted" in code.toString() && "AgIC" !in code.toString())
    }

    @Test
    fun theBoxKeepsThePasswordAsBytesAndEscapesEverything() {
        val request = LinkMessage.encode(LinkMessage.PasswordRequest(ByteArray(16), "https://a.example", "x", 1))
        val password = "p\"a\\s\ns\u0001ё😀".encodeToByteArray()
        val plain = PasswordBox.plaintext(request, "https://a.example", "o\"cto", password)
        val opened = assertNotNull(PasswordBox.open(plain))
        assertEquals(PasswordBox.requestDigest(request), opened.request)
        assertEquals(64, opened.request.length)
        assertEquals("https://a.example", opened.origin)
        assertEquals("o\"cto", opened.username)
        assertContentEquals(password, opened.password.bytes)
        opened.close()
        assertTrue(opened.password.closed)
        assertNull(PasswordBox.open("{\"req\":\"x\",\"origin\":\"y\",\"username\":\"z\"}".encodeToByteArray()))
    }

    @Test
    fun flatJsonIsStrict() {
        assertEquals(mapOf("a" to "b", "n" to "12", "t" to "true"), FlatJson.parseText(" {\"a\" : \"b\", \"n\":12,\"t\":true} ".encodeToByteArray()))
        assertEquals("é😀", FlatJson.parseText("{\"a\":\"\\u00e9\\ud83d\\ude00\"}".encodeToByteArray())!!["a"])
        assertEquals(emptyMap(), FlatJson.parseText("{}".encodeToByteArray()))
        for (bad in listOf("", "[]", "{\"a\":{}}", "{\"a\":[1]}", "{\"a\":\"b\"} x", "{\"a\":\"b\",\"a\":\"c\"}", "{\"a\":\"\\ud800\"}", "{\"a\":\"x\ny\"}", "{\"a\":nope}", "{a:1}")) {
            assertNull(FlatJson.parse(bad.encodeToByteArray()), bad)
        }
    }

    @Test
    fun theWriterPutsCommasWhereTheyGo() {
        val w = JsonWriter().beginObject()
            .name("type").string("devices")
            .name("phones").beginArray()
            .beginObject().name("id").string("a").name("n").number(1).endObject()
            .beginObject().name("id").string("b").name("ok").bool(false).name("z").nul().endObject()
            .endArray()
            .name("empty").beginArray().endArray()
            .endObject()
        assertEquals("""{"type":"devices","phones":[{"id":"a","n":1},{"id":"b","ok":false,"z":null}],"empty":[]}""", w.toByteArray().decodeToString())
    }

    @Test
    fun mdnsAnswersReadBackIntoServices() {
        val type = LinkWire.SERVICE_TYPE
        val instance = "0123456789abcdef"
        val full = Mdns.instanceName(instance, type)
        val bytes = Mdns.response(
            answers = listOf(Mdns.Record.Ptr(Mdns.serviceName(type), 120, full)),
            additional = listOf(
                Mdns.Record.Srv(full, 120, LinkWire.PORT, "phone-1.local"),
                Mdns.Record.Txt(full, 120, listOf("v=1")),
                Mdns.Record.A("phone-1.local", 120, byteArrayOf(192.toByte(), 168.toByte(), 1, 20)),
            ),
        )
        val message = assertNotNull(Mdns.parse(bytes))
        assertTrue(message.isResponse)
        val services = Mdns.services(message.records, type)
        assertEquals(1, services.size)
        assertEquals(instance, services[0].instance)
        assertEquals(LinkWire.PORT, services[0].port)
        assertContentEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), services[0].addresses.single())
        val query = assertNotNull(Mdns.parse(Mdns.query(listOf(Mdns.Question(Mdns.serviceName(type), Mdns.TYPE_PTR)))))
        assertEquals("_unitool._tcp.local", query.questions.single().name)
        assertTrue(!query.isResponse)
    }

    @Test
    fun mdnsFollowsCompressionPointersAndRefusesLoops() {
        // A PTR answer whose target points back into the question's name: how real responders write it.
        val packet = hex(
            "0000 8400 0001 0001 0000 0000" +
                "085f756e69746f6f6c 045f746370 056c6f63616c 00 000c 0001" +
                "c00c 000c 0001 00000078 0005 026162 c00c",
        )
        val message = assertNotNull(Mdns.parse(packet))
        val ptr = assertIs<Mdns.Record.Ptr>(message.records.single())
        assertEquals("ab._unitool._tcp.local", ptr.target)
        val loop = hex("0000 8400 0001 0000 0000 0000 c00c 000c 0001")
        assertNull(Mdns.parse(loop))
    }

    @Test
    fun pairingThenASessionInMemory() {
        // The computer's side.
        val computer = NoiseKeyPair.generate()
        val psk = secureRandomBytes(32)
        val code = PairingCode(computer.public, psk, "laptop", ByteArray(51) { 4 })
        // The phone scans it.
        val scanned = assertNotNull(PairingCode.parse(code.format()))
        val phone = NoiseKeyPair.generate()
        val linkId = secureRandomBytes(8)

        val c = NoiseHandshake.initiator(NoisePattern.XXpsk3, computer, psk = psk, prologue = LinkWire.prologue(LinkWire.Mode.Pair))
        val p = NoiseHandshake.responder(NoisePattern.XXpsk3, phone, psk = scanned.psk, prologue = LinkWire.prologue(LinkWire.Mode.Pair))
        assertNotNull(p.read(c.write()))
        assertNotNull(c.read(p.write()))
        assertNotNull(p.read(c.write()))
        assertContentEquals(scanned.computerKey, p.remoteStatic)
        val ct = c.split()
        val pt = p.split()
        val welcome = LinkMessage.decode(ct.open(pt.seal(LinkMessage.encode(LinkMessage.Welcome("Pixel", linkId))))!!) as LinkMessage.Welcome
        assertContentEquals(linkId, welcome.linkId)
        assertIs<LinkMessage.Paired>(LinkMessage.decode(pt.open(ct.seal(LinkMessage.encode(LinkMessage.Paired)))!!))
        val phoneKey = ct.remoteStatic

        // Later: a session. The phone has two computers and finds the caller by trying both.
        val other = NoiseKeyPair.generate()
        val first = NoiseHandshake.initiator(NoisePattern.KK, computer, phoneKey, prologue = LinkWire.prologue(LinkWire.Mode.Session)).let { hs ->
            hs to hs.write()
        }
        val known = listOf(other.public, scanned.computerKey)
        val match = known.firstNotNullOf { candidate ->
            val hs = NoiseHandshake.responder(NoisePattern.KK, phone, candidate, prologue = LinkWire.prologue(LinkWire.Mode.Session))
            if (hs.read(first.second) != null) candidate to hs else null
        }
        assertContentEquals(computer.public, match.first)
        assertNotNull(first.first.read(match.second.write()))
        val session = first.first.split()
        val phoneSide = match.second.split()
        val req = LinkMessage.PasswordRequest(secureRandomBytes(16), "https://github.com", "Chromium", 5)
        val got = LinkMessage.decode(phoneSide.open(session.seal(LinkMessage.encode(req)))!!) as LinkMessage.PasswordRequest
        assertEquals("https://github.com", got.origin)

        // A session in the wrong mode's prologue does not open.
        val wrong = NoiseHandshake.initiator(NoisePattern.KK, computer, phoneKey, prologue = LinkWire.prologue(LinkWire.Mode.Pair)).write()
        assertNull(NoiseHandshake.responder(NoisePattern.KK, phone, computer.public, prologue = LinkWire.prologue(LinkWire.Mode.Session)).read(wrong))
    }
}
