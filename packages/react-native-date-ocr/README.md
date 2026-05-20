# react-native-date-ocr

React Native OCR module specialized for product **expiry / manufacture date**
recognition. Drop-in replacement for `@gutenye/ocr-react-native` with a
pipeline tuned for date strings.

## What it does

```
Input JPEG  →  YOLO11 date region detector  →  fine-tuned PPOCRv4 recognizer
            →  multi-variant preprocessing (CLAHE / Otsu / adaptive / deskew / 2× upscale)
            →  Regex-based date format normalization (OfflineDatePatterns, 20+ patterns)
            →  Returns TextLine[] with { text, score, frame }
```

Compared to generic PaddleOCR:
- Detector is fine-tuned specifically for date regions → fewer false positives,
  better localization on cluttered packaging
- Recognizer is fine-tuned on date-heavy samples → less character confusion
  (`E↔2`, `O↔0`, etc.)
- Multi-variant preprocessing improves accuracy on bad-lighting / faded prints

## Platform support

| Platform | Status |
|----------|--------|
| Android arm64-v8a | ✅ Primary target (Carrefour HHT devices) |
| Android x86_64 | ⚠️ Will not run on emulator with arm64-only APK (OpenCV `.so` can't translate). Build the host app with `x86_64` in `abiFilters` for emulator testing. |
| iOS | ❌ Not supported |

## API

API shape mirrors `@gutenye/ocr-react-native` so the host app can swap by
changing one `import`.

```ts
import Ocr from 'react-native-date-ocr'

const ocr = await Ocr.create({
  models: {
    detectionPath:   '/data/.../yolo_date.onnx',
    recognitionPath: '/data/.../rec_finetuned.onnx',
    dictionaryPath:  '/data/.../ppocr_keys_v1.txt',
  },
  isDebug: false,
})

// NOTE: ours returns a Promise; gutenye uses JSI sync return.
// Host apps must add `await` if migrating from gutenye.
const lines = await ocr.detect('file:///cache/foo.jpg')
// lines: [
//   { text: 'EXP:2026.04.30', score: 0.93, frame: { top, left, width, height } },
//   ...
// ]

await ocr.close()
```

## Live Camera Frame Processor (v1.1.0+)

For live-preview UX (red bounding box overlay updating in real time while
the user aims the camera at a date — see testMobileOcr's `SnapActivity`),
v1.1.0 adds a vision-camera Frame Processor Plugin.

### Prerequisites in the host app

```sh
yarn add react-native-vision-camera@^4
yarn add react-native-worklets-core
```

Add `react-native-worklets-core/plugin` to `babel.config.js` **before** the
reanimated plugin if you use reanimated:

```js
module.exports = {
  presets: ['module:metro-react-native-babel-preset'],
  plugins: [
    'react-native-worklets-core/plugin',
    // 'react-native-reanimated/plugin', // if applicable
  ],
}
```

### Usage

```tsx
import { Camera, useFrameProcessor } from 'react-native-vision-camera'
import { Worklets } from 'react-native-worklets-core'
import Ocr, { detectDateFrame, DateFrameResult } from 'react-native-date-ocr'

// 1. Initialize the shared OCR pipeline once (e.g. on screen mount).
await Ocr.create({ models: { detectionPath, recognitionPath, dictionaryPath } })

// 2. Get a JS-thread setter the worklet can invoke.
const onResult = Worklets.createRunOnJS((r: DateFrameResult) => setLastResult(r))

// 3. Define the frame processor (runs on a worklet thread per frame).
const frameProcessor = useFrameProcessor((frame) => {
  'worklet'
  const r = detectDateFrame(frame)
  if (r) onResult(r)
}, [])

// 4. Wire it into the Camera. Throttle to 2-3 FPS so YOLO + rec doesn't
//    overheat the device.
<Camera
  ...cameraProps
  frameProcessor={frameProcessor}
  frameProcessorFps={3}
/>
```

The `DateFrameResult` shape:

```ts
{
  width: 200,  height: 600,           // ROI bitmap dims; box coords are in this space
  detections: [{ left, top, right, bottom, conf }, ...],
  candidates: [{ text, date, conf }, ...],   // same index as detections
  elapsedMs: 187,
}
```

Use `detections` to draw overlay boxes (normalize to ROI dims, multiply onto
your camera view's ScanGuide region). Use `candidates[i].text` to feed your
JS-side date regex (mirrors what `ocr.detect()` returns — same pipeline).

### Static `Ocr.detect` and live `detectDateFrame` share one pipeline

The native module lazily initializes the YOLO + PPOCR pipeline on first
`Ocr.create()` and exposes it process-wide. Both `ocr.detect(path)` and the
frame processor read from the same instance, so models are loaded once even
if you mix both APIs in the same screen.

### Differences from `@gutenye/ocr-react-native`

| Aspect | gutenye | this module |
|--------|---------|-------------|
| Bridge | JSI (C++, sync) | Standard RN bridge (Promise) |
| `detect()` return | `TextLine[]` directly | `Promise<TextLine[]>` — **caller must `await`** |
| Models param | Optional (has defaults) | **Required** — pass explicit paths |
| Det model | `ch_PP-OCRv4_det_infer.onnx` (generic) | `yolo_date.onnx` (date-specific YOLO11) |
| Rec model | `ch_PP-OCRv4_rec_infer.onnx` (generic) | `rec_finetuned.onnx` (date fine-tuned) |
| Classifier | `ch_ppocr_mobile_v2.0_cls_infer.onnx` | not used (date orientation handled by detector) |

## Installation

### Add the package

**Option A: local file dependency (for development)**

```json
// host_app/package.json
"dependencies": {
  "react-native-date-ocr": "file:../path/to/testMobileOcr/packages/react-native-date-ocr"
}
```

**Option B: tarball (recommended for sharing with teammates)**

```sh
cd packages/react-native-date-ocr
yarn pack          # produces react-native-date-ocr-v1.1.0.tgz
# copy the tgz into the host app
cd ../../host_app
yarn add ./libs/react-native-date-ocr-v1.1.0.tgz
```

**Option C: GitHub / internal registry**

```json
"react-native-date-ocr": "git+ssh://git@github.com/install88/testMobileOcr.git#main"
```

### Wire it into the host

```sh
yarn install                                       # picks up the dep
cd android && ./gradlew clean                      # clear any stale cache
cd .. && yarn android                              # rebuild — autolinking finds the module
```

No manual `MainApplication.java` edits needed — React Native autolinking picks
up `react-native.config.js` from the module.

### Ship the model files

The module does **not** bundle the ONNX models inside the AAR. The host app is
responsible for getting them onto the device and passing their filesystem
paths to `Ocr.create({ models })`. Three files are required:

| File | Size | Source |
|------|------|--------|
| `yolo_date.onnx` | ~10.6 MB | `testMobileOcr/app/src/main/assets/models/` |
| `rec_finetuned.onnx` | ~10.8 MB | same |
| `ppocr_keys_v1.txt` | ~26 KB | same (also used by gutenye, can be shared) |

Carrefour HHT already has a download infrastructure (`OcrDownloader.jsx` +
`utils/Ocr.js`) that fetches `ocr.zip` from a per-environment URL and unzips
into `${RNFS.CachesDirectoryPath}/ocr/ocr.bundle/`. To migrate:

1. Add the two new models to the existing zip → re-zip → upload to all
   environments (PPR / PRE / QA / CR / DR / production).
2. Bump `OCR_BUNDLE_VERSION` in `utils/Ocr.js` (`v2` → `v3`).
3. Bump `OCR_BUNDLE_SIZE` to the new total directory size in bytes.
4. Done — `OcrDownloader` will see the version/size mismatch on next launch,
   download v3, and store under the same path our `Ocr.create()` reads from.

## Native dependencies

Pulled in by `android/build.gradle`:

- `com.microsoft.onnxruntime:onnxruntime-android:1.17.3` (matches gutenye's
  version — required because Carrefour pins `minSdk=21`; ONNX 1.18+ needs 24)
- `org.opencv:opencv:4.13.0`

`packagingOptions { pickFirst ... }` in `android/build.gradle` handles `.so`
conflicts if the host app also bundles ONNX Runtime / OpenCV via another
module (e.g. while `@gutenye/ocr-react-native` is still installed).

## Building locally

The module is not built standalone — it is built when consumed by an RN host
app via Gradle composite build. To verify it compiles:

```sh
# 1. yarn link or file: install into a host RN app
# 2. cd host_app/android && ./gradlew :react-native-date-ocr:assembleRelease
# 3. Output AAR is at:
#    host_app/node_modules/react-native-date-ocr/android/build/outputs/aar/
```

## Sample app

`testMobileOcr/app/` is a standalone Android app that exercises the same
pipeline directly (no RN bridge). Use it for fast iteration on the Kotlin
code, OCR accuracy testing, and as a reference for what the pipeline can do.

```sh
cd testMobileOcr
gradlew.bat assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk (~84 MB, arm64-only)
```

## License

UNLICENSED — internal use.
