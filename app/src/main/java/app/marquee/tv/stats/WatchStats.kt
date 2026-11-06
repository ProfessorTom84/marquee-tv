package app.marquee.tv.stats

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class AppShare(val label: String, val packageName: String, val minutes: Long, val percent: Int)

data class WeekStats(
    val totalMinutes: Long,
    val lastWeekMinutes: Long,
    val perDayMinutes: List<Long>, // Monday..Sunday
    val todayIndex: Int,
    val top: List<AppShare>,
) {
    val changePercent: Int? get() = if (lastWeekMinutes <= 0) null else (((totalMinutes - lastWeekMinutes) * 100) / lastWeekMinutes).toInt()
}

/** Screen time per app from Android's usage stats. Nothing leaves the device. */
class WatchStats(private val context: Context) {
    private val ignore = setOf(
        context.packageName, "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher", "com.android.systemui", "com.android.tv.settings",
        "com.google.android.tvrecommendations", "com.google.android.katniss", "android",
    )

    fun hasPermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun thisWeek(): WeekStats? {
        if (!hasPermission()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        fun usage(from: Long, to: Long): Map<String, Long> =
            usm.queryAndAggregateUsageStats(from, to)
                .filterKeys { it !in ignore }
                .mapValues { it.value.totalTimeInForeground / 60000 }
                .filterValues { it > 0 }

        val perDay = (0..6).map { i ->
            val d = monday.plusDays(i.toLong())
            if (d.isAfter(today)) 0L else usage(ms(d), ms(d.plusDays(1)).coerceAtMost(System.currentTimeMillis())).values.sum()
        }
        val week = usage(ms(monday), System.currentTimeMillis())
        val lastWeek = usage(ms(monday.minusWeeks(1)), ms(monday)).values.sum()
        val total = week.values.sum()
        val pm = context.packageManager
        val top = week.entries.sortedByDescending { it.value }.take(3).map { (pkg, mins) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            AppShare(label, pkg, mins, if (total > 0) ((mins * 100) / total).toInt() else 0)
        }
        return WeekStats(total, lastWeek, perDay, today.dayOfWeek.value - 1, top)
    }

    companion object {
        fun formatMinutes(m: Long) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
        @Suppress("unused")
        fun isInstalled(pm: PackageManager, pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }
}
