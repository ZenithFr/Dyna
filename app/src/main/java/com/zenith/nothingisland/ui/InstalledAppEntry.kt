package com.zenith.nothingisland.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class InstalledAppEntry(val packageName: String, val label: String)

fun loadInstalledAppsExcluding(context: Context, excluded: Set<String>): List<InstalledAppEntry> {
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcherIntent, 0)
    }
    return activities
        .asSequence()
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg in excluded || pkg == context.packageName) return@mapNotNull null
            val label = runCatching {
                info.activityInfo.applicationInfo.let { pm.getApplicationLabel(it).toString() }
            }.getOrElse { info.loadLabel(pm).toString() }
            InstalledAppEntry(packageName = pkg, label = label)
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .toList()
}
