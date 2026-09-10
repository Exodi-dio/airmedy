package me.misa198.airmedy.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.misa198.airmedy.R
import me.misa198.airmedy.sync.AndroidSyncRuntime
import me.misa198.airmedy.sync.LocalLibraryScanResult
import me.misa198.airmedy.sync.MediaStoreLibraryScanner
import me.misa198.airmedy.ui.components.AirmedyPillButton
import me.misa198.airmedy.ui.components.AirmedyPillButtonVariant
import me.misa198.airmedy.ui.theme.LocalAirmedyColors

internal data class LibraryScanUiState(
    val isScanning: Boolean = false,
    val tracks: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val completed: Boolean = false,
    val permissionDenied: Boolean = false,
)

@Composable
internal fun LibraryScanContent(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(LibraryScanUiState()) }
    val colors = LocalAirmedyColors.current
    val context = LocalContext.current.applicationContext

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScan(scope, context) { uiState = it }
        } else {
            uiState = LibraryScanUiState(permissionDenied = true)
        }
    }
    val startScan: () -> Unit = {
        val permission = readMediaPermission()
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            launchScan(scope, context) { uiState = it }
        } else {
            uiState = LibraryScanUiState()
            permissionLauncher.launch(permission)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(R.string.scan_description),
            color = colors.textMuted,
        )
        Spacer(modifier = Modifier.height(16.dp))
        when {
            uiState.isScanning -> Text(
                text = stringResource(R.string.scan_running),
                color = colors.textMuted,
            )
            uiState.permissionDenied -> {
                Text(
                    text = stringResource(R.string.scan_permission_denied),
                    color = colors.textMuted,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ScanButton(
                    label = stringResource(R.string.scan_start),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = startScan,
                )
            }
            uiState.completed -> {
                Text(
                    text = stringResource(
                        R.string.scan_complete,
                        uiState.tracks,
                        uiState.albums,
                        uiState.artists,
                    ),
                    color = colors.textMuted,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ScanButton(
                    label = stringResource(R.string.scan_again),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = startScan,
                )
            }
            else -> ScanButton(
                label = stringResource(R.string.scan_start),
                modifier = Modifier.fillMaxWidth(),
                onClick = startScan,
            )
        }
    }
}

@Composable
private fun ScanButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AirmedyPillButton(
        label = label,
        variant = AirmedyPillButtonVariant.Primary,
        onClick = onClick,
        modifier = modifier,
    )
}

private fun launchScan(scope: CoroutineScope, context: Context, onResult: (LibraryScanUiState) -> Unit) {
    scope.launch {
        onResult(LibraryScanUiState(isScanning = true))
        onResult(performScan(context) ?: LibraryScanUiState())
    }
}

private fun readMediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private suspend fun performScan(context: Context): LibraryScanUiState? = withContext(Dispatchers.IO) {
    runCatching {
        val scanner = MediaStoreLibraryScanner(
            contentResolver = context.contentResolver,
            artworkDir = File(context.filesDir, "artwork"),
        )
        val result: LocalLibraryScanResult = scanner.scan()
        AndroidSyncRuntime.syncStore().writeLocalLibrary(
            snapshot = result.snapshot,
            audioRows = result.audio,
            artworkRows = result.artwork,
        )
        val albums = result.snapshot.tracks.map { it.album.id }.distinct().size
        val artists = result.snapshot.tracks.flatMap { it.artists }.map { it.id }.distinct().size
        LibraryScanUiState(
            tracks = result.snapshot.tracks.size,
            albums = albums,
            artists = artists,
            completed = true,
        )
    }.getOrNull()
}