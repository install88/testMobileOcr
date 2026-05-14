# Food OCR Offline Android

Android offline package-date recognition app based on the `yoloOcr` v2.2
pipeline.

The app does not call any API. When the ONNX assets are present, it runs:

```text
CameraX frame -> scan-guide ROI -> YOLO date detector -> crop -> PPOCR rec -> date parser -> MFG/EXP assignment
```

If any model asset is missing, the app falls back to the older ML Kit analyzer.

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
versionName: 1.0.2
versionCode: 3
```

The crop padding is intentionally asymmetric. Horizontal padding keeps tight
YOLO boxes from clipping digits, while vertical padding is `0` to avoid pulling
nearby lines into the PPOCR recognition crop.

## Repository Contents

Important Android files:

- `app/src/main/java/com/example/foodocr/MainActivity.kt`
- `app/src/main/java/com/example/foodocr/ScanGuide.kt`
- `app/src/main/java/com/example/foodocr/offline/`
- `app/src/main/res/layout/activity_main.xml`

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

From the repo root:

```powershell
.\scripts\build_release_apk.ps1
```

Or run Gradle directly:

```powershell
.\gradlew.bat :app:assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

The current release build uses the debug signing config so it can be installed
for local testing. Use a real keystore before production distribution.

Install to a connected phone:

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
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
