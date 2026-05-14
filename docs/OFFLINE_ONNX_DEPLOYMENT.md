# Offline ONNX Deployment

This project contains an Android offline implementation of the yoloOcr v2.2
pipeline:

```text
YOLO ONNX -> asymmetric crop -> PPOCRv4 rec ONNX -> date parser -> MFG/EXP assignment
```

## Model Assets

The app expects these files under `app/src/main/assets/models/`:

```text
yolo_date.onnx
rec_finetuned.onnx
ppocr_keys_v1.txt
```

They are small enough to commit in this repo. If any of the three files is
missing, `MainActivity` falls back to the older ML Kit analyzer.

## Current Crop Setting

The mobile crop setting is:

```text
horizontal padding: 6 px
vertical padding: 0 px
```

This was chosen from validation experiments:

```text
pad_x=12, pad_y=12 -> 90.9%
pad_x=12, pad_y=0  -> 96.4%
pad_x=6,  pad_y=0  -> 96.7%
```

The reason is practical: PPOCR rec reads the whole crop as one text line, so
vertical padding can pull in nearby rows and corrupt the recognized date.

## Build APK

From the repo root:

```powershell
.\scripts\build_release_apk.ps1
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

## Run Validation Report

Install Python dependencies first:

```powershell
python -m pip install -r tools\requirements.txt
```

Then run:

```powershell
.\scripts\run_val_report.ps1 -Dataset C:\Users\insta\Desktop\dataset -Split val
```

For a quick environment check:

```powershell
.\scripts\run_val_report.ps1 -Dataset C:\Users\insta\Desktop\dataset -Split val -Limit 10
```

Outputs:

```text
outputs/report.html
outputs/mobile_onnx_report_val.jsonl
```

The report includes YOLO boxes, GT polygons, raw rec text, regex result, final
match result, and best IoU per GT date box.

## Current Baseline

Latest local validation on the user's `val` dataset:

```text
images: 362
pass: 350
fail: 12
E2E accuracy: 96.7%
YOLO IoU@0.50: 98.8%
YOLO IoU@0.75: 97.4%
```

Latest Android APK metadata:

```text
versionName: 1.0.2
versionCode: 3
```

## Android Runtime Notes

Two Android-specific details are important for real device execution:

- OpenCV `CV_8UC3` Mats are converted to tensor input through `ByteArray`, then
  normalized as unsigned bytes with `value & 0xFF`. Do not use `DoubleArray`
  with `Mat.get(...)` for these 8-bit Mats on Android.
- ONNX Runtime Android can return output values as Java arrays such as
  `float[][][]`. The decoder goes through `OnnxTensorUtils.output(...)` so it can
  handle either `OnnxTensor` or Java array output.

## Parity Checklist

When accuracy changes, compare in this order:

1. YOLO boxes and confidence
2. Crop image and crop dimensions
3. PPOCR raw `REC` text
4. Regex extracted date
5. MFG/EXP assignment

Do not tune multiple stages at once. The report is meant to show which stage
actually broke.
