package app.marquee.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Which screensaver Android runs when the TV sits idle (its "screensaver_components" setting), and switching it.
 *
 * Google TV's own screensaver (Ambient mode: Google Photos, art, …) is chosen in Google TV's settings, but if
 * Marquee's Ambient was ever made the system screensaver (an optional step in the README), Android keeps running
 * Marquee's however Google TV's settings are set. Marquee can put it right when it has the "write secure
 * settings" permission (granted once over adb); otherwise it shows the one command that does it.
 */
object Screensaver {
    private const val KEY = "screensaver_components"
    private const val ENABLED = "screensaver_enabled"
    const val GRANT_COMMAND = "adb shell pm grant app.marquee.tv android.permission.WRITE_SECURE_SETTINGS"

    data class Dream(val component: ComponentName, val label: String)

    /** Every screensaver installed on the TV. */
    fun installed(ctx: Context): List<Dream> = runCatching {
        val pm = ctx.packageManager
        pm.queryIntentServices(Intent("android.service.dreams.DreamService"), PackageManager.GET_META_DATA).map {
            Dream(ComponentName(it.serviceInfo.packageName, it.serviceInfo.name), it.loadLabel(pm).toString())
        }
    }.getOrDefault(emptyList())

    /** Google TV's own screensaver (Ambient mode). */
    fun google(ctx: Context): Dream? = installed(ctx).let { all ->
        all.firstOrNull { it.component.packageName.contains("dream", true) && it.component.packageName.startsWith("com.google") }
            ?: all.firstOrNull { it.component.packageName.startsWith("com.google") }
    }

    fun marquee(ctx: Context) = ComponentName(ctx.packageName, AmbientDreamService::class.java.name)

    /** What Android will run when idle, or null if Android doesn't let Marquee read it. */
    fun current(ctx: Context): ComponentName? = runCatching {
        Settings.Secure.getString(ctx.contentResolver, KEY)?.split(',')?.firstOrNull()?.let { ComponentName.unflattenFromString(it) }
    }.getOrNull()

    fun readable(ctx: Context) = runCatching { Settings.Secure.getString(ctx.contentResolver, KEY); true }.getOrDefault(false)

    fun canChange(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Makes [dream] the system screensaver (and makes sure screensavers are on). */
    fun use(ctx: Context, dream: ComponentName): Boolean = runCatching {
        val cr = ctx.contentResolver
        Settings.Secure.putString(cr, KEY, dream.flattenToString()) && Settings.Secure.putInt(cr, ENABLED, 1)
    }.getOrDefault(false)

    /** The adb command that makes [dream] the system screensaver, for when Marquee can't change it itself. */
    fun command(dream: ComponentName) = "adb shell settings put secure $KEY ${dream.flattenToShortString()}"

    /** Plain-English name for a screensaver component. */
    fun describe(ctx: Context, c: ComponentName?): String = when {
        c == null -> "unknown"
        c.packageName == ctx.packageName -> "Marquee Ambient"
        else -> installed(ctx).firstOrNull { it.component == c }?.label?.let { "$it (${if (c.packageName.startsWith("com.google")) "Google TV" else c.packageName})" }
            ?: c.flattenToShortString()
    }
}
