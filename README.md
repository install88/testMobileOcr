# Food OCR Offline Android

Android offline package-date recognition app based on the `yoloOcr` v2.2
pipeline.

The app does not call any API. When the ONNX assets are present, it runs:

```text
CameraX frame -> scan-guide ROI -> YOLO date detector -> crop -> PPOCR rec -> date parser -> MFG/EXP assignment
```

If any model asset is missing, the app shows an error screen (ML Kit fallback
was removed in v1.1.0 along with arm64-only APK packaging).

## Two UI Modes

The launcher entry (`MenuActivity`) gives the user two ways to scan:

### 自動偵測 (Auto-detect) — `MainActivity`

- `ImageAnalysis` use case only. CameraX delivers preview frames continuously.
- Each frame at ~350ms cadence: YOLO → rec → regex → MFG/EXP role assignment.
- Vote-based stabilization: results across frames accumulate into stable
  MFG/EXP picks.
- Use case: hands-free continuous scanning until a confident reading emerges.

### 拍照確認 (Snap) — `SnapActivity` (Freeze design)

- Same `ImageAnalysis` pipeline runs continuously and updates a **live red
  bounding-box overlay** (`BoundingBoxOverlayView`) so the user sees what YOLO
  is locking onto BEFORE they commit.
- The capture button does NOT trigger `ImageCapture`. Instead it reads the
  most recent `OfflineFrameResult` (`@Volatile lastFrameResult`) and freezes
  the display on it.
- Net effect: snap mode is bit-for-bit equivalent to auto-detect — same
  pipeline, same input frame, what the red overlay shows IS the result.
- Pressing during a gap before any analysis frame has run shows
  「畫面尚未分析、請稍候」 instead of returning a stale guess.
- No high-resolution captured JPEG is produced (we don't need it). Revert
  commit `4b2e325` to restore the legacy ImageCapture + downscale path if
  evidence storage is ever required.

### Why Freeze beat ImageCapture for snap mode

The earlier ImageCapture path (with `CAPTURE_MODE_MAXIMIZE_QUALITY`) had three
failure modes that the Freeze design avoids:

1. **AF re-trigger gap** — 200-500ms between button press and actual capture
   while CameraX runs fresh 3A (autofocus/exposure/whitebalance). Any phone
   movement during this window changes what gets captured.
2. **Resolution mismatch** — Snap captured 4K JPEG vs auto-detect's 480p
   analysis frame. YOLO was trained on 600x373 images, so 4K input loses
   detail during the YOLO internal resize to 640x640.
3. **Aspect-ratio drift** — Capture used 4:3 sensor format vs preview's
   16:9, giving YOLO different ScanGuide ROI geometry per mode.

Freeze sidesteps all three by simply reusing the analysis pipeline's output.

## Current Accuracy Baseline

Current validation command:

```powershell
.\scripts\run_val_report.ps1 -Dataset C:\Users\insta\Desktop\dataset -Split val
```

Current `val` result:

```text
images: 362
E2E accuracy: 350/362 = 96.7%
crop padding: x=6, y=0
YOLO IoU@0.50: 98.8%
YOLO IoU@0.75: 97.4%
```

Current Android test APK version:

```text
versionName: 1.1.0
versionCode: 4
```

The crop padding is intentionally asymmetric. Horizontal padding keeps tight
YOLO boxes from clipping digits, while vertical padding is `0` to avoid pulling
nearby lines into the PPOCR recognition crop.

## Repository Contents

Important Android files:

- `app/src/main/java/com/example/foodocr/MenuActivity.kt` — launcher with two
  mode buttons
- `app/src/main/java/com/example/foodocr/MainActivity.kt` — 自動偵測 (Auto)
- `app/src/main/java/com/example/foodocr/SnapActivity.kt` — 拍照確認 (Snap,
  Freeze design)
- `app/src/main/java/com/example/foodocr/BoundingBoxOverlayView.kt` — red
  YOLO box overlay used by snap mode
- `app/src/main/java/com/example/foodocr/ScanGuide.kt` — green target frame
- `app/src/main/java/com/example/foodocr/offline/` — pipeline + ONNX runners
- `app/src/main/res/layout/activity_menu.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_snap.xml`

Model assets committed in the repo:

- `app/src/main/assets/models/yolo_date.onnx`
- `app/src/main/assets/models/rec_finetuned.onnx`
- `app/src/main/assets/models/ppocr_keys_v1.txt`

Validation/report tools:

- `tools/mobile_onnx_html_report.py`
- `tools/evaluate_mobile_onnx.py`
- `tools/requirements.txt`

Convenience scripts:

- `scripts/build_release_apk.ps1`
- `scripts/run_val_report.ps1`

Generated files are intentionally ignored:

- `app/build/`
- `outputs/`
- `*.apk`
- `.venv/`

## Prerequisites

For APK build:

- Android Studio or Android SDK installed
- JDK 17
- Gradle wrapper from this repo

For report generation:

- Python 3.10+
- Python packages from `tools/requirements.txt`

Optional Python setup:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r tools\requirements.txt
```

## Build APK

### Preferred: GitHub Actions (CI)

`.github/workflows/build-apk.yml` builds a debug APK on every push to `main`
and on manual `workflow_dispatch`. Use this path unless you specifically need
a local build.

1. Push to `main` (or trigger the workflow manually).
2. Open <https://github.com/install88/testMobileOcr/actions> and wait for the
   green check (≈5-10 minutes).
3. Open the run, scroll to **Artifacts**, download `app-debug-{run_number}.zip`.
4. Unzip → install `app-debug.apk` on the phone.

Artifact size is ~47 MB (debug build, native libs deflated). The historical
local-build size of ~81 MB came from `extractNativeLibs=true` (uncompressed
`.so` files in the APK). Both produce functionally identical apps.

### Local build (Windows — currently broken)

On Windows machines with certain antivirus / security software configurations,
the Gradle daemon fails to initialize its internal loopback pipe:

```text
java.io.IOException: Unable to establish loopback connection
  ... sun.nio.ch.WEPollSelectorImpl.<init> ...
  ... sun.nio.ch.UnixDomainSockets.connect0 ...
```

Tried and confirmed NOT to fix: Adoptium JDK 17.0.19, Microsoft OpenJDK 17.0.17,
Android Studio JBR (JDK 21), `--no-daemon`, Windows reboot,
`-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.PollSelectorProvider`.

Root cause is an OS-level block on `AF_UNIX` (Unix Domain Sockets on Windows),
typically from a security product. If you must build locally, identify and
whitelist the blocker. Otherwise use CI.

Once unblocked, the standard commands work:

```powershell
.\scripts\build_release_apk.ps1
# or
.\gradlew.bat :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected phone:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Generate Validation Report

Dataset layout expected by the report tool:

```text
dataset/
  val/
  val_label.txt
  train/
  train_label.txt
```

Run:

```powershell
.\scripts\run_val_report.ps1 -Dataset C:\Users\insta\Desktop\dataset -Split val
```

Quick environment check with only 10 images:

```powershell
.\scripts\run_val_report.ps1 -Dataset C:\Users\insta\Desktop\dataset -Split val -Limit 10
```

Outputs:

```text
outputs/report.html
outputs/mobile_onnx_report_val.jsonl
```

The HTML report shows:

- original image
- green GT polygons
- orange/red YOLO boxes
- raw REC text
- regex date result
- GT date
- best IoU per GT box

## Clone On Another Computer

1. Clone the repo.
2. Open it once in Android Studio or make sure Android SDK/JDK 17 are available.
3. Build APK:

```powershell
.\scripts\build_release_apk.ps1
```

4. To run report, install Python dependencies:

```powershell
python -m pip install -r tools\requirements.txt
```

5. Run report with the local dataset path:

```powershell
.\scripts\run_val_report.ps1 -Dataset D:\path\to\dataset -Split val
```

## Notes

- The app is offline and does not call remote APIs.
- Keep the ONNX assets in `app/src/main/assets/models/`.
- Do not commit generated APKs or `outputs/` reports.
- Android OpenCV `CV_8UC3` image data must be read as bytes and converted with
  `value & 0xFF`; reading it into `DoubleArray` crashes on device.
- ONNX Runtime Android may return model outputs as Java arrays such as
  `float[][][]`, not always as `OnnxTensor`; decoders must handle both forms.
- If accuracy drops after a model or preprocessing change, regenerate
  `outputs/report.html` and compare REC text, regex result, and crop images.
