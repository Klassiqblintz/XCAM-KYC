package com.hazbu.xcam.hooks;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.hazbu.xcam.data.Constants;
import com.hazbu.xcam.xposed.XCamModule;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Consolidated Capture Hook.
 * Handles all image data injection points (BitmapFactory, Bitmap.compress, MediaStore, FileOutput).
 * Prevents redundant extractions and recursion.
 */
public class CaptureHook {
    private final XCamModule module;
    private final ImageReaderHook imageReaderHook;

    private static class RecorderTarget {
        String path;
        FileDescriptor fd;
        long startMediaPosMs = 0;
        long startWallClockMs = 0;

        RecorderTarget(String path, FileDescriptor fd) {
            this.path = path;
            this.fd = fd;
        }
    }

    private final Map<Object, RecorderTarget> recorderTargets = new ConcurrentHashMap<>();
    private volatile RecorderTarget lastRecorderTarget;
    private volatile long lastStartMediaPosMs = 0;
    private volatile long lastStartWallClockMs = 0;

    public CaptureHook(XCamModule module, ImageReaderHook imageReaderHook) {
        this.module = module;
        this.imageReaderHook = imageReaderHook;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Capture Diversion Hooks");

            hookBitmapFactory(param);

            hookBitmapCompress(param);

            hookMediaStore(param);

            hookMediaRecorder(param);

            hookMediaCodec(param);

            module.logHook("[+] Capture Diversion Hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Capture Hooks installation failed: " + t.getMessage());
        }
    }

    private void hookBitmapFactory(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method method : BitmapFactory.class.getDeclaredMethods()) {
                if (method.getName().equals("decodeByteArray")) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            int activeW = imageReaderHook.getActiveCaptureWidth();
                            int activeH = imageReaderHook.getActiveCaptureHeight();
                            int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                            int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                            byte[] injected = module.handleCapture(targetW, targetH);
                            if (injected != null) {
                                BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                boundOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                int actualW = boundOpts.outWidth > 0 ? boundOpts.outWidth : targetW;
                                int actualH = boundOpts.outHeight > 0 ? boundOpts.outHeight : targetH;
                                String nodeDesc = "BitmapFactory#decodeByteArray(" + actualW + "x" + actualH + ")";
                                module.recordPipelineNode(nodeDesc);
                                module.reportPipelineCapture(actualW, actualH, injected);
                                module.logHook("[*] Activity: Captured -> Injected into " + nodeDesc);
                                Object[] args = chain.getArgs().toArray();
                                args[0] = injected;
                                if (args.length >= 3) args[2] = injected.length;
                                return chain.proceed(args);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeByteArray");
                }

                if (method.getName().equals("decodeStream") && method.getParameterTypes().length >= 1) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            int activeW = imageReaderHook.getActiveCaptureWidth();
                            int activeH = imageReaderHook.getActiveCaptureHeight();
                            int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                            int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                            byte[] injected = module.handleCapture(targetW, targetH);
                            if (injected != null) {
                                BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                boundOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                int actualW = boundOpts.outWidth > 0 ? boundOpts.outWidth : targetW;
                                int actualH = boundOpts.outHeight > 0 ? boundOpts.outHeight : targetH;
                                module.reportPipelineCapture(actualW, actualH, injected);
                                String nodeDesc = "BitmapFactory#decodeStream(" + actualW + "x" + actualH + ")";
                                module.recordPipelineNode(nodeDesc);
                                module.logHook("[*] Activity: Captured -> Injected into " + nodeDesc);

                                InputStream fake = new ByteArrayInputStream(injected);
                                List<Object> args = chain.getArgs();
                                for (int i = 0; i < args.size(); i++) {
                                    if (args.get(i) instanceof InputStream || args.get(i) instanceof FileDescriptor || args.get(i) instanceof String) {
                                        if (args.get(i) instanceof FileDescriptor) {
                                            File f = new File("/dev/null");
                                            try {
                                                args.set(i, new FileInputStream(f).getFD());
                                            } catch (Exception ignored) {}
                                        } else {
                                            args.set(i, fake);
                                        }
                                        break;
                                    }
                                }
                                return chain.proceed(args);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeStream");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] BitmapFactory hook failed: " + t.getMessage());
        }
    }

    private void hookBitmapCompress(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method method : Bitmap.class.getDeclaredMethods()) {
                if (method.getName().equals("compress")) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            int activeW = imageReaderHook.getActiveCaptureWidth();
                            int activeH = imageReaderHook.getActiveCaptureHeight();
                            int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                            int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                            byte[] injected = module.handleCapture(targetW, targetH);
                            if (injected != null) {
                                Bitmap.CompressFormat format = (Bitmap.CompressFormat) chain.getArgs()[0];
                                OutputStream out = (OutputStream) chain.getArgs()[2];
                                if (out != null && format != null) {
                                    try {
                                        out.write(injected);
                                        out.flush();
                                    } catch (Exception ignored) {}
                                    String nodeDesc = "Bitmap#compress(" + format + ") -> " + injected.length + " bytes";
                                    module.recordPipelineNode(nodeDesc);
                                    module.reportPipelineCapture(targetW, targetH, injected);
                                    module.logHook("[*] Activity: Captured -> Injected into " + nodeDesc);
                                }
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: Bitmap#compress");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Bitmap.compress hook failed: " + t.getMessage());
        }
    }

    private void hookMediaStore(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> resolverClass = param.getClassLoader().loadClass("android.content.ContentResolver");
            for (Method method : resolverClass.getDeclaredMethods()) {
                if (method.getName().equals("openFileDescriptor") || method.getName().equals("openOutputStream")) {
                    module.hook(method).intercept(chain -> {
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            module.triggerCaptureState();
                            module.recordPipelineNode("ContentResolver(" + method.getName() + ")");
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: ContentResolver#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] MediaStore hook failed: " + t.getMessage());
        }
    }

    private void hookMediaRecorder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> recorderClass = param.getClassLoader().loadClass("android.media.MediaRecorder");
            for (Method method : recorderClass.getDeclaredMethods()) {
                if (method.getName().equals("setOutputFile") || method.getName().equals("setOutputFile")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            Object arg0 = chain.getArgs()[0];
                            if (arg0 instanceof String) {
                                String path = (String) arg0;
                                module.recordPipelineNode("MediaRecorder#setOutputFile(" + path + ")");
                                RecorderTarget target = new RecorderTarget(path, null);
                                recorderTargets.put(chain.getThisObject(), target);
                                lastRecorderTarget = target;
                                module.logHook("[*] Activity: MediaRecorder recording to " + path);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: MediaRecorder#setOutputFile");
                }

                if (method.getName().equals("start")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            RecorderTarget target = recorderTargets.get(chain.getThisObject());
                            if (target != null) {
                                target.startMediaPosMs = module.getCurrentPosition();
                                target.startWallClockMs = SystemClock.uptimeMillis();
                                lastRecorderTarget = target;
                                lastStartMediaPosMs = target.startMediaPosMs;
                                lastStartWallClockMs = target.startWallClockMs;
                                module.recordPipelineNode("MediaRecorder#start");
                                module.logHook("[*] Activity: MediaRecorder started");
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: MediaRecorder#start");
                }

                if (method.getName().equals("stop")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null && lastRecorderTarget != null) {
                            long elapsed = (SystemClock.uptimeMillis() - lastStartWallClockMs);
                            long seekToMs = lastStartMediaPosMs + elapsed;
                            module.logHook("[*] Activity: MediaRecorder stopped, lastMediaPos=" + lastStartMediaPosMs);
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: MediaRecorder#stop");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] MediaRecorder hook failed: " + t.getMessage());
        }
    }

    private void hookMediaCodec(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> codecClass = param.getClassLoader().loadClass("android.media.MediaCodec");
            for (Method method : codecClass.getDeclaredMethods()) {
                if (method.getName().equals("queueInputBuffer") || method.getName().equals("releaseOutputBuffer")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            module.recordPipelineNode("MediaCodec(" + method.getName() + ")");
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: MediaCodec#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] MediaCodec hook failed: " + t.getMessage());
        }
    }
}
