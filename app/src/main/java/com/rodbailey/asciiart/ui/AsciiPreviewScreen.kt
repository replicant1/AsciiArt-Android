package com.rodbailey.asciiart.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rodbailey.asciiart.R
import com.rodbailey.asciiart.camera.CameraFrameAnalyzer
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import com.rodbailey.asciiart.processing.FrameProcessingResult
import com.rodbailey.asciiart.processing.GridSize
import com.rodbailey.asciiart.processing.PixelGrid
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val TAG = "AsciiPreviewScreen"

// Slots in AsciiGridPreview's textMetricsCache, which holds the last computed cell size and
// the text metrics derived from it, so measureText() and textSize mutations are skipped on
// frames where the cell size has not changed (i.e. every frame in steady state).
private const val CACHE_CELL_WIDTH = 0
private const val CACHE_CELL_HEIGHT = 1
private const val CACHE_CHAR_WIDTH = 2
private const val CACHE_BASELINE_OFFSET = 3
private const val TEXT_METRICS_CACHE_SLOTS = 4

// Text height as a fraction of cell height. Set to slightly less than 1.0 so that characters
// with tall ascenders or deep descenders (e.g. '|', 'g', 'y') do not overflow into adjacent
// cells. The value was determined empirically: at 1.0 some glyphs clip; at 0.90 the gap is
// visually noticeable; 0.92 is the largest value that keeps all printable ASCII glyphs within
// their cell bounds.
private const val TEXT_SIZE_CELL_FRACTION = 0.92f

// ---------------------------------------------------------------------------
// Root composable — owns the ViewModel, launchers, and lifecycle observer
// ---------------------------------------------------------------------------

/**
 * Entry point called from [com.rodbailey.asciiart.MainActivity].
 *
 * Responsibilities:
 * - Creates / retrieves [AsciiPreviewViewModel].
 * - Syncs [AsciiPreviewState.hasCameraPermission] from the Android system on every
 *   ON_RESUME (covers the "user went to Settings and granted permission" path).
 * - Owns the camera-permission launcher and video-file picker launcher, because both
 *   require composable-scoped APIs ([rememberLauncherForActivityResult]).
 * - Observes [AsciiPreviewEvent]s (none yet, but the channel is ready for future additions).
 * - Passes [AsciiPreviewState] and [AsciiPreviewViewModel.onAction] to the stateless
 *   [AsciiPreviewScreen].
 */
@Composable
fun AsciiPreviewRoot(
    viewModel: AsciiPreviewViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Sync camera permission on every ON_RESUME so the UI reflects changes made in Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                viewModel.onAction(AsciiPreviewAction.OnCameraPermissionResult(granted))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Camera permission launcher — result flows back through the ViewModel.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onAction(AsciiPreviewAction.OnCameraPermissionResult(granted))
    }

    // Video file picker — opens in the Download directory and persists the URI grant.
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input).apply {
                    // EXTRA_INITIAL_URI requires API 26
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:Download"
                            )
                        )
                    }
                }
        }
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist URI permission", e)
            }
            viewModel.onAction(AsciiPreviewAction.OnVideoUriSelected(uri.toString()))
        }
    }

    // Collect one-time events. No events are defined yet; the channel is here so the
    // pattern is in place for future additions (e.g. Snackbar on a processing error).
    ObserveAsEvents(viewModel.events) { /* exhaustive when goes here when events are added */ }

    AsciiPreviewScreen(
        state = state,
        onAction = viewModel::onAction,
        onRequestCameraPermission = {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onLoadVideo = {
            videoPickerLauncher.launch(arrayOf("video/*"))
        }
    )
}

// ---------------------------------------------------------------------------
// Screen composable — stateless; receives state + callbacks only
// ---------------------------------------------------------------------------

/**
 * Renders the full ASCII preview UI.
 *
 * This composable holds no state and no ViewModel reference, making it independently
 * previewable and testable.
 *
 * **Performance note:** frame data is intentionally absent from [state]. The camera and
 * video pipelines deliver results at ~30 fps. Routing those updates through the ViewModel's
 * [StateFlow] would cause this entire composable body to re-execute on every frame arrival.
 * Frame state lives as local `remember` inside [CameraTabContent] and [ExoPlayerVideoFileTab]
 * instead, scoping recomposition to only the inner frame-display composable.
 *
 * @param onRequestCameraPermission Called when the user taps the "Grant Permission" button.
 *   The launcher lives in [AsciiPreviewRoot] because it needs composable scope.
 * @param onLoadVideo Called when the user taps "Load Video". The file-picker launcher
 *   lives in [AsciiPreviewRoot] for the same reason.
 */
@Composable
fun AsciiPreviewScreen(
    state: AsciiPreviewState,
    onAction: (AsciiPreviewAction) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onLoadVideo: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.app_title), style = MaterialTheme.typography.titleLarge)

        // Shared Controls Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.ascii_preview_scale_factor_label, state.scaleFactor),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = state.scaleFactor.toFloat(),
                onValueChange = { onAction(AsciiPreviewAction.OnScaleFactorChange(it.roundToInt())) },
                valueRange = 2f..48f
            )
            Text(
                stringResource(R.string.ascii_preview_contrast_label, (state.contrastFactor * 100f).roundToInt()),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = state.contrastFactor,
                onValueChange = { onAction(AsciiPreviewAction.OnContrastFactorChange(it)) },
                valueRange = 0.2f..2.0f
            )
            DisplayModeChipBar(
                displayMode = state.displayMode,
                onDisplayModeChange = { onAction(AsciiPreviewAction.OnDisplayModeChange(it)) },
                colorEnabled = state.colorEnabled,
                onColorEnabledChange = { onAction(AsciiPreviewAction.OnColorEnabledChange(it)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Tab Selection
        TabRow(selectedTabIndex = state.selectedTab) {
            Tab(
                selected = state.selectedTab == 0,
                onClick = { onAction(AsciiPreviewAction.OnTabSelected(0)) },
                text = { Text(stringResource(R.string.ascii_preview_tab_live_camera)) }
            )
            Tab(
                selected = state.selectedTab == 1,
                onClick = { onAction(AsciiPreviewAction.OnTabSelected(1)) },
                text = { Text(stringResource(R.string.ascii_preview_tab_video_file)) }
            )
        }

        // Content Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (state.selectedTab) {
                0 -> {
                    if (!state.hasCameraPermission) {
                        Button(onClick = onRequestCameraPermission) {
                            Text(stringResource(R.string.camera_permission_request_button))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.camera_permission_required),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } else {
                        CameraTabContent(
                            scaleFactor = state.scaleFactor,
                            contrastFactor = state.contrastFactor,
                            colorEnabled = state.colorEnabled,
                            displayMode = state.displayMode,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                1 -> {
                    ExoPlayerVideoFileTab(
                        scaleFactor = state.scaleFactor,
                        contrastFactor = state.contrastFactor,
                        colorEnabled = state.colorEnabled,
                        displayMode = state.displayMode,
                        loadedVideoUri = state.loadedVideoUri,
                        onLoadVideo = onLoadVideo,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun CameraTabContent(
    scaleFactor: Int,
    contrastFactor: Float,
    colorEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    modifier: Modifier = Modifier
) {
    // liveFrame stays as local remember state: updating it at ~30 fps here scopes
    // recomposition to only this subtree, rather than causing AsciiPreviewScreen to
    // re-execute on every camera frame.
    var liveFrame by remember { mutableStateOf<FrameProcessingResult?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        CameraAnalysisPipeline(
            scaleFactor = scaleFactor,
            contrastFactor = contrastFactor,
            colorEnabled = colorEnabled,
            displayMode = displayMode,
            onFrameProcessed = { frame -> liveFrame = frame }
        )

        val frame = liveFrame
        val previewModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        // A frame carries only what the mode it was captured under draws, so displayBitmap
        // is null for the frame or two still in flight after a switch to Image mode. The
        // camera delivers ~30 a second, so the placeholder covers that gap.
        val imageBitmap = frame?.displayBitmap?.takeIf { displayMode == AsciiDisplayMode.IMAGE }
        when {
            imageBitmap != null -> ImagePreview(
                bitmap = imageBitmap,
                modifier = previewModifier
            )

            frame != null && displayMode == AsciiDisplayMode.ASCII -> AsciiGridPreview(
                gridSize = frame.gridSize,
                asciiText = frame.asciiText,
                asciiColors = frame.asciiColors,
                colorEnabled = colorEnabled,
                modifier = previewModifier
            )

            else -> Box(
                modifier = previewModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.ascii_preview_waiting_for_frames),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun DisplayModeChipBar(
    displayMode: AsciiDisplayMode,
    onDisplayModeChange: (AsciiDisplayMode) -> Unit,
    colorEnabled: Boolean,
    onColorEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                labelColor = MaterialTheme.colorScheme.onSurface
            )
            val chipBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            FilterChip(
                selected = displayMode == AsciiDisplayMode.IMAGE,
                onClick = { onDisplayModeChange(AsciiDisplayMode.IMAGE) },
                label = { Text(stringResource(R.string.ascii_preview_image_button)) },
                colors = chipColors,
                border = chipBorder
            )
            FilterChip(
                selected = displayMode == AsciiDisplayMode.ASCII,
                onClick = { onDisplayModeChange(AsciiDisplayMode.ASCII) },
                label = { Text(stringResource(R.string.ascii_preview_ascii_button)) },
                colors = chipColors,
                border = chipBorder
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.ascii_preview_colour_label))
            Switch(
                checked = colorEnabled,
                onCheckedChange = onColorEnabledChange
            )
        }
    }
}

@Composable
private fun CameraAnalysisPipeline(
    scaleFactor: Int,
    contrastFactor: Float,
    colorEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    onFrameProcessed: (FrameProcessingResult) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentScaleFactor by rememberUpdatedState(scaleFactor)
    val currentContrastFactor by rememberUpdatedState(contrastFactor)
    val currentColorEnabled by rememberUpdatedState(colorEnabled)
    val currentDisplayMode by rememberUpdatedState(displayMode)
    val currentFrameCallback by rememberUpdatedState(onFrameProcessed)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val frameAnalyzer = CameraFrameAnalyzer(
            scaleFactorProvider = { currentScaleFactor },
            contrastFactorProvider = { currentContrastFactor },
            colorEnabledProvider = { currentColorEnabled },
            displayModeProvider = { currentDisplayMode },
            onFrameProcessed = currentFrameCallback
        )
        analysisUseCase.setAnalyzer(analysisExecutor, frameAnalyzer)

        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysisUseCase
                    )
                } catch (interruptedException: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Camera provider setup interrupted", interruptedException)
                } catch (executionException: ExecutionException) {
                    Log.e(TAG, "Failed to obtain camera provider", executionException)
                } catch (securityException: SecurityException) {
                    Log.e(TAG, "Camera permission not granted", securityException)
                } catch (illegalStateException: IllegalStateException) {
                    Log.e(TAG, "Camera could not bind to lifecycle", illegalStateException)
                } catch (illegalArgumentException: IllegalArgumentException) {
                    Log.e(TAG, "Invalid camera binding arguments", illegalArgumentException)
                }
            },
            mainExecutor
        )

        onDispose {
            analysisUseCase.clearAnalyzer()
            if (cameraProviderFuture.isDone) {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                } catch (interruptedException: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Camera provider teardown interrupted", interruptedException)
                } catch (executionException: ExecutionException) {
                    Log.e(TAG, "Failed to obtain camera provider on teardown", executionException)
                }
            }
            analysisExecutor.shutdown()
        }
    }
}

/**
 * Draws the de-res grid: one small image — 135x240 at scaleFactor 8 on a Pixel 3 — scaled
 * up with no filtering, so cells stay hard-edged. Colour mode used to draw that by hand,
 * one drawRect per cell: 32,400 canvas ops per frame, ~972,000/sec at 30fps.
 *
 * Which picture this is — the sampled cell colours or the grayscale luma — is settled by
 * [com.rodbailey.asciiart.processing.ImageProcessor], which builds only the one the current
 * mode shows. This composable used to take both and choose, which meant the pipeline had to
 * produce both.
 *
 * The scene is drawn as captured — no inversion. Only the letterbox background is black, to
 * match the surrounding UI. ASCII mode needs its glyph density to track brightness because
 * ink on a black background *is* the light, but Image mode paints the luminance directly,
 * so inverting it just yields a photographic negative.
 */
@Composable
fun ImagePreview(
    bitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.ascii_preview_image_content_description),
        modifier = modifier.background(Color.Black),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

/**
 * Draws [asciiText] as a [gridSize] grid of glyphs.
 *
 * The dimensions arrive as a [GridSize] because that is all this ever wanted. It used to
 * take the grayscale `Bitmap` and read `.width`/`.height` off it — never a pixel — which
 * read as if the image were being drawn here and kept a full-size bitmap alive per frame
 * to carry two numbers.
 *
 * [gridSize] is passed even though [asciiColors] carries its own, because with Colour off
 * there is no colour grid and no other source of the dimensions. When both are here they are
 * the same object — [com.rodbailey.asciiart.processing.ImageProcessor] builds them from one
 * value — but nothing in the type system says so, hence the check below.
 */
@Composable
fun AsciiGridPreview(
    gridSize: GridSize,
    asciiText: String,
    asciiColors: PixelGrid?,
    colorEnabled: Boolean,
    modifier: Modifier,
) {
    require(asciiColors == null || asciiColors.size == gridSize) {
        "colour grid ${asciiColors?.size} does not match the glyph grid $gridSize"
    }

    val (gridWidth, gridHeight) = gridSize
    val rowStride = gridWidth + 1
    val defaultAsciiColor = Color.White.toArgb()
    val gridWidthSampleChar = stringResource(R.string.grid_width_sample_char)
    val textPaint = remember {
        AndroidPaint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    val fontMetricsCache = remember { AndroidPaint.FontMetrics() }
    val textMetricsCache = remember { FloatArray(TEXT_METRICS_CACHE_SLOTS) { -1f } }

    Canvas(modifier = modifier.background(Color.Black)) {
        val sourceWidth = gridWidth.toFloat()
        val sourceHeight = gridHeight.toFloat()
        val sourceAspect = sourceWidth / sourceHeight
        val canvasAspect = size.width / size.height

        val drawWidth: Float
        val drawHeight: Float
        val drawOffsetX: Float
        val drawOffsetY: Float

        if (canvasAspect > sourceAspect) {
            drawHeight = size.height
            drawWidth = drawHeight * sourceAspect
            drawOffsetX = (size.width - drawWidth) / 2f
            drawOffsetY = 0f
        } else {
            drawWidth = size.width
            drawHeight = drawWidth / sourceAspect
            drawOffsetX = 0f
            drawOffsetY = (size.height - drawHeight) / 2f
        }

        if (asciiText.isNotEmpty()) {
            val cellWidth = drawWidth / gridWidth
            val cellHeight = drawHeight / gridHeight

            if (cellWidth != textMetricsCache[CACHE_CELL_WIDTH] || cellHeight != textMetricsCache[CACHE_CELL_HEIGHT]) {
                val baseTextSize = cellHeight * TEXT_SIZE_CELL_FRACTION
                textPaint.textSize = baseTextSize
                val sampleWidth = textPaint.measureText(gridWidthSampleChar).coerceAtLeast(1f)
                if (sampleWidth > cellWidth) {
                    textPaint.textSize = baseTextSize * (cellWidth / sampleWidth)
                }
                textPaint.getFontMetrics(fontMetricsCache)
                textMetricsCache[CACHE_BASELINE_OFFSET] = (cellHeight - (fontMetricsCache.bottom - fontMetricsCache.top)) / 2f - fontMetricsCache.top
                textMetricsCache[CACHE_CHAR_WIDTH] = textPaint.measureText(gridWidthSampleChar)
                textMetricsCache[CACHE_CELL_WIDTH] = cellWidth
                textMetricsCache[CACHE_CELL_HEIGHT] = cellHeight
            }
            val baselineOffset = textMetricsCache[CACHE_BASELINE_OFFSET]
            val charWidth = textMetricsCache[CACHE_CHAR_WIDTH]
            val rowStartX = drawOffsetX + (cellWidth - charWidth) / 2f
            val nativeCanvas = drawContext.canvas.nativeCanvas

            if (!colorEnabled) {
                textPaint.color = defaultAsciiColor
                textPaint.letterSpacing = (cellWidth - charWidth) / textPaint.textSize
                for (y in 0 until gridHeight) {
                    val rowStart = y * rowStride
                    if (rowStart >= asciiText.length) break
                    val rowEnd = minOf(rowStart + gridWidth, asciiText.length)
                    val textY = drawOffsetY + (y * cellHeight) + baselineOffset
                    nativeCanvas.drawText(asciiText, rowStart, rowEnd, rowStartX, textY, textPaint)
                }
                textPaint.letterSpacing = 0f
            } else {
                val singleChar = CharArray(1)
                for (y in 0 until gridHeight) {
                    val rowStart = y * rowStride
                    if (rowStart >= asciiText.length) break
                    val rowEnd = minOf(rowStart + gridWidth, asciiText.length)
                    val textY = drawOffsetY + (y * cellHeight) + baselineOffset
                    for (x in 0 until gridWidth) {
                        textPaint.color = asciiColors?.getOrNull(x, y) ?: defaultAsciiColor
                        singleChar[0] = if (rowStart + x < rowEnd) asciiText[rowStart + x] else ' '
                        val textX = rowStartX + (x * cellWidth)
                        nativeCanvas.drawText(singleChar, 0, 1, textX, textY, textPaint)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

/**
 * Collects [flow] while the lifecycle is at least STARTED, forwarding each emission to
 * [onEvent]. Cancels and restarts automatically with lifecycle transitions.
 *
 * Used by [AsciiPreviewRoot] to observe [AsciiPreviewEvent]s. Extracted as a composable so
 * the lifecycle-aware collection pattern is reusable and its intent is self-documenting.
 */
@Composable
private fun <T> ObserveAsEvents(flow: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(flow, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { onEvent(it) }
        }
    }
}

