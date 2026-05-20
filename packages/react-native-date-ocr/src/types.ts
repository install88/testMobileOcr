/**
 * Public types — kept compatible with @gutenye/ocr-react-native so the
 * import can be swapped without touching downstream code.
 */

export interface OcrModelPaths {
  /** Absolute filesystem path to the YOLO date detector ONNX model. */
  detectionPath: string
  /** Absolute filesystem path to the PPOCR recognition ONNX model. */
  recognitionPath: string
  /** Absolute filesystem path to the dictionary file (e.g. ppocr_keys_v1.txt). */
  dictionaryPath: string
}

export interface OcrOptions {
  /**
   * Filesystem paths to the three model files. Required.
   * In Carrefour HHT, point these to the unzipped `ocr.bundle/` directory
   * under `RNFS.CachesDirectoryPath`.
   */
  models?: OcrModelPaths
  /** When true, native module logs initialization details. */
  isDebug?: boolean

  // The following fields are accepted for API compatibility with
  // @gutenye/ocr-react-native but are currently ignored by the
  // YOLO-based pipeline (which has fixed thresholds tuned for dates).
  recognitionImageMaxSize?: number
  detectionThreshold?: number
  detectionBoxThreshold?: number
  detectionUnclipRatiop?: number
  detectionUseDilate?: boolean
  detectionUsePolygonScore?: boolean
  useDirectionClassify?: boolean
}

export interface Frame {
  top: number
  left: number
  width: number
  height: number
}

export interface TextLine {
  text: string
  score: number
  frame: Frame
}
