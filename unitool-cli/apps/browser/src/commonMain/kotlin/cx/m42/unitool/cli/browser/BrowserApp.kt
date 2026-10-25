package cx.m42.unitool.cli.browser

import cx.m42.unitool.cli.CliApp
import cx.m42.unitool.cli.Command
import cx.m42.unitool.cli.Io
import cx.m42.unitool.cli.fail
import cx.m42.unitool.cli.repeated
import cx.m42.unitool.cli.required
import cx.m42.unitool.cli.write
import cx.m42.unitool.kit.json.JsonWriter
import cx.m42.unitool.kit.link.PairingCode
import cx.m42.unitool.kit.qr.ErrorCorrection
import cx.m42.unitool.kit.qr.Qr
import cx.m42.unitool.kit.qr.QrTerminal

/** The native messaging host's name, and the Firefox extension's id (D554). */
public const val HOST_NAME: String = "cx.m42.unitool"
public const val FIREFOX_EXTENSION: String = "browser@unitool.m42.cx"

/** The extension's id in Chromium browsers until a store gives it another (D567): from the key in its manifest. */
public const val CHROME_EXTENSION: String = "omcnlonedghinfecceojlapofcjfcpoi"

/**
 * `unitool browser`: passwords from the phone into this computer's browser (.ai-notes/passwords-browser).
 * The extension starts `host`; the other commands are for the person: install the host, pair a
 * phone, see and forget phones.
 */
public class BrowserApp internal constructor(
    private val system: () -> BrowserSystem,
    /** Sees each pairing code the terminal shows — the tests' camera; nothing else listens. */
    shown: (PairingCode) -> Unit,
) : CliApp(id = "browser", title = "Passwords in the browser", summary = "Fill passwords from the phone into this computer's browser") {

    public constructor(system: () -> BrowserSystem = ::systemBrowser) : this(system, {})

    override val commands: List<Command> = listOf(
        InstallCommand(system),
        PairCommand(system, shown),
        DevicesCommand(system),
        ForgetCommand(system),
        HostCommand(system),
    )
}

internal class InstallCommand(private val system: () -> BrowserSystem) :
    Command("install", "Let Chrome, Chromium, Brave, Edge and Firefox start the Unitool host") {
    private val extensionIds by option("--extension-id", help = "another Chromium extension id to allow (a store's)", metavar = "ID").repeated()
    private val all by flag("--all", help = "write for every browser, installed or not")

    override fun run(io: Io): Int {
        val sys = system()
        val exe = sys.executable() ?: fail("cannot tell where this unitool is; run it by its full path")
        val home = io.env("HOME") ?: fail("HOME is not set")
        val config = LinkStore.configHome(io)
        // The browser passes no arguments of ours, so it starts a two-line wrapper (D566).
        val dataDir = LinkStore.dataHome(io) + "/unitool"
        val wrapper = "$dataDir/browser-host"
        sys.mkdirs(dataDir)
        sys.writeExecutable(
            wrapper,
            "#!/bin/sh\n# Written by `unitool browser install` (.ai-notes/passwords-browser D566): the browser starts this.\nexec ${quote(exe)} browser host \"\$@\"\n".encodeToByteArray(),
        )
        val ids = (listOf(CHROME_EXTENSION) + extensionIds).distinct()
        ids.forEach { if (!Regex("[a-p]{32}").matches(it)) fail("--extension-id: '$it' is not a Chromium extension id (32 letters a–p)") }
        val chromium = listOf(
            "Google Chrome" to "$config/google-chrome",
            "Chromium" to "$config/chromium",
            "Brave" to "$config/BraveSoftware/Brave-Browser",
            "Microsoft Edge" to "$config/microsoft-edge",
        )
        var written = 0
        for ((label, dir) in chromium) {
            if (!all && !sys.isDirectory(dir)) continue
            val path = "$dir/NativeMessagingHosts/$HOST_NAME.json"
            sys.mkdirs("$dir/NativeMessagingHosts")
            io.writeFile(path, manifest(wrapper) { name("allowed_origins").beginArray().apply { ids.forEach { string("chrome-extension://$it/") } }.endArray() })
            io.say("$label: $path")
            written++
        }
        val snapFirefox = sys.isDirectory("$home/snap/firefox") || sys.isDirectory("/snap/firefox")
        if (all || sys.isDirectory("$home/.mozilla") || snapFirefox) {
            // Snap's Firefox reads the same place through the desktop portal (00-recon, D555).
            val path = "$home/.mozilla/native-messaging-hosts/$HOST_NAME.json"
            sys.mkdirs("$home/.mozilla/native-messaging-hosts")
            io.writeFile(path, manifest(wrapper) { name("allowed_extensions").beginArray().string(FIREFOX_EXTENSION).endArray() })
            io.say("Firefox: $path")
            written++
        }
        if (written == 0) {
            io.say("No browser found under $config or ~/.mozilla. Start the browser once, or write for every one: unitool browser install --all")
            return 1
        }
        io.say("The browser starts $wrapper. Moved this unitool? Run install again.")
        if (snapFirefox) io.say("Firefox from snap asks once whether it may start Unitool — allow it.")
        return 0
    }

    private fun manifest(wrapper: String, allowed: JsonWriter.() -> Unit): ByteArray {
        val w = JsonWriter().beginObject()
            .name("name").string(HOST_NAME)
            .name("description").string("Unitool: passwords from the phone")
            .name("path").string(wrapper)
            .name("type").string("stdio")
        w.allowed()
        w.endObject()
        return w.toByteArray() + "\n".encodeToByteArray()
    }

    private fun quote(word: String): String = "'" + word.replace("'", "'\\''") + "'"
}

internal class PairCommand(private val system: () -> BrowserSystem, private val shown: (PairingCode) -> Unit) : Command("pair", "Pair a phone: a QR code here, Unitool → Passwords → Computers there") {
    private val key by option("--key", help = "the SSH key answers are encrypted to; without it, ~/.ssh/id_ed25519 or ~/.ssh/id_rsa", metavar = "FILE")
    private val label by option("--name", help = "this computer's name on the phone; without it, the host name", metavar = "NAME")

    override fun run(io: Io): Int {
        val sys = system()
        val store = LinkStore(io, sys)
        val start = Pairing.start(io, sys, store, key, label)
        val uri = start.code.format()
        val qr = Qr.encode(uri, ErrorCorrection.L) ?: fail("the pairing code does not fit a QR code")
        io.say(QrTerminal.ansi(qr))
        shown(start.code)
        io.say("On the phone: Unitool → Passwords → Computers → Pair a computer, and scan this.")
        io.say("The phone will show \"${start.code.name}\" and this key: ${start.sshKey.fingerprint} (${start.sshPath})")
        io.say("Waiting for the phone, up to 5 minutes…")
        val result = PhoneClient(sys) { io.say(it) }.pair(start.code, start.computer, sys.now() + PhoneClient.PAIR_MILLIS, { false }) {
            Pairing.keep(store, start, it)
        }
        start.code.psk.fill(0)
        return when (result) {
            is PhoneClient.Paired.Ok -> {
                io.say("Paired: ${result.phone.name}")
                0
            }
            PhoneClient.Paired.NotFound -> fail("no phone came within 5 minutes. Is it in the same network, with Passwords open?")
            PhoneClient.Paired.Refused -> fail("the phone did not accept the pairing; scan the code again")
            PhoneClient.Paired.Cancelled -> 130
        }
    }
}

internal class DevicesCommand(private val system: () -> BrowserSystem) : Command("devices", "List the paired phones and the SSH key in use") {
    override fun run(io: Io): Int {
        val store = LinkStore(io, system())
        val phones = store.phones()
        if (phones.isEmpty()) io.say("No phone is paired. Pair one: unitool browser pair")
        phones.forEach { io.write("${it.id}  ${it.name}  paired ${isoDate(it.pairedAt)}\n") }
        store.sshKeyPath()?.let { path ->
            val fingerprint = (SshKeyChoice.check(io, path) as? SshKeyChoice.Result.Ok)?.publicKey?.fingerprint ?: "cannot be read"
            io.say("Answers are encrypted to $path ($fingerprint)")
        }
        return 0
    }
}

internal class ForgetCommand(private val system: () -> BrowserSystem) : Command("forget", "Forget a paired phone") {
    private val id by argument("ID", "the phone's id from `unitool browser devices`").required()

    override fun run(io: Io): Int {
        val store = LinkStore(io, system())
        val phones = store.phones()
        val gone = phones.filter { it.id == id }
        if (gone.isEmpty()) fail("no phone with id $id; see unitool browser devices")
        store.savePhones(phones - gone.toSet())
        io.say("Forgotten: ${gone.joinToString { it.name }}. On the phone, Passwords → Computers → Forget this computer.")
        return 0
    }
}

internal class HostCommand(private val system: () -> BrowserSystem) : Command("host", "The native messaging host the extension talks to (started by the browser)") {
    // The browser passes the extension's origin (Chromium) or the manifest's path and the add-on id
    // (Firefox). Which extension may start us is the manifest's allowed_* list; nothing is taken from here.
    private val caller by argument("CALLER", "the extension's origin (Chromium) or the manifest's path (Firefox); not used")
    private val addon by argument("ADDON", "the add-on id (Firefox); not used")

    override fun run(io: Io): Int = NativeHost(io, system()).run()
}

internal fun isoDate(millis: Long): String {
    var days = millis / 86_400_000
    // Civil from days (Howard Hinnant), enough for a date in a list.
    days += 719_468
    val era = (if (days >= 0) days else days - 146_096) / 146_097
    val doe = days - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
