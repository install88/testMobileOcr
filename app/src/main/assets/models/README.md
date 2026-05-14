# Model Assets

These files are required for the offline ONNX pipeline:

- `yolo_date.onnx`
- `rec_finetuned.onnx`
- `ppocr_keys_v1.txt`

They are committed with the project so a fresh clone can build an APK without
downloading models separately.

If any file is missing, the Android app falls back to the ML Kit analyzer.
