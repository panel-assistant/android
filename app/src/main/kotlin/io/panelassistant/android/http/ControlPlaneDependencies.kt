package io.panelassistant.android.http

import android.content.Context
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.control.Su
import io.panelassistant.android.migration.IdentityMigrationSurface
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.InstallProgress
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun controlPlaneDependencies(
    appContext: Context,
    config: Config,
    scope: CoroutineScope,
    pendingApks: PendingUploadStore,
    identityMigration: IdentityMigrationSurface,
    playAudio: (String) -> Boolean,
    onInstallComponent: (String, String, String) -> Boolean,
    buildBackupArtifact: (CompanionBackupRequest, String) -> PanelBackup.Artifact,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
): ControlPlaneRouteDependencies = ControlPlaneRouteDependencies(
    playAudio = playAudio,
    installComponent = onInstallComponent,
    installedComponentVersion = { name ->
        when (name) {
            "paneld" -> Config.VERSION
            "companion" -> CompanionInstaller.installedPkg(appContext)?.let { pkg ->
                AppInstaller.installedVersion(appContext, pkg).takeIf { it.isNotBlank() }
            }
            "webview" -> runCatching {
                android.webkit.WebView.getCurrentWebViewPackage()?.versionName
            }.getOrNull()
            else -> null
        }
    },
    buildBackup = { request, passphrase ->
        withContext(Dispatchers.IO) {
            buildBackupArtifact(request, passphrase)
        }
    },
    backupFileStem = { config.panelId },
    authorize = authorizeSensitive,
    identityMigration = identityMigration,
    apkUpload = ApkUploadRouteDependencies(
        enabled = { config.apkUploadAllowed },
        rootAvailable = { Su.availableCachedIsolated() || HelperClient.available() },
        pending = pendingApks,
        createStagingFile = { File.createTempFile("apk-upload-", ".apk", appContext.cacheDir) },
        inspect = { staged ->
            withContext(Dispatchers.IO) { AppInstaller.inspect(appContext, staged.absolutePath) }?.let {
                UploadedApkIdentity(it.pkg, it.version, it.signerSha256, it.signerSha256s, it.versionCode)
            }
        },
        startInstall = { claimed, progress ->
            val apk = claimed.file
            val job = scope.launch {
                val result = runCatching {
                    installUploadedApk(
                        claimed,
                        identityMigration,
                        install = { AppInstaller.installLocalApk(appContext, it) },
                    )
                }.getOrElse {
                    apk.delete()
                    "error: ${it.message}"
                }
                Log.i("ha-paneld/http", "APK upload install: $result")
                InstallProgress.finish(progress, result)
            }
            job.invokeOnCompletion { cause -> if (cause != null) apk.delete() }
            InstallProgress.finishOnFailure(progress, job)
        },
    ),
)
