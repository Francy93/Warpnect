package io.warpnect.platform.diagnostics

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import io.warpnect.diagnostics.report.DiagnosticReportEnvironment
import io.warpnect.diagnostics.report.ReportDestination
import io.warpnect.diagnostics.report.ReportPlatformMetadata
import io.warpnect.diagnostics.report.ReportRuntimeMetadata

object AndroidReportSupport {
    fun environment(context: Context): DiagnosticReportEnvironment {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        return DiagnosticReportEnvironment(
            runtime = ReportRuntimeMetadata(
                packageInfo.versionName ?: "unknown",
                versionCode,
            ),
            platform = ReportPlatformMetadata(
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.firstOrNull(),
                Build.MANUFACTURER,
                Build.MODEL,
            ),
        )
    }
}

class AndroidReportDestination(
    private val resolver: ContentResolver,
    private val uri: android.net.Uri,
) : ReportDestination {
    override fun openForWrite() = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
        ?: runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
}
