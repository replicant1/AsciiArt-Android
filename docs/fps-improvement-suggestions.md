# Live Camera FPS Improvement Suggestions

*Generated: 2026-08-10*

Potential optimisations for the live camera pipeline, ordered by expected impact.

---

## 🔴 High Impact

### 1. Pre-compute a contrast look-up table (LUT)
**Status:** ⬜ Not started

The inner loop runs this arithmetic on every pixel, every frame:
```kotlin
val contrastedGray = (((gray - 128f) * contrast) + 128f).coerceIn(0f, 255f)
```
Since `contrast` only changes when the slider moves, a 256-entry `IntArray` LUT can be built once per slider change. Per-pixel float multiply/add/clamp is then replaced with a single array lookup.

At 640×480 with scale=8, that is 9,600 lookups/frame. At scale=4 it is 38,400 — the impact grows as scale decreases.

**Files:** `ImageProcessor.kt`

---

### 2. Reuse the display `Bitmap` instead of allocating a new one per frame
**Status:** ⬜ Not started

In Image mode, `Bitmap.createBitmap()` is called ~30 times/sec in `displayBitmapFor()`. Keeping a pooled `Bitmap` and calling `setPixels()` to update it in-place eliminates 30 short-lived heap allocations per second and the GC pauses they cause.

Care is needed to signal Compose that the bitmap contents have changed even though the reference is the same.

**Files:** `ImageProcessor.kt`, `AsciiPreviewScreen.kt`

---

## 🟡 Medium Impact

### 3. Replace `mainThreadHandler.post()` with a `MutableStateFlow`
**Status:** ⬜ Not started

Every frame posts a `Runnable` to the main thread via `Handler`. A `MutableStateFlow<FrameProcessingResult?>` updated from the analysis thread and collected with `collectAsStateWithLifecycle()` in the composable is more idiomatic, avoids handler allocation overhead, and naturally drops stale frames by keeping only the latest value.

**Files:** `CameraFrameAnalyzer.kt`, `AsciiPreviewScreen.kt`

---

### 4. Eliminate per-frame `String` allocation in `toAsciiText()`
**Status:** ⬜ Not started

`AsciiArt.toAsciiText()` builds a new `String` every frame in ASCII mode. A reusable `StringBuilder` kept inside `ImageProcessor` — following the same `PixelBuffer` pattern already used for pixel arrays — would remove one heap allocation per frame in ASCII mode.

**Files:** `AsciiArt.kt`, `ImageProcessor.kt`

---

## 🟢 Lower Impact / Newly Spotted

### 5. Skip `AsciiGridPreview` repaint when content is unchanged
**Status:** ⬜ Not started

The `Canvas` draw loop in `AsciiGridPreview` redraws every glyph every frame unconditionally. Skipping the repaint when `asciiText` and `asciiColors` are reference-equal to the previous frame would save canvas work on any frame the analyzer did not deliver a new result.

**Files:** `AsciiPreviewScreen.kt`

---

### 6. Optimise `yuvToArgb` for the colour path
**Status:** ⬜ Not started

The YUV→ARGB conversion runs for every output cell when colour is on. The coefficients (298, 409, 100, 208, 516) are fixed. This is a candidate for a small NDK function or SIMD intrinsic if pushing past 30 FPS in colour mode at low scale factors becomes a goal.

**Files:** `ImageProcessor.kt`

---

### 7. Eliminate the `PixelBuffer.freeze()` copy in ASCII + Colour mode
**Status:** ⬜ Not started

`freeze()` copies the entire colour pixel array into a new `PixelGrid` every frame so the UI can hold it safely while the analyser writes the next. Introducing a double-buffer — two `PixelBuffer` instances alternated — would let the UI hold one buffer while the analyser writes the other, eliminating the copy.

**Files:** `PixelGrid.kt`, `ImageProcessor.kt`

---

## Completed

| # | Optimisation | Result |
|---|---|---|
| — | Explicit 640×480 `ResolutionSelector` on `ImageAnalysis` | Steady-state FPS: ~25 → ~30 on Pixel 3 |

