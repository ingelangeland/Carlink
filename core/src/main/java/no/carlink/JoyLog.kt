package no.carlink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/** Joystick-opptak fra bilskjermen: lagres på telefonen og kan deles (f.eks. med Claude). */
object JoyLog {
    private const val NOTIF = 44

    fun file(ctx: Context) = File(File(ctx.filesDir, "joystick").apply { mkdirs() }, "CarLink-joystick.txt")

    fun received(ctx: Context, name: String, text: String) {
        if (text.isBlank()) return
        runCatching { file(ctx).writeText(text) }.onFailure { return }
        val pi = PendingIntent.getActivity(ctx, 5, shareIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("joy", "Joystick recording", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(NOTIF, NotificationCompat.Builder(ctx, "joy")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Joystick recording from the car")
                .setContentText("Tap to share $name")
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    fun exists(ctx: Context) = file(ctx).let { it.exists() && it.length() > 0 }

    fun shareIntent(ctx: Context): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file(ctx))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "CarLink joystick recording")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        return Intent.createChooser(send, "Share the joystick recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
