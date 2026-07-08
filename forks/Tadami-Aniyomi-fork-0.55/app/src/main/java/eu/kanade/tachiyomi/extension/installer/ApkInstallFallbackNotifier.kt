package eu.kanade.tachiyomi.extension.installer

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tadami.aurora.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder

class ApkInstallFallbackNotifier(
    private val context: Context,
) {
    fun show(suggestion: ApkInstallFallbackSuggestion) {
        val content = buildString {
            append("PackageInstaller failed: ${suggestion.reason}. ")
            append("Open extensions and retry with ")
            append(suggestion.suggestedBackends.joinToString { it.name })
            append(".")
        }
        val pendingIntent = when (suggestion.kind) {
            ApkExtensionKind.ANIME -> NotificationReceiver.openAnimeExtensionsPendingActivity(context)
            ApkExtensionKind.MANGA,
            ApkExtensionKind.NOVEL_KOTLIN,
            -> NotificationReceiver.openExtensionsPendingActivity(context)
        }
        val notification = context.notificationBuilder(Notifications.CHANNEL_EXTENSIONS_UPDATE) {
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentTitle("Extension install failed")
            setContentText(content)
            setStyle(NotificationCompat.BigTextStyle().bigText(content))
            setContentIntent(pendingIntent)
            setAutoCancel(true)
        }.build()
        NotificationManagerCompat.from(context).notify(notificationId(suggestion), notification)
    }

    private fun notificationId(suggestion: ApkInstallFallbackSuggestion): Int {
        return Notifications.ID_EXTENSION_INSTALLER - suggestion.packageName.hashCode().mod(10_000)
    }
}
