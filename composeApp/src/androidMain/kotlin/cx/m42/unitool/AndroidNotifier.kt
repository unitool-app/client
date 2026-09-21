package cx.m42.unitool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import cx.m42.superizer.host.push.Notifier

/**
 * Draws the notification a non-silent push asks for.
 *
 * The library routes and decides; it does not draw, because drawing is the one part of push that
 * has no shared implementation — `NotificationManager` exists on exactly one of the three targets.
 * So [Notifier] is a hole in the host builder and this is what Unitool puts in it.
 *
 * The [link] it is handed is already a `unitool://…` URL that the route knows how to open, which is
 * why the tap intent below is an ordinary `VIEW` and needs no push-specific handling on the other
 * side: a notification tap and a link from anywhere else on the device arrive by the same door.
 */
internal class AndroidNotifier(private val context: Context) : Notifier {

    override fun show(title: String?, body: String?, link: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(manager)

        // FLAG_IMMUTABLE is required from API 31 and correct everywhere: nothing that receives this
        // intent has any business rewriting the link inside it.
        val tap = PendingIntent.getActivity(
            context,
            link.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(link)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // The framework builder rather than NotificationCompat: this source set is Android-only and
        // minSdk is 24, so the compat layer would be a dependency that only renames things.
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title ?: context.getString(R.string.app_name))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        // The link as the tag, so a second push about the same screen replaces the first rather
        // than stacking a duplicate the reader has to dismiss twice.
        manager.notify(link, ID, notification)
    }

    /**
     * Channels are required from API 26 and creating one that already exists is a no-op, so this is
     * cheaper than remembering whether it has been done.
     */
    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.notification_channel_name),
                // Not HIGH: nothing this app has to say is worth taking over the screen. A
                // calculator that interrupts is a calculator that gets its notifications turned off.
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private companion object {
        const val CHANNEL = "unitool.general"
        const val ID = 1
    }
}
