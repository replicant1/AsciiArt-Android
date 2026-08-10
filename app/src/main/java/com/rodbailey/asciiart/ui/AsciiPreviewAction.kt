package com.rodbailey.asciiart.ui

import com.rodbailey.asciiart.processing.AsciiDisplayMode

sealed interface AsciiPreviewAction {
    /** The Scale slider moved; [value] is the new integer step count. */
    data class OnScaleFactorChange(val value: Int) : AsciiPreviewAction
    /** The Contrast slider moved; [value] is the new multiplier. */
    data class OnContrastFactorChange(val value: Float) : AsciiPreviewAction
    /** The Colour toggle changed. */
    data class OnColorEnabledChange(val value: Boolean) : AsciiPreviewAction
    /** The user tapped an Image / ASCII chip. */
    data class OnDisplayModeChange(val mode: AsciiDisplayMode) : AsciiPreviewAction
    /** The user tapped a tab header; [index] is 0 for Live Camera, 1 for Video File. */
    data class OnTabSelected(val index: Int) : AsciiPreviewAction
    /**
     * The camera permission result arrived — either from the initial ON_RESUME check or
     * from the system permission dialog.
     */
    data class OnCameraPermissionResult(val granted: Boolean) : AsciiPreviewAction
    /**
     * The user picked a video file; [uri] is its content URI string.
     * URI persistence ([ContentResolver.takePersistableUriPermission]) is handled by the
     * Root composable before this action is dispatched.
     */
    data class OnVideoUriSelected(val uri: String) : AsciiPreviewAction
}

