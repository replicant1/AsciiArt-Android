package com.rodbailey.asciiart.ui

import com.rodbailey.asciiart.processing.AsciiDisplayMode

/**
 * The single source of truth for everything [AsciiPreviewScreen] renders.
 *
 * Frame data ([FrameProcessingResult]) is intentionally absent: the camera and video pipelines
 * deliver frames at ~30fps. Keeping those results as local `remember` state inside
 * [CameraTabContent] and [ExoPlayerVideoFileTab] scopes recomposition to only the
 * frame-display composable on each arrival. Routing 30fps updates through this StateFlow
 * would cause the entire [AsciiPreviewScreen] body to re-execute on every frame.
 */
data class AsciiPreviewState(
    /** Down-sampling factor: 1 cell per [scaleFactor] source pixels in each dimension. */
    val scaleFactor: Int = 8,
    /** Contrast multiplier applied to luma before glyph/pixel mapping. */
    val contrastFactor: Float = 1.0f,
    /** Whether per-cell RGB colour is sampled and applied to glyphs / image cells. */
    val colorEnabled: Boolean = false,
    /** Whether the output is drawn as ASCII glyphs or as a de-res image. */
    val displayMode: AsciiDisplayMode = AsciiDisplayMode.IMAGE,
    /** Index of the currently selected tab (0 = Live Camera, 1 = Video File). */
    val selectedTab: Int = 0,
    /**
     * Whether the app currently holds CAMERA permission.
     * Synced from the system on every ON_RESUME via a lifecycle observer in the Root
     * composable so it stays accurate after the user visits Settings.
     */
    val hasCameraPermission: Boolean = false,
    /**
     * Content URI of the last video file the user loaded, as a [String] so it is
     * safely parcelable and can survive process death via [SavedStateHandle].
     * Null until the user picks a file.
     */
    val loadedVideoUri: String? = null
)

