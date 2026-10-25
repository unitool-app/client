package cx.m42.services.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import cx.m42.unitool.kit.link.LinkWire
import kotlinx.coroutines.CoroutineScope

/**
 * The phone's listener on Android (D538, D552): the JVM server socket, the names announced through
 * `NsdManager`, and a Wi-Fi `MulticastLock` for exactly as long as the listener is open — without it
 * many phones drop the queries a computer sends to find them, and with it held longer they drain the
 * battery. `CHANGE_WIFI_MULTICAST_STATE` is a normal permission: no prompt.
 */
public class AndroidLocalLink(context: Context) : LocalLink {
    private val app = context.applicationContext

    override fun open(scope: CoroutineScope, names: List<String>, accept: suspend (LinkPipe) -> Unit): LinkListener =
        SocketListener(scope, names, accept, { port, current -> NsdAnnouncer(app, port, current) })
}

private class NsdAnnouncer(context: Context, private val port: Int, names: List<String>) : Announcer {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("unitool-link")
        ?.apply { setReferenceCounted(false); acquire() }
    private val registered = LinkedHashMap<String, NsdManager.RegistrationListener>()

    init {
        rename(names)
    }

    @Synchronized
    override fun rename(names: List<String>) {
        (registered.keys - names.toSet()).forEach { name -> registered.remove(name)?.let { runCatching { nsd.unregisterService(it) } } }
        (names - registered.keys).forEach { name ->
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            val info = NsdServiceInfo().apply {
                serviceName = name
                serviceType = LinkWire.SERVICE_TYPE
                setPort(port)
                setAttribute("v", LinkWire.VERSION.toString())
            }
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { registered[name] = listener }
        }
    }

    @Synchronized
    override fun close() {
        registered.values.forEach { runCatching { nsd.unregisterService(it) } }
        registered.clear()
        runCatching { lock?.release() }
    }
}
