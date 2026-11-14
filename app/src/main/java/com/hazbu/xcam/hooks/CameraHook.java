package com.hazbu.xcam.hooks;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.hazbu.xcam.xposed.XCamModule;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the original Camera API (android.hardware.Camera).
 * Consolidated all Legacy Camera logic including Renderer hooks.
 */
public class CameraHook {
    private static final long PREVIEW_RESTART_DELAY_MS = 100;

    private final XCamModule module;
    private final Handler mainHandler;
    private final Set<String> hookedPreviewCallbackClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Surface viewfinderSurface;
    private SurfaceTexture dummySurfaceTexture;

    public CameraHook(XCamModule module) {
        this.module = module;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera API (Legacy)");
            hookCameraParameters(param);
            hookViewfinder(param);
            hookPreviewCallbacks(param);
            hookCameraEventHandler(param);
            hookDisplayOrientation(param);
            hookRelease(param);
            hookTakePicture(param);
            hookLegacyMethod1(param);
            module.logHook("[+] Camera API (Legacy) hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] FAILED to initialize Camera API hooks: " + t.getMessage());
        }
    }

    private void hookLegacyMethod1(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi") Class<?> legacyDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.legacy.LegacyCameraDevice");
            for (Method method : legacyDeviceClass.getDeclaredMethods()) {
                if (method.getName().equals("produceFrame") && method.getParameterTypes().length == 5) {
                    module.hook(method).intercept(chain -> {
                        int format = (int) chain.getArgs().get(4);
                        if (format == 0x21) {
                            int width = (int) chain.getArgs().get(2);
                            int height = (int) chain.getArgs().get(3);
                            byte[] replacement = module.handleCapture(width, height);
                            if (replacement != null) {
                                Object[] newArgs = chain.getArgs().toArray();
                                newArgs[1] = replacement;
                                return chain.proceed(newArgs);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: LegacyCameraDevice#produceFrame");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookViewfinder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setPreviewDisplay = Camera.class.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);
            module.hook(setPreviewDisplay).intercept(chain -> {
                SurfaceHolder holder = (SurfaceHolder) chain.getArgs().get(0);
                if (holder != null && holder.getSurface() != null && module.getMediaPath() != null) {
                    viewfinderSurface = holder.getSurface();
                    module.registerPreviewSurface(viewfinderSurface);
                    if (dummySurfaceTexture == null) {
                        dummySurfaceTexture = new SurfaceTexture(10);
                    }
                    try {
                        Camera camera = (Camera) chain.getThisObject();
                        camera.setPreviewTexture(dummySurfaceTexture);
                        module.logHook("[+] Hijacked Camera#setPreviewDisplay -> diverted camera to dummy texture");
                        return null;
                    } catch (Throwable ignored) {}
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewDisplay");

            Method setPreviewTexture = Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            module.hook(setPreviewTexture).intercept(chain -> {
                SurfaceTexture texture = (SurfaceTexture) chain.getArgs().get(0);
                if (texture != null && module.getMediaPath() != null) {
                    if (texture == dummySurfaceTexture) return chain.proceed();
                    if (dummySurfaceTexture == null) {
                        dummySurfaceTexture = new SurfaceTexture(10);
                    }
                    Camera camera = (Camera) chain.getThisObject();
                    try {
                        camera.setPreviewTexture(dummySurfaceTexture);
                        module.logHook("[+] Hijacked Camera#setPreviewTexture -> diverted camera to dummy texture");
                        return null;
                    } catch (Throwable ignored) {}
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewTexture");
        } catch (Throwable t) {
            module.logHook("[!] Viewfinder hook failed: " + t.getMessage());
        }
    }

    private void hookPreviewCallbacks(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> cameraClass = Camera.class;
            for (Method method : cameraClass.getDeclaredMethods()) {
                if (method.getName().equals("addCallbackBuffer") ||
                    method.getName().equals("setPreviewCallbackWithBuffer") ||
                    method.getName().equals("setPreviewCallback")) {
                    module.hook(method).intercept(chain -> {
                        Camera camera = (Camera) chain.getThisObject();
                        if (module.getMediaPath() != null) {
                            module.recordPipelineNode("Camera1(PreviewCallback)");
                            Object arg = chain.getArgs().get(0);
                            String cls = arg.getClass().getName();
                            if (!hookedPreviewCallbackClasses.contains(cls)) {
                                hookedPreviewCallbackClasses.add(cls);
                                module.logHook("[*] Activity: Camera1 PreviewCallback class: " + cls);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: Camera#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] PreviewCallback hook failed: " + t.getMessage());
        }
    }

    private void hookCameraEventHandler(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> cameraClass = Camera.class;
            for (Method method : cameraClass.getDeclaredMethods()) {
                if (method.getName().equals("native_fireEventHandler") || method.getName().equals("fireEventHandler")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            module.recordPipelineNode("Camera1(EventHandler)");
                            module.handleCamera1Preview(dummySurfaceTexture);
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: Camera#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] EventHandler hook failed: " + t.getMessage());
        }
    }

    private void hookDisplayOrientation(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method method = Camera.class.getDeclaredMethod("setDisplayOrientation", int.class);
            module.hook(method).intercept(chain -> {
                if (module.getMediaPath() != null && dummySurfaceTexture != null) {
                    mainHandler.postDelayed(() -> {
                        module.handleCamera1Preview(dummySurfaceTexture);
                    }, PREVIEW_RESTART_DELAY_MS);
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setDisplayOrientation");
        } catch (Throwable t) {
            module.logHook("[!] DisplayOrientation hook failed: " + t.getMessage());
        }
    }

    private void hookRelease(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method method = Camera.class.getDeclaredMethod("release");
            module.hook(method).intercept(chain -> {
                module.logHook("[*] Activity: Camera#release");
                if (dummySurfaceTexture != null) {
                    try { dummySurfaceTexture.release(); } catch (Throwable ignored) {}
                    dummySurfaceTexture = null;
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#release");
        } catch (Throwable t) {
            module.logHook("[!] Release hook failed: " + t.getMessage());
        }
    }

    private void hookTakePicture(XposedModuleInterface.PackageReadyParam param) {
        try {
            // Hook shutter callback
            for (Method method : Camera.class.getDeclaredMethods()) {
                if (method.getName().equals("takePicture")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            module.triggerCaptureState();
                            module.recordPipelineNode("Camera1(takePicture)");
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: Camera#takePicture");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] takePicture hook failed: " + t.getMessage());
        }
    }

    private void hookCameraParameters(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method method = Camera.class.getDeclaredMethod("getParameters");
            module.hook(method).intercept(chain -> {
                if (module.getMediaPath() != null && dummySurfaceTexture != null) {
                    mainHandler.postDelayed(() -> {
                        module.handleCamera1Preview(dummySurfaceTexture);
                    }, PREVIEW_RESTART_DELAY_MS);
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#getParameters");
        } catch (Throwable t) {
            module.logHook("[!] CameraParameters hook failed: " + t.getMessage());
        }
    }
}
