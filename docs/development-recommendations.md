# Development Recommendations

*Generated: 2026-08-10*

A prioritised list of development tasks that could improve the app or codebase.

---

## 🔴 High Value

### 1. ViewModel Unit Tests
`AsciiPreviewViewModel` has no tests yet. Given it now owns all the state logic, testing actions like scale/contrast/colour changes and tab selection would be valuable.
- **Skill:** `android-testing`

### 2. String Externalisation
There are likely hardcoded string literals scattered through the composables. These should be moved to `strings.xml` for proper Android i18n support.
- **Skill:** `android-string-externalisation`

### 3. Dependency Injection (Koin)
The ViewModel and processing classes are instantiated without a DI framework. Adding Koin would make the dependency graph explicit and make testing much easier.
- **Skill:** `android-di-koin`

---

## 🟡 Medium Value

### 4. Error Handling Layer
There is no typed `Result`/`DataError` wrapper. Camera permission denials, video file errors, and processing failures are likely handled ad-hoc. A consistent error model would clean this up.
- **Skill:** `android-error-handling`

### 5. Compose UI Tests
The screen composables have no UI tests. Tests for the tab bar, sliders, and display mode switching would catch regressions.
- **Skill:** `android-testing`

### 6. Navigation Layer
If a second screen is ever added (e.g. settings, about), there is no nav graph in place. Setting up type-safe Compose Navigation now would prevent a painful retrofit later.
- **Skill:** `android-navigation`

---

## 🟢 Lower Priority / Nice to Have

### 7. Module Structure
Everything lives in one `:app` module. Splitting into `:feature:preview`, `:core:processing`, `:core:camera` etc. would improve build times and enforce clean architectural boundaries.
- **Skill:** `android-module-structure`

### 8. KDoc / Documentation
The processing engine (`ImageProcessor`, `AsciiArt`, `PixelGrid`) is complex. Adding KDoc to the public API of those classes would help future maintainers.

### 9. Proguard / R8 Rules Review
The `proguard-rules.pro` is likely still the default. If the app is ever released, rules for ExoPlayer and CameraX should be verified to prevent runtime crashes in release builds.

---

## Status

| # | Task | Status |
|---|------|--------|
| 1 | ViewModel unit tests | ⬜ Not started |
| 2 | String externalisation | ⬜ Not started |
| 3 | Dependency injection (Koin) | ✅ Done |
| 4 | Error handling layer | ⬜ Not started |
| 5 | Compose UI tests | ⬜ Not started |
| 6 | Navigation layer | ⬜ Not started |
| 7 | Module structure | ⬜ Not started |
| 8 | KDoc / documentation | ⬜ Not started |
| 9 | Proguard / R8 rules review | ⬜ Not started |

