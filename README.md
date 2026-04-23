# Food OCR

Android app for scanning food package dates with CameraX + Google ML Kit Chinese OCR.

## Current goals

- Recognize manufacture date and expiry date from food packaging
- Handle mixed labels such as `MFG`, `MFD`, `EXP`, `BEST BEFORE`, `製造日期`, `有效期限`
- Support multiple date formats, including compact numeric dates like `20261121`
- Stabilize results with multi-frame weighted voting instead of trusting a single frame

## Current implementation

- Camera preview and live OCR analysis with CameraX
- Chinese text recognition via ML Kit
- Dual-role output:
  - `MFG`
  - `EXP`
- Multi-format parsing:
  - `yyyy-MM-dd`
  - `yyyy/MM/dd`
  - `yyyy.MM.dd`
  - `yyyy年MM月dd日`
  - compact 8-digit dates such as `20261121`
  - compact 8-digit month-first dates such as `11212026`
  - several 6-digit and ROC-year variants
- Nearby-line pairing:
  - tries to connect labels and dates even when they are split across adjacent lines
- Weighted vote buffers:
  - manufacture and expiry dates are voted independently across recent frames
- Basic frame-quality checks:
  - too dark
  - too bright
  - low contrast
  - heavy glare
- Fallback enhancement pass:
  - when quality is poor and first OCR pass is weak, the app retries with a contrast-enhanced grayscale image

## Project structure

- `app/src/main/java/com/example/foodocr/MainActivity.kt`
  App entry point, camera binding, UI updates, vote rendering
- `app/src/main/java/com/example/foodocr/FoodDateAnalyzer.kt`
  OCR pipeline, date parsing, label matching, quality scoring, weighted voting models
- `app/src/main/res/layout/activity_main.xml`
  Preview screen and status panel

## Build

Open in Android Studio and let Gradle sync, or run:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

## Current APK outputs

These are generated locally and are not committed:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## Known limitations

- Not yet validated against a large real-world packaging image set
- Reflection, wrinkles, curved plastic, and dot-matrix printing can still reduce OCR quality
- Date-role inference is heuristic-based; some edge cases may still swap MFG and EXP
- No crop overlay or guided region-of-interest yet
- No sample image regression tests yet

## Recommended next steps

1. Collect real packaging photos from multiple brands and lighting conditions
2. Add an image replay mode for testing without live camera
3. Improve ROI detection so OCR focuses on likely date-print regions
4. Tune heuristics using real examples with wrong predictions
5. Consider optional OpenCV preprocessing if the current fallback is not enough

## Handoff

See [docs/HANDOFF.md](docs/HANDOFF.md) for the current status, design choices, and what to continue on another machine.
