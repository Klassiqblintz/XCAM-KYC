package com.hazbu.xcam.xposed

import com.hazbu.xcam.hooks.*
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(
    private val module: XCamModule,
    val cameraHook: CameraHook,
    val camera2Hook: Camera2Hook,
    val imageReaderHook: ImageReaderHook,
    val captureHook: CaptureHook,
    val webRtcHook: WebRtcHook,
    val audioHook: AudioHook,
) {
    fun install(param: XposedModuleInterface.PackageReadyParam) {
        cameraHook.install(param)
        camera2Hook.install(param)
        captureHook.install(param)
        imageReaderHook.install(param)
        webRtcHook.install(param)
        audioHook.install(param)

        module.logInit("[+] Core camera & audio hooks installed successfully")
    }

    companion object {
        fun create(module: XCamModule): XCamInjectors {
            return XCamInjectors(
                module = module,
                cameraHook = CameraHook(module),
                camera2Hook = Camera2Hook(module),
                imageReaderHook = ImageReaderHook(module),
                captureHook = CaptureHook(module, ImageReaderHook(module)),
                webRtcHook = WebRtcHook(module),
                audioHook = AudioHook(module),
            )
        }
    }
}
