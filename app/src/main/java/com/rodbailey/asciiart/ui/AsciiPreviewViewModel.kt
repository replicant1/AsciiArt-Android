package com.rodbailey.asciiart.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Holds and mutates all user-controlled state for the ASCII preview screen.
 *
 * ## What is and is not here
 *
 * User settings (scale, contrast, colour, display mode, tab, loaded video URI) live here
 * because they are user-controlled, survive configuration change by default, and survive
 * process death via [SavedStateHandle].
 *
 * Frame data ([com.rodbailey.asciiart.processing.FrameProcessingResult]) is deliberately
 * absent. The camera and video pipelines deliver frames at ~30 fps. Routing those updates
 * through this [StateFlow] would cause the entire [AsciiPreviewScreen] body to re-execute
 * on every arrival. Keeping them as local `remember` state inside [CameraTabContent] and
 * [ExoPlayerVideoFileTab] scopes recomposition to only the frame-display composable.
 *
 * [hasCameraPermission] lives here so the Screen composable has a single `state` parameter,
 * but it is always synced from the Android system (not derived from ViewModel logic) via a
 * lifecycle observer in [AsciiPreviewRoot].
 *
 * ## Dispatchers
 *
 * All mutations run on whichever thread calls [onAction] — in practice always the main
 * thread — so no dispatcher injection is needed. [StateFlow.update] is an atomic swap;
 * the overhead at 30 fps from slider drags is negligible.
 */
class AsciiPreviewViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(
        AsciiPreviewState(
            scaleFactor = savedStateHandle[KEY_SCALE_FACTOR] ?: 8,
            contrastFactor = savedStateHandle[KEY_CONTRAST_FACTOR] ?: 1.0f,
            colorEnabled = savedStateHandle[KEY_COLOR_ENABLED] ?: false,
            displayMode = savedStateHandle.get<String>(KEY_DISPLAY_MODE)
                ?.let { runCatching { AsciiDisplayMode.valueOf(it) }.getOrNull() }
                ?: AsciiDisplayMode.IMAGE,
            selectedTab = savedStateHandle[KEY_SELECTED_TAB] ?: 0,
            // hasCameraPermission always starts false; the Root composable syncs it on
            // the first ON_RESUME, before the first frame is ever requested.
            hasCameraPermission = false,
            loadedVideoUri = savedStateHandle[KEY_LOADED_VIDEO_URI]
        )
    )
    val state = _state.asStateFlow()

    private val _events = Channel<AsciiPreviewEvent>()
    val events = _events.receiveAsFlow()

    fun onAction(action: AsciiPreviewAction) {
        when (action) {
            is AsciiPreviewAction.OnScaleFactorChange -> {
                savedStateHandle[KEY_SCALE_FACTOR] = action.value
                _state.update { it.copy(scaleFactor = action.value) }
            }
            is AsciiPreviewAction.OnContrastFactorChange -> {
                savedStateHandle[KEY_CONTRAST_FACTOR] = action.value
                _state.update { it.copy(contrastFactor = action.value) }
            }
            is AsciiPreviewAction.OnColorEnabledChange -> {
                savedStateHandle[KEY_COLOR_ENABLED] = action.value
                _state.update { it.copy(colorEnabled = action.value) }
            }
            is AsciiPreviewAction.OnDisplayModeChange -> {
                savedStateHandle[KEY_DISPLAY_MODE] = action.mode.name
                _state.update { it.copy(displayMode = action.mode) }
            }
            is AsciiPreviewAction.OnTabSelected -> {
                savedStateHandle[KEY_SELECTED_TAB] = action.index
                _state.update { it.copy(selectedTab = action.index) }
            }
            is AsciiPreviewAction.OnCameraPermissionResult -> {
                // Not saved to SavedStateHandle — always re-queried from the system.
                _state.update { it.copy(hasCameraPermission = action.granted) }
            }
            is AsciiPreviewAction.OnVideoUriSelected -> {
                savedStateHandle[KEY_LOADED_VIDEO_URI] = action.uri
                _state.update { it.copy(loadedVideoUri = action.uri) }
            }
        }
    }

    companion object {
        private const val KEY_SCALE_FACTOR = "scaleFactor"
        private const val KEY_CONTRAST_FACTOR = "contrastFactor"
        private const val KEY_COLOR_ENABLED = "colorEnabled"
        private const val KEY_DISPLAY_MODE = "displayMode"
        private const val KEY_SELECTED_TAB = "selectedTab"
        private const val KEY_LOADED_VIDEO_URI = "loadedVideoUri"
    }
}

