package io.github.mangi.eta.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.R
import io.github.mangi.eta.data.update.AppLatestRelease
import io.github.mangi.eta.data.update.AppUpdateChecker
import io.github.mangi.eta.data.update.AppUpdateInstaller
import io.github.mangi.eta.data.update.UpdateException
import io.github.mangi.eta.data.update.UpdateIssue
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog

internal class OnlineUpdateControls(
    val status: String?,
    val checking: Boolean,
    val onClick: () -> Unit,
    val dialog: @Composable () -> Unit,
)

@Composable
internal fun rememberOnlineUpdate(context: Context): OnlineUpdateControls {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val progressFlow = remember { MutableStateFlow(0) }
    val progress by progressFlow.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppLatestRelease?>(null) }
    var show by rememberSaveable { mutableStateOf(false) }
    // Only set after private-cache download and verification succeed. Saved state handles permission round trips.
    var readyName by rememberSaveable { mutableStateOf<String?>(null) }
    var download by remember { mutableStateOf<Job?>(null) }
    var issue by remember { mutableStateOf<Int?>(null) }

    fun launchInstaller(name: String) {
        try {
            context.startActivity(AppUpdateInstaller.installIntent(context, name))
            show = false
        } catch (_: Exception) {
            issue = R.string.update_open_installer_failed
            readyName = null
        }
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        readyName?.let { name ->
            if (context.packageManager.canRequestPackageInstalls()) launchInstaller(name)
            else issue = R.string.update_install_permission_needed
        }
    }

    fun installOrAskPermission(name: String) {
        issue = null
        if (context.packageManager.canRequestPackageInstalls()) {
            launchInstaller(name)
        } else {
            try {
                installPermission.launch(AppUpdateInstaller.settingsIntent(context))
            } catch (_: Exception) {
                issue = R.string.update_open_installer_failed
            }
        }
    }

    fun close() {
        download?.cancel()
        show = false
    }

    fun browse(target: AppLatestRelease) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target.url.toString())))
        } catch (_: Exception) {
            issue = R.string.update_open_release_failed
        }
    }

    val checkingText = stringResource(R.string.ui_about_update_checking)
    val checkFailed = stringResource(R.string.ui_update_check_failed)
    val alreadyLatest = stringResource(R.string.ui_update_already_latest)
    val nonePublished = stringResource(R.string.update_channel_empty, BuildConfig.UPDATE_REPOSITORY)
    fun check() {
        if (checking || download != null) return
        if (readyName != null) {
            show = true
            return
        }
        checking = true
        scope.launch {
            try {
                val latest = AppUpdateChecker.fetchLatest()
                when {
                    latest == null -> Toast.makeText(context, nonePublished, Toast.LENGTH_LONG).show()
                    !AppUpdateChecker.isNewer(latest.version, BuildConfig.VERSION_NAME) ->
                        Toast.makeText(context, alreadyLatest, Toast.LENGTH_SHORT).show()
                    else -> {
                        release = latest
                        issue = null
                        show = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, checkFailed, Toast.LENGTH_SHORT).show()
            } finally {
                checking = false
            }
        }
    }

    fun downloadUpdate(target: AppLatestRelease) {
        if (download != null) return
        issue = null
        progressFlow.value = 0
        download = scope.launch {
            try {
                val name = AppUpdateInstaller.downloadAndVerify(context.applicationContext, target) {
                    progressFlow.value = it
                }
                readyName = name
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    installOrAskPermission(name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                issue = when (e.issue) {
                    UpdateIssue.INTEGRITY -> R.string.update_integrity_failed
                    UpdateIssue.PACKAGE -> R.string.update_package_mismatch
                    UpdateIssue.VERSION -> R.string.update_version_mismatch
                    UpdateIssue.SIGNATURE -> R.string.update_signature_mismatch
                    UpdateIssue.SYSTEM -> R.string.update_system_unsupported
                }
            } catch (_: Exception) {
                issue = R.string.update_download_failed
            } finally {
                download = null
            }
        }
    }

    return OnlineUpdateControls(
        status = when {
            checking -> checkingText
            download != null -> stringResource(R.string.update_downloading, progress)
            readyName != null -> stringResource(R.string.update_ready)
            else -> stringResource(R.string.update_channel, BuildConfig.UPDATE_REPOSITORY)
        },
        checking = checking,
        onClick = ::check,
        dialog = {
            val target = release
            val ready = readyName
            val busy = download != null
            GlassDialog(
                show = show && (target != null || ready != null),
                backdrop = currentGlassOverlayBackdrop(),
                title = stringResource(R.string.ui_update_available_title),
                summary = target?.let { stringResource(R.string.ui_update_available_message, it.version, BuildConfig.VERSION_NAME) },
                onDismissRequest = ::close,
            ) {
                Column {
                    val message = when {
                        busy -> if (progress < 100) stringResource(R.string.update_downloading, progress)
                            else stringResource(R.string.update_verifying)
                        issue != null -> stringResource(requireNotNull(issue))
                        ready != null -> stringResource(R.string.update_ready)
                        target?.apk == null -> stringResource(R.string.update_no_verified_asset)
                        else -> null
                    }
                    if (message != null) {
                        Text(message)
                        Spacer(Modifier.height(12.dp))
                    }
                    DialogActions(
                        confirmText = stringResource(when {
                            ready != null -> R.string.update_install
                            target?.apk != null -> R.string.update_download_install
                            else -> R.string.update_open_release
                        }),
                        onCancel = ::close,
                        confirmEnabled = !busy,
                        onConfirm = {
                            when {
                                ready != null -> installOrAskPermission(ready)
                                target?.apk != null -> downloadUpdate(target)
                                target != null -> browse(target)
                            }
                        },
                    )
                }
            }
        },
    )
}
