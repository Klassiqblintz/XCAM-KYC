package com.hazbu.xcam.hooks;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.view.Surface;

import com.hazbu.xcam.xposed.XCamModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the Camera2 API (android.hardware.camera2) and ImageReader.
 * Consolidated all Camera2 logic including Hijacking and Surgical Diversion.
 */
public class Camera2Hook {
    private final XCamModule module;

    public Camera2Hook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera2 API");
            hookDiscovery(param);
            hookModernHijack(param);
            hookSurgicalDiverter(param);
            hookCaptureFailed(param);
            hookCleanup(param);
            module.logHook("[+] Camera2 API hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize Camera2 API hooks: " + t.getMessage());
        }
    }

    private void hookDiscovery(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi") Class<?> cameraDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraDeviceImpl");
            for (Method method : cameraDeviceClass.getDeclaredMethods()) {
                if (method.getName().startsWith("createCaptureSession")) {
                    module.hook(method).intercept(chain -> {
                        module.logHook("[*] Activity: CameraDeviceImpl#" + method.getName());
                        module.incrementSessionGeneration();
                        module.clearPreviewSurfaces();
                        for (Object arg : chain.getArgs()) {
                            inspectDiscoveryArgument(arg);
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: CameraDeviceImpl#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Discovery hook failed: " + t.getMessage());
        }
    }

    private void inspectDiscoveryArgument(Object arg) {
        if (arg instanceof Surface) {
            Surface s = (Surface) arg;
            module.logSessionOutput(s);
            if (!module.isNonPreviewSurface(s)) {
                String sStr = s.toString();
                if (sStr.contains("SurfaceTexture") || sStr.contains("SurfaceView") || sStr.contains("BLAST")) {
                    module.registerPreviewSurface(s);
                }
            }
        } else if (arg instanceof OutputConfiguration) {
            Surface s = ((OutputConfiguration) arg).getSurface();
            if (s != null) inspectDiscoveryArgument(s);
        } else if (arg instanceof Collection) {
            for (Object item : (Collection<?>) arg) inspectDiscoveryArgument(item);
        }
    }

    private void hookModernHijack(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> ocClass = param.getClassLoader().loadClass("android.hardware.camera2.params.OutputConfiguration");
            for (Constructor<?> constructor : ocClass.getDeclaredConstructors()) {
                module.hook(constructor).intercept(chain -> {
                    if (module.getMediaPath() == null) return chain.proceed();

                    Surface surface = null;
                    int surfaceIndex = -1;
                    List<Object> args = chain.getArgs();
                    for (int i = 0; i < args.size(); i++) {
                        if (args.get(i) instanceof Surface && ((Surface) args.get(i)).isValid()) {
                            surface = (Surface) args.get(i);
                            surfaceIndex = i;
                            break;
                        }
                    }

                    if (surface != null) {
                        if (module.isNonPreviewSurface(surface)) return chain.proceed();

                        String sStr = surface.toString();
                        boolean isPreview = module.isPreviewSurface(surface) ||
                                sStr.contains("SurfaceTexture") ||
                                sStr.contains("SurfaceView") ||
                                sStr.contains("BLAST");

                        if (isPreview) module.registerPreviewSurface(surface);

                        if (isPreview && !module.getPreviewSwapped()) {
                            module.logHook("[*] Hijacked OutputConfiguration: " + surface.toString());
                            module.handleModernPreview(surface);
                        }
                    }
                    return chain.proceed();
                });
            }
            module.logHook("[+] Hooked: OutputConfiguration constructors");
        } catch (Throwable t) {
            module.logHook("[!] ModernHijack failed: " + t.getMessage());
        }
    }

    private void hookSurgicalDiverter(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> sessionClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraCaptureSessionImpl");
            for (Method method : sessionClass.getDeclaredMethods()) {
                if (method.getName().equals("onPrepareResults") || method.getName().equals("onCaptureSequenceCompleted")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            module.handleModernPreview(module.getDummySurface());
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: CameraCaptureSessionImpl#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] SurgicalDiverter failed: " + t.getMessage());
        }
    }

    private void hookCaptureFailed(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> failureClass = param.getClassLoader().loadClass("android.hardware.camera2.CaptureFailure");
            for (Method method : failureClass.getDeclaredMethods()) {
                if (method.getName().equals("getCallback")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            module.incrementSessionGeneration();
                            module.clearPreviewSurfaces();
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: CaptureFailure#getCallback");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] CaptureFailed hook failed: " + t.getMessage());
        }
    }

    private void hookCleanup(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> sessionClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraCaptureSessionImpl");
            for (Method method : sessionClass.getDeclaredMethods()) {
                if (method.getName().equals("close")) {
                    module.hook(method).intercept(chain -> {
                        module.logHook("[*] Activity: CameraCaptureSession closed");
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: CameraCaptureSessionImpl#close");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Cleanup hook failed: " + t.getMessage());
        }
    }
}
