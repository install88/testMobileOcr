import { Ocr } from './Ocr'

export { Ocr }
export default Ocr
export type { OcrOptions, OcrModelPaths, TextLine, Frame } from './types'

// vision-camera Frame Processor API (new in v1.1.0).
// Only usable when the host app has react-native-vision-camera installed
// (declared as optional peerDependency in package.json).
export { detectDateFrame } from './frameProcessor'
export type {
  DateDetection,
  DateCandidate,
  DateFrameResult,
} from './frameProcessor'
