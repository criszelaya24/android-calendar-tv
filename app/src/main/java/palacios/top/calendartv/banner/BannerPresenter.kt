package palacios.top.calendartv.banner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import palacios.top.calendartv.R
import palacios.top.calendartv.data.Settings
import palacios.top.calendartv.ui.MainActivity

@Serializable
data class BannerItem(
    val title: String,
    val subtitle: String,
    val color: String,
    val description: String = "",
    /** Account the event belongs to: photo file if we have one, else a colored circle with [accountInitial]. */
    val avatarPath: String = "",
    val accountInitial: String = "",
    val avatarColor: String = "",
)

@Serializable
data class BannerRequest(
    val items: List<BannerItem>,
    val soundId: String,
    val volume: Int,
    val seconds: Int,
)

/** Shows reminders: the on-screen banner over any app, plus a regular notification for the TV's panel. */
object BannerPresenter {

    private const val CHANNEL = "reminders"

    fun canDrawOverlay(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    fun show(context: Context, items: List<BannerItem>, settings: Settings) {
        if (items.isEmpty()) return
        postNotifications(context, items)
        if (!canDrawOverlay(context)) return
        val request = BannerRequest(items, settings.soundId, settings.volume, settings.bannerSeconds)
        context.startService(
            Intent(context, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_REQUEST, Json.encodeToString(request)),
        )
    }

    private fun postNotifications(context: Context, items: List<BannerItem>) {
        val manager = NotificationManagerCompat.from(context)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null) }, // the sound is played by SoundPlayer so it can be customised
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        items.forEachIndexed { index, item ->
            manager.notify(
                (System.currentTimeMillis() % 1_000_000).toInt() + index,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(item.title)
                    .setContentText(item.subtitle)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
