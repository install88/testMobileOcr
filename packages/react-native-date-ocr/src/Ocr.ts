import OcrModule from './OcrModule'
import type { OcrOptions, TextLine } from './types'

/**
 * Drop-in replacement for `@gutenye/ocr-react-native`'s default export.
 *
 * Usage in the consuming app:
 *
 *   import Ocr from 'react-native-date-ocr'
 *   const ocr = await Ocr.create({
 *     models: {
 *       detectionPath: `${OCR_BUNDLE_PATH}/yolo_date.onnx`,
 *       recognitionPath: `${OCR_BUNDLE_PATH}/rec_finetuned.onnx`,
 *       dictionaryPath: `${OCR_BUNDLE_PATH}/ppocr_keys_v1.txt`,
 *     },
 *   })
 *   const lines = await ocr.detect('file:///.../cropped.jpg')
 *   // lines: TextLine[] = [{ text, score, frame: { top, left, width, height } }, ...]
 */
export class Ocr {
  static async create(options: OcrOptions = {}): Promise<Ocr> {
    await OcrModule.create(options)
    return new Ocr()
  }

  async detect(rawImagePath: string): Promise<TextLine[]> {
    const imagePath = rawImagePath.replace(/^file:\/\//, '')
    const result = (await OcrModule.detect(imagePath)) as TextLine[]
    return result
  }

  async close(): Promise<void> {
    await OcrModule.close()
  }
}
