# Handoff Notes

## Current State

This repo is ready to clone on another Windows/Android development machine and
rebuild locally.

Completed:

- Android CameraX scan screen
- visible scan guide box for users to align the date
- offline ONNX analyzer using YOLO + PPOCR rec
- local model assets under `app/src/main/assets/models/`
- raw debug area in the app showing `REC` and regex date output
- MFG/EXP assignment heuristics
- validation report generator with image overlays
- PowerShell scripts for APK build and report generation

## Main Commands

Build release APK:

```powershell
.\scripts\build_release_apk.ps1
```

Generate validation report:

```powershell
python -m pip install -r tools\requirements.txt
.\scripts\run_val_report.ps1 -Dataset C:\Users\insta\Desktop\dataset -Split val
```

## Important Files

- `README.md`
- `docs/OFFLINE_ONNX_DEPLOYMENT.md`
- `scripts/build_release_apk.ps1`
- `scripts/run_val_report.ps1`
- `tools/mobile_onnx_html_report.py`
- `app/src/main/java/com/example/foodocr/offline/`
- `app/src/main/assets/models/`

## Current Validation Result

Using the local `val` dataset:

```text
images: 362
pass: 350
fail: 12
E2E accuracy: 96.7%
crop padding: x=6, y=0
```

Latest Android APK metadata:

```text
versionName: 1.0.2
versionCode: 3
```

Main remaining failures are mostly recognition mistakes, with a few parser or
matching edge cases. The earlier large drop was mostly caused by vertical crop
padding pulling unrelated text into the PPOCR recognition crop.

## Device Fixes Already Applied

- Fixed Android OpenCV `CV_8UC3` tensor conversion by reading Mat data as
  `ByteArray` instead of `DoubleArray`.
- Fixed ONNX Runtime Android output decoding by supporting Java array outputs
  such as `float[][][]`, not only `OnnxTensor`.

## What Not To Commit

Do not commit:

- `app/build/`
- `outputs/`
- generated `*.apk`
- `.venv/`
- local Android Studio files such as `local.properties`

The ONNX model assets should be committed because they are required for a fresh
clone to build and run the offline pipeline.

## Next Improvement Ideas

- Add an in-app still-image import/replay mode
- Add OBB or four-point crop correction for slanted date text
- Fine-tune rec on the remaining failure crops
- Add a small regression subset to run quickly before each APK build
