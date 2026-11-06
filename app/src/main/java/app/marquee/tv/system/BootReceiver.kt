package app.marquee.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.marquee.tv.MainActivity
import app.marquee.tv.MarqueeApp

/** Opens Marquee after the TV boots, so the stock home screen never shows. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as MarqueeApp
        SelfHeal.ensureAccessibility(context)
        if (!app.store.value.takeoverHome) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
