package com.hazbu.xcam.xposed;

import com.hazbu.xcam.hooks.*;
import io.github.libxposed.api.XposedModuleInterface;

class XCamInjectors {

    final CameraHook cameraHook;
    final Camera2Hook camera2Hook;
    final ImageReaderHook imageReaderHook;
    final CaptureHook captureHook;
    final WebRtcHook webRtcHook;
    final AudioHook audioHook;

    XCamInjectors(XCamModule module) {
        cameraHook = new CameraHook(module);
        camera2Hook = new Camera2Hook(module);
        imageReaderHook = new ImageReaderHook(module);
        captureHook = new CaptureHook(module, imageReaderHook);
        webRtcHook = new WebRtcHook(module);
        audioHook = new AudioHook(module);
    }

    void install(XposedModuleInterface.PackageReadyParam param) {
        cameraHook.install(param);
        camera2Hook.install(param);
        captureHook.install(param);
        imageReaderHook.install(param);
        webRtcHook.install(param);
        audioHook.install(param);

        module.logInit("[+] Core camera & audio hooks installed successfully");
    }
}
