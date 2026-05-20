import { NativeModules, Platform } from 'react-native'

const LINKING_ERROR =
  "The package 'react-native-date-ocr' doesn't seem to be linked. Make sure:\n" +
  "- You have rebuilt the app after installing the package\n" +
  "- You are not using Expo Go\n"

const { RNDateOcr } = NativeModules as {
  RNDateOcr?: {
    create(options: object): Promise<void>
    detect(imagePath: string): Promise<unknown[]>
    close(): Promise<void>
  }
}

if (!RNDateOcr && Platform.OS === 'android') {
  throw new Error(LINKING_ERROR)
}

export default RNDateOcr!
