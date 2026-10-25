package cx.m42.unitool.cli.browser

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

public actual fun systemBrowser(): BrowserSystem = JvmBrowserSystem()

/**
 * The same computer on java.net, for the JVM tests: the host they run talks to a phone on a real
 * socket. [input] and [output] are the native messaging pipe; a test hands its own.
 */
public class JvmBrowserSystem(
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
    private val clock: () -> Long = System::currentTimeMillis,
) : BrowserSystem {
    override fun now(): Long = clock()

    override fun hostname(): String = runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrDefault("computer")

    override fun executable(): String? = System.getProperty("unitool.binary")

    override fun mkdirs(path: String) {
        var at = File("/")
        path.split('/').filter { it.isNotEmpty() }.forEach { part ->
            at = File(at, part)
            if (!at.exists() && at.mkdir()) runCatching { Files.setPosixFilePermissions(at.toPath(), PosixFilePermissions.fromString("rwx------")) }
        }
    }

    override fun writeExecutable(path: String, bytes: ByteArray) {
        File(path).writeBytes(bytes)
        Files.setPosixFilePermissions(File(path).toPath(), PosixFilePermissions.fromString("rwx------"))
    }

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun localAddresses(): List<String> = interfaces().flatMap { nic ->
        nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { it.hostAddress }
    }.distinct()

    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
    }.getOrDefault(emptyList())

    override fun connect(address: String, port: Int, timeoutMillis: Int): Connection? = runCatching {
        val socket = Socket()
        socket.connect(InetSocketAddress(address, port), timeoutMillis)
        object : Connection {
            override val remote = "$address:$port"
            override fun write(bytes: ByteArray) {
                socket.getOutputStream().apply { write(bytes); flush() }
            }
            override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
                socket.soTimeout = timeoutMillis.coerceAtLeast(1)
                return try {
                    socket.getInputStream().read(buffer)
                } catch (_: SocketTimeoutException) {
                    0
                } catch (_: Exception) {
                    -1
                }
            }
            override fun close() = socket.close()
        }
    }.getOrNull()

    override fun multicastQuery(query: ByteArray, millis: Int): List<Datagram> {
        val group = InetAddress.getByName("224.0.0.251")
        val sockets = ArrayList<DatagramSocket>()
        runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(5353))
                interfaces().forEach { runCatching { joinGroup(InetSocketAddress(group, 5353), it) } }
            }
        }.getOrNull()?.let { sockets += it }
        runCatching { DatagramSocket(0) }.getOrNull()?.let { sockets += it }
        sockets.forEach { runCatching { it.send(DatagramPacket(query, query.size, group, 5353)) } }
        val out = java.util.Collections.synchronizedList(ArrayList<Datagram>())
        val deadline = clock() + millis
        val threads = sockets.map { s ->
            Thread {
                val buffer = ByteArray(9000)
                while (true) {
                    val left = (deadline - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    s.soTimeout = left
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                        out += Datagram(packet.address.hostAddress, buffer.copyOf(packet.length))
                    } catch (_: Exception) {
                        break
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(millis + 500L) }
        sockets.forEach { it.close() }
        return out.toList()
    }

    override val stdio: Stdio = object : Stdio {
        override fun read(buffer: ByteArray): Int = input.read(buffer).let { if (it <= 0) -1 else it }
        override fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }
        override fun readable(timeoutMillis: Int): Boolean {
            val end = System.currentTimeMillis() + timeoutMillis
            while (true) {
                if (input.available() > 0 || ended()) return true
                if (System.currentTimeMillis() >= end) return false
                Thread.sleep(10)
            }
        }
        private fun ended(): Boolean = (input as? EndAware)?.ended == true
    }
}

/** A test's pipe says when the browser "closed the port": `available()` alone cannot. */
public interface EndAware {
    public val ended: Boolean
}
