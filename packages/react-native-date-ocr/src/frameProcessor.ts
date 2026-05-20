/**
 * vision-camera Frame Processor binding for the YOLO + PPOCR date pipeline.
 *
 * Pairs with the native `DateOcrFrameProcessorPlugin` registered under the
 * name `"detectDateFrame"` (see android/src/main/java/com/dateocr/rn/
 * DateOcrPackage.kt).
 *
 * Usage (from a React Native screen running react-native-vision-camera v4+):
 *
 *   import { Camera, useFrameProcessor } from 'react-native-vision-camera'
 *   import { detectDateFrame } from 'react-native-date-ocr'
 *   import { Worklets } from 'react-native-worklets-core'
 *
 *   const onResult = Worklets.createRunOnJS((result) => setLastResult(result))
 *
 *   const frameProcessor = useFrameProcessor((frame) => {
 *     'worklet'
 *     const r = detectDateFrame(frame)
 *     if (r) onResult(r)
 *   }, [])
 *
 *   <Camera ... frameProcessor={frameProcessor} frameProcessorFps={3} />
 *
 * Important: `Ocr.create({...})` must have been called BEFORE the first frame
 * arrives (so the shared pipeline is initialized). Otherwise `detectDateFrame`
 * returns null until create() completes.
 */
import type { Frame } from 'react-native-vision-camera'
import { VisionCameraProxy } from 'react-native-vision-camera'

/** A single YOLO detection, in ROI bitmap pixel coordinates. */
export interface DateDetection {
  /** Left edge in ROI pixel space (0..width). */
  left: number
  /** Top edge in ROI pixel space (0..height). */
  top: number
  /** Right edge in ROI pixel space. */
  right: number
  /** Bottom edge in ROI pixel space. */
  bottom: number
  /** YOLO confidence score (0..1). */
  conf: number
}

/** Recognition output for a single detected box. */
export interface DateCandidate {
  /** Raw PPOCR rec output (e.g. "EXP:2027.10.02"). Run your own date regex on this. */
  text: string
  /**
   * Date string extracted by the native-side regex, or empty string if the
   * native regex didn't recognize the format. For most consumers (e.g. the
   * Carrefour `getRecognizedData` JS regex), use `text` and ignore this field.
   */
  date: string
  /** Rec confidence score (0..1). */
  conf: number
}

/** Combined per-frame result from the native plugin. */
export interface DateFrameResult {
  /** ROI bitmap width in pixels — coordinate space of `detections[].*`. */
  width: number
  /** ROI bitmap height in pixels — coordinate space of `detections[].*`. */
  height: number
  /** YOLO detection boxes for this frame. Order is unspecified. */
  detections: DateDetection[]
  /** PPOCR rec output per detection. Same length & order as `detections`. */
  candidates: DateCandidate[]
  /** Wall-clock ms spent inside the pipeline for this frame. */
  elapsedMs: number
}

// VisionCameraProxy.initFrameProcessorPlugin must be called at module load,
// outside the worklet, so the registry lookup happens on the JS thread.
const plugin = VisionCameraProxy.initFrameProcessorPlugin('detectDateFrame', {})

/**
 * Run YOLO date detection + PPOCR rec on a vision-camera Frame.
 *
 * Must be called from inside a worklet (use `useFrameProcessor`).
 *
 * Returns null if:
 *   - The pipeline isn't yet initialized (Ocr.create() not finished).
 *   - The native plugin couldn't decode the frame.
 *   - The "detectDateFrame" plugin failed to register at module init (e.g.
 *     react-native-vision-camera missing or wrong version).
 */
export function detectDateFrame(frame: Frame): DateFrameResult | null {
  'worklet'
  if (plugin == null) {
    throw new Error(
      "react-native-date-ocr: 'detectDateFrame' frame processor plugin not " +
        'available. Verify react-native-vision-camera >=4.0.0 is installed ' +
        'and the native module is autolinked.',
    )
  }
  const result = plugin.call(frame) as DateFrameResult | null
  return result
}
