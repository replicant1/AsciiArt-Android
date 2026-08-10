package com.rodbailey.asciiart.ui

/**
 * One-time side effects emitted by [AsciiPreviewViewModel].
 *
 * No events are currently required — camera permission and video-picker launch are both
 * handled directly by composable launchers in [AsciiPreviewRoot], which is the right place
 * for anything that needs a composable-scoped API
 * ([rememberLauncherForActivityResult], etc.).
 *
 * The sealed interface is here as the structural anchor for future additions, for example
 * surfacing a Snackbar when a processing error occurs.
 */
sealed interface AsciiPreviewEvent

