package com.rodbailey.asciiart.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.rodbailey.asciiart.R
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import com.rodbailey.asciiart.processing.ExoPlayerFrameListener
import com.rodbailey.asciiart.processing.FrameProcessingResult


/**
 * Renders the Video File tab.
 *
 * URI selection and persistence are handled by the caller ([AsciiPreviewRoot]) via the
 * [onLoadVideo] callback and [loadedVideoUri] state. This composable owns only the
 * ExoPlayer instance and the frame-capture pipeline, both of which are lifecycle-scoped
 * to this composition via [DisposableEffect].
 *
 * **Performance note:** [videoFrame] and [isPlaying] remain as local `remember` state.
 * They update at up to ~30 fps and would cause [AsciiPreviewScreen] to re-execute on
 * every arrival if routed through the ViewModel's [StateFlow].
 *
 * @param loadedVideoUri Content URI string of the video to play, or null if none loaded.
 * @param onLoadVideo Called when the user taps the "Load Video" button; the file picker
 *   launcher lives in [AsciiPreviewRoot] and is passed down as this lambda.
 */
@Composable
fun ExoPlayerVideoFileTab(
    scaleFactor: Int,
    contrastFactor: Float,
    colorEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    loadedVideoUri: String?,
    onLoadVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var frameListener by remember { mutableStateOf<ExoPlayerFrameListener?>(null) }
    var captureTextureView by remember { mutableStateOf<TextureView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoFrame by remember { mutableStateOf<FrameProcessingResult?>(null) }

    val currentScaleFactor = rememberUpdatedState(scaleFactor)
    val currentContrastFactor = rememberUpdatedState(contrastFactor)
    val currentColorEnabled = rememberUpdatedState(colorEnabled)
    val currentDisplayMode = rememberUpdatedState(displayMode)
    val currentCaptureTextureView = rememberUpdatedState(captureTextureView)
    val currentFrameSetter = rememberUpdatedState { frame: FrameProcessingResult ->
        videoFrame = frame
    }

    // Create and tear down ExoPlayer + frame listener
    DisposableEffect(Unit) {
        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player

        val listener = ExoPlayerFrameListener(
            exoPlayer = player,
            textureViewProvider = { currentCaptureTextureView.value },
            scaleFactorProvider = { currentScaleFactor.value },
            contrastFactorProvider = { currentContrastFactor.value },
            colorEnabledProvider = { currentColorEnabled.value },
            displayModeProvider = { currentDisplayMode.value },
            frameSkipRate = 2,
            onFrameProcessed = { frame -> currentFrameSetter.value(frame) }
        )
        frameListener = listener
        listener.startListening()

        onDispose {
            listener.release()
            player.release()
            exoPlayer = null
            frameListener = null
        }
    }

    // Track ExoPlayer's playing state for the Play/Pause button
    DisposableEffect(exoPlayer) {
        val player = exoPlayer ?: return@DisposableEffect onDispose {}
        val playbackListener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(playbackListener)
        onDispose { player.removeListener(playbackListener) }
    }

    // Load and play video when URI changes
    LaunchedEffect(loadedVideoUri, exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        val uri = loadedVideoUri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
        player.play()
    }

    // Point ExoPlayer at the capture TextureView. Both display modes render from captured
    // frames, so the target never changes with displayMode — but it does have to wait for
    // the TextureView, which AndroidView creates after the first composition.
    LaunchedEffect(exoPlayer, captureTextureView) {
        val player = exoPlayer ?: return@LaunchedEffect
        val textureView = captureTextureView ?: return@LaunchedEffect
        player.setVideoTextureView(textureView)
    }

    // Each processed frame is built for one display mode and one colour setting, so
    // changing either needs a frame of its own. While playing, the next capture is a frame
    // or two away and this changes nothing; while paused there is no next capture, and
    // without this the display would stay as it was until playback resumed.
    LaunchedEffect(displayMode, colorEnabled, frameListener) {
        frameListener?.refreshCurrentFrame()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Persistent control bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onLoadVideo) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.video_file_load_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            IconButton(
                onClick = { exoPlayer?.seekTo(0); exoPlayer?.play() },
                enabled = loadedVideoUri != null
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.video_file_restart_button)
                )
            }
            IconButton(
                onClick = { exoPlayer?.play() },
                enabled = loadedVideoUri != null && !isPlaying
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.video_file_play_button)
                )
            }
            IconButton(
                onClick = { exoPlayer?.pause() },
                enabled = loadedVideoUri != null && isPlaying
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = stringResource(R.string.video_file_pause_button)
                )
            }
        }

        // Content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Hidden capture surface — ExoPlayer renders the decoded video here and
            // getBitmap() reads it back. alpha=0 keeps it invisible; the processed output
            // is drawn over the top.
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply { alpha = 0f }.also { captureTextureView = it }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (loadedVideoUri == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.video_file_none_loaded),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                val frame = videoFrame
                val imageBitmap =
                    frame?.displayBitmap?.takeIf { displayMode == AsciiDisplayMode.IMAGE }
                if (imageBitmap != null) {
                    ImagePreview(
                        bitmap = imageBitmap,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (frame != null && displayMode == AsciiDisplayMode.ASCII) {
                    AsciiGridPreview(
                        gridSize = frame.gridSize,
                        asciiText = frame.asciiText,
                        asciiColors = frame.asciiColors,
                        colorEnabled = colorEnabled,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.video_file_waiting_for_frames),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
