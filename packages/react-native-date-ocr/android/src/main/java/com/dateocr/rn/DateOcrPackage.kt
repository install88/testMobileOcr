package com.dateocr.rn

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.mrousavy.camera.frameprocessors.FrameProcessorPluginRegistry

class DateOcrPackage : ReactPackage {

    init {
        // Register the vision-camera Frame Processor Plugin once per process.
        // The lambda is invoked by vision-camera the first time JS calls
        // `VisionCameraProxy.initFrameProcessorPlugin('detectDateFrame', ...)`.
        //
        // Wrapped in try/catch so apps that consume this module WITHOUT
        // installing react-native-vision-camera (only the static `Ocr.detect`
        // API is in use) still link and run cleanly — the registry class is
        // simply absent at runtime in that case.
        try {
            FrameProcessorPluginRegistry.addFrameProcessorPlugin("detectDateFrame") { proxy, options ->
                DateOcrFrameProcessorPlugin(proxy, options)
            }
        } catch (_: Throwable) {
            // vision-camera not on classpath — frame processor plugin is unavailable
            // but the static OCR API still works.
        }
    }

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(DateOcrModule(reactContext))
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }
}
