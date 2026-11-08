package com.homebabymonitor.ui

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.homebabymonitor.core.CrashReportFormatter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object CrashDiagnostics {
    private const val MAX_TRACE_BYTES = 8 * 1024 * 1024
    private const val RECENT_MILLIS = 14L * 24L * 60L * 60L * 1000L

    private fun latestCrash(context: Context): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activity.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .asSequence()
                .filter { it.reason == ApplicationExitInfo.REASON_CRASH
                    || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || it.reason == ApplicationExitInfo.REASON_ANR }
                .filter { it.timestamp in (System.currentTimeMillis() - RECENT_MILLIS)..System.currentTimeMillis() }
                .maxByOrNull { it.timestamp }
        }.getOrNull()
    }

    fun available(context: Context): Boolean = latestCrash(context) != null

    fun prepareReport(context: Context): Uri? {
        val crash = latestCrash(context) ?: return null
        val folder = File(context.cacheDir, "diagnostics")
        check(folder.isDirectory || folder.mkdirs()) { "Не удалось создать каталог диагностики" }
        val archive = File(folder, "HomeBabyMonitor-crash.zip")
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "неизвестна"

        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("summary.txt"))
            val summary = CrashReportFormatter.summary(crash.reason, crash.status, crash.timestamp, version) +
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Описание: ${crash.description?.take(4096).orEmpty()}\n"
            zip.write(summary.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    crash.traceInputStream?.use { trace ->
                        zip.putNextEntry(ZipEntry("native-tombstone.pb"))
                        val buffer = ByteArray(8192)
                        var remaining = MAX_TRACE_BYTES
                        while (remaining > 0) {
                            val n = trace.read(buffer, 0, minOf(buffer.size, remaining))
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            remaining -= n
                        }
                        zip.closeEntry()
                    }
                }
            }
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", archive)
    }

    fun shareReport(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Отчёт об ошибке", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить отчёт об ошибке"))
    }
}
