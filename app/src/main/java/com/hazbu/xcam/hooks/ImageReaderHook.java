package com.hazbu.xcam.hooks;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;

import androidx.annotation.NonNull;

import com.hazbu.xcam.xposed.XCamModule;
import com.hazbu.xcam.utils.SystemUtils;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Specialized hooks for android.media.ImageReader.
 * Detects when ImageReader is created and when data starts flowing.
 * Added surgical injection for RGBA_8888 (0x1) format used by Instagram.
 */
public class ImageReaderHook {
    private final XCamModule module;
    private final Set<String> detectedFlows = new HashSet<>();
    private final Set<String> injectedFlows = new HashSet<>();
    private final Map<ImageReader, ReaderMetadata> readerTracker = new WeakHashMap<>();

    private volatile int activeCaptureWidth = 0;
    private volatile int activeCaptureHeight = 0;

    public int getActiveCaptureWidth() {
        return activeCaptureWidth;
    }

    public int getActiveCaptureHeight() {
        return activeCaptureHeight;
    }

    public void reset() {
        activeCaptureWidth = 0;
        activeCaptureHeight = 0;
        detectedFlows.clear();
        injectedFlows.clear();
        readerTracker.clear();
    }

    private static class ReaderMetadata {
        final int width;
        final int height;
        final int format;
        final String formatName;
        final long surfaceId;

        ReaderMetadata(int width, int height, int format, String formatName, long surfaceId) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.formatName = formatName;
            this.surfaceId = surfaceId;
        }

        @NonNull
        @Override
        public String toString() {
            return width + "x" + height + " " + formatName + " (ID: " + surfaceId + ")";
        }
    }

    public ImageReaderHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing ImageReader Hooks");
            hookImageReader(param);
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize ImageReader hooks: " + t.getMessage());
        }
    }

    private String getFormatName(int format) {
        switch (format) {
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.YUV_420_888: return "YUV_420_888";
            case 0x1: return "RGBA_8888";
            case 0x22: return "PRIVATE";
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR";
            default: return "Format(0x" + Integer.toHexString(format) + ")";
        }
    }

    private String getShortFormatName(int format) {
        switch (format) {
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.YUV_420_888: return "YUV";
            case 0x1: return "RGBA";
            case 0x22: return "PRIV";
            case ImageFormat.RAW_SENSOR: return "RAW";
            default: return "???";
        }
    }

    private void hookImageReader(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method method : ImageReader.class.getDeclaredMethods()) {
                if (method.getName().equals("newInstance")) {
                    module.hook(method).intercept(chain -> {
                        int w = (int) chain.getArgs().get(0);
                        int h = (int) chain.getArgs().get(1);
                        int format = (int) chain.getArgs().get(2);
                        String fmtName = getFormatName(format);

                        if (w > 0 && h > 0) {
                            if (format == ImageFormat.JPEG || (w * h >= activeCaptureWidth * activeCaptureHeight)) {
                                activeCaptureWidth = w;
                                activeCaptureHeight = h;
                            }
                        }

                        Object result = chain.proceed();
                        if (result instanceof ImageReader) {
                            ImageReader reader = (ImageReader) result;
                            long surfaceId = SystemUtils.INSTANCE.getSurfaceId(reader.getSurface());
                            readerTracker.put(reader, new ReaderMetadata(w, h, format, fmtName, surfaceId));
                            module.registerImageReaderSurface(reader.getSurface(), format, w, h);
                            String shortFmt = getShortFormatName(format);
                            module.recordPipelineNode("ImageReader(" + w + "x" + h + " " + shortFmt + ")");
                            module.logHook("[+] ImageReader.newInstance: " + w + "x" + h + " " + fmtName + " | ID: " + surfaceId);
                            module.showToast(w + "x" + h + " (" + fmtName + ")");
                        }
                        return result;
                    });
                    module.logHook("[+] Hooked: ImageReader.newInstance");
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    Class<?> imageClass = param.getClassLoader().loadClass("android.media.Image");
                    for (Method method : imageClass.getDeclaredMethods()) {
                        if (method.getName().equals("getFormat") || method.getName().equals("getWidth") || method.getName().equals("getHeight")) {
                            module.hook(method).intercept(chain -> {
                                if (chain.getThisObject() instanceof Image) {
                                    Image img = (Image) chain.getThisObject();
                                    if (img.getFormat() == ImageFormat.YUV_420_888) {
                                        module.recordPipelineNode("ImageReader(YUV420)");
                                    }
                                }
                                return chain.proceed();
                            });
                        }
                    }
                    module.logHook("[+] Hooked: Image class methods (API 33+)");
                } catch (ClassNotFoundException ignored) {}
            }

            // Hook acquireNextImage / acquireLatestImage
            for (Method method : ImageReader.class.getDeclaredMethods()) {
                if (method.getName().equals("acquireNextImage") || method.getName().equals("acquireLatestImage")) {
                    module.hook(method).intercept(chain -> {
                        if (module.getMediaPath() != null && chain.getThisObject() instanceof ImageReader) {
                            ImageReader reader = (ImageReader) chain.getThisObject();
                            ReaderMetadata meta = readerTracker.get(reader);
                            if (meta != null) {
                                String flowKey = meta.surfaceId + "::" + meta.format;
                                if (detectedFlows.add(flowKey)) {
                                    String flowDesc = "ImageReader(" + meta.width + "x" + meta.height + " " + meta.formatName + ")";
                                    module.recordPipelineNode(flowDesc);
                                    module.logHook("[*] Activity: " + flowDesc + " first data flow detected");

                                    byte[] replacement = module.handleCapture(meta.width, meta.height);
                                    if (replacement != null) {
                                        if (injectedFlows.add(flowKey)) {
                                            module.logHook("[+] Injection: " + flowKey + " -> captured frame injected");
                                        }
                                    }
                                }
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: ImageReader#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] ImageReader hook setup failed: " + t.getMessage());
        }
    }
}
