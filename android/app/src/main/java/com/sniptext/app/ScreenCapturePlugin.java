package com.sniptext.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.ActivityCallback;
import androidx.activity.result.ActivityResult;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import android.graphics.BitmapFactory;
import android.util.Log;

@CapacitorPlugin(name = "ScreenCapture")
public class ScreenCapturePlugin extends Plugin {

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private android.graphics.RectF pendingRect = null;
    
    private static JSObject pendingCaptureVal = null;
    private static final String CACHE_FILENAME = "pending_snip.jpg";

    @PluginMethod
    public void checkPendingCapture(PluginCall call) {
        if (pendingCaptureVal != null) {
            call.resolve(pendingCaptureVal);
            pendingCaptureVal = null;
            clearCacheFile();
        } else {
            // Try fallback from storage
            JSObject cached = loadFromCache();
            if (cached != null) {
                call.resolve(cached);
                clearCacheFile();
            } else {
                call.resolve();
            }
        }
    }

    @PluginMethod
    public void disableFloating(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            stopService();
            android.widget.Toast.makeText(getContext(), "Floating mode disabled", android.widget.Toast.LENGTH_SHORT).show();
            call.resolve();
        });
    }

    private void handleBubbleCapture(android.graphics.RectF rect) {
        getActivity().runOnUiThread(() -> {
            this.pendingRect = rect;
            if (mediaProjection != null) {
                takeScreenshot(null, rect);
                this.pendingRect = null;
            } else {
                startCapture(null);
            }
        });
    }

    @PluginMethod
    public void enableFloating(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getContext())) {
                    // Help for Android 13+ restricted settings
                    if (Build.VERSION.SDK_INT >= 33) {
                         android.widget.Toast.makeText(getContext(), "If switch is disabled, Go to App Info -> 3 dots -> Allow restricted settings", android.widget.Toast.LENGTH_LONG).show();
                    }
                    
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getContext().getPackageName()));
                    // Ensure the intent opens even if it's buried
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getActivity().startActivity(intent);
                    call.reject("Overlay Permission needed. Check App Info if restricted.");
                    return;
                }
            }

            Intent intent = new Intent(getContext(), ScreenCaptureService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                int retries = 0;
                @Override
                public void run() {
                    ScreenCaptureService service = ScreenCaptureService.getInstance();
                    if (service != null) {
                        service.showFloatingBubble(rect -> handleBubbleCapture(rect));
                        android.widget.Toast.makeText(getContext(), "Floating mode enabled!", android.widget.Toast.LENGTH_SHORT).show();
                        call.resolve();
                    } else if (retries < 10) {
                        retries++;
                        new Handler(Looper.getMainLooper()).postDelayed(this, 300);
                    } else {
                        call.reject("Could not enable floating mode.");
                    }
                }
            }, 300);
        });
    }

    @PluginMethod
    public void captureInsideApp(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            android.view.ViewGroup rootView = (android.view.ViewGroup) getActivity().getWindow().getDecorView();
            final int screenWidth = rootView.getWidth();
            final int screenHeight = rootView.getHeight();

            if (screenWidth <= 0 || screenHeight <= 0) {
                call.reject("Window dimensions zero");
                return;
            }

            SnippingOverlayView snippingView = new SnippingOverlayView(getActivity(), rect -> {
                getActivity().runOnUiThread(() -> {
                    android.view.View overlayToRemove = rootView.findViewWithTag("snipOverlayInApp");
                    if (overlayToRemove != null) rootView.removeView(overlayToRemove);

                    if (rect == null || rect.width() <= 10 || rect.height() <= 10) {
                        call.reject("User cancelled snip or snip too small");
                        return;
                    }

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Window window = getActivity().getWindow();
                            Bitmap bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
                            PixelCopy.request(window, bitmap, copyResult -> {
                                if (copyResult == PixelCopy.SUCCESS) {
                                    processInternalBitmap(call, bitmap, rect, screenWidth, screenHeight);
                                } else {
                                    call.reject("PixelCopy failed with code: " + copyResult);
                                }
                            }, new Handler(Looper.getMainLooper()));
                        } else {
                            call.reject("Android version too old for PixelCopy");
                        }
                    }, 50);
                });
            });

            snippingView.setTag("snipOverlayInApp");
            rootView.addView(snippingView, new android.widget.FrameLayout.LayoutParams(-1, -1));
            // Let user know they can drag
            android.widget.Toast.makeText(getContext(), "Drag area to extract text", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void processInternalBitmap(PluginCall call, Bitmap bitmap, android.graphics.RectF rect, int sw, int sh) {
        try {
            int x = Math.max(0, (int) rect.left);
            int y = Math.max(0, (int) rect.top);
            int w = Math.min(sw - x, (int) rect.width());
            int h = Math.min(sh - y, (int) rect.height());
            final Bitmap finalBitmap = (w > 0 && h > 0) ? Bitmap.createBitmap(bitmap, x, y, w, h) : bitmap;
            
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);
            String base64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            
            JSObject ret = new JSObject();
            ret.put("value", "data:image/jpeg;base64," + base64);
            
            getActivity().runOnUiThread(() -> {
                pendingCaptureVal = ret;
                saveToCache(finalBitmap);
                saveToGallery(finalBitmap);
                notifyListeners("onCaptureResult", ret);
                if (call != null) call.resolve(ret);
                bringAppToFront();
            });
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void startCapture(PluginCall call) {
        if (mediaProjection != null) {
            getActivity().runOnUiThread(() -> {
                ScreenCaptureService service = ScreenCaptureService.getInstance();
                if (service != null) {
                    service.showOverlay(rect -> takeScreenshot(call, rect));
                    new Handler(Looper.getMainLooper()).postDelayed(() -> getActivity().moveTaskToBack(true), 300);
                } else {
                    if (call != null) call.reject("Service not active");
                }
            });
            return;
        }

        getActivity().runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(getContext())) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(intent);
                if (call != null) call.reject("Permission needed");
                return;
            }

            projectionManager = (MediaProjectionManager) getActivity().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                try {
                    android.util.Log.d("ScreenCapture", "Starting projection service...");
                    Intent intent = new Intent(getContext(), ScreenCaptureService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getContext().startForegroundService(intent);
                    } else {
                        getContext().startService(intent);
                    }

                    android.util.Log.d("ScreenCapture", "Requesting user permission for screen capture...");
                    android.widget.Toast.makeText(getContext(), "Requesting permission...", android.widget.Toast.LENGTH_SHORT).show();
                    
                    Intent captureIntent = projectionManager.createScreenCaptureIntent();
                    startActivityForResult(call, captureIntent, "captureResult");
                } catch (Exception e) {
                    call.reject("Failed to start capture: " + e.getMessage());
                }
            }
        });
    }

    @ActivityCallback
    public void captureResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            mediaProjection = projectionManager.getMediaProjection(result.getResultCode(), result.getData());
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopCapture();
                }
            }, new Handler(Looper.getMainLooper()));

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                ScreenCaptureService service = ScreenCaptureService.getInstance();
                if (service != null) {
                    service.showOverlay(rect -> takeScreenshot(call, rect));
                    getActivity().moveTaskToBack(true);
                }
            }, 500);
        } else {
            if (call != null) call.reject("User cancelled");
        }
    }

    private void takeScreenshot(final PluginCall call, final android.graphics.RectF snipRect) {
        if (mediaProjection == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int w = metrics.widthPixels, h = metrics.heightPixels;

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("Snip", w, h, metrics.densityDpi, 
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);

        // Hide overlay AFTER virtual display is attached so the screen redraws and pushes a frame!
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service != null) {
            getActivity().runOnUiThread(() -> service.hideOverlay());
        }

        imageReader.setOnImageAvailableListener(reader -> {
            Image img = null;
            try {
                if (imageReader == null) return;
                img = reader.acquireLatestImage();
                if (img != null) {
                    processCapturedImage(call, img, snipRect, w, h);
                }
            } catch (Exception e) {
                if (call != null) call.reject("Failed to capture frame: " + e.getMessage());
            } finally {
                if (img != null) img.close();
                getActivity().runOnUiThread(() -> cleanupVirtualResources());
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private void processCapturedImage(PluginCall call, Image image, android.graphics.RectF rect, int w, int h) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int ps = planes[0].getPixelStride();
            int rs = planes[0].getRowStride();
            int rp = rs - ps * w;

            Bitmap bmp = Bitmap.createBitmap(w + rp / ps, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buffer);
            Bitmap full = Bitmap.createBitmap(bmp, 0, 0, w, h);
            
            final Bitmap finalCropped = (rect != null) ? createCroppedBitmap(full, rect, w, h) : full;

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            finalCropped.compress(Bitmap.CompressFormat.JPEG, 90, os);
            JSObject obj = new JSObject();
            obj.put("value", "data:image/jpeg;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP));
            
            getActivity().runOnUiThread(() -> {
                pendingCaptureVal = obj;
                saveToCache(finalCropped);
                saveToGallery(finalCropped);
                notifyListeners("onCaptureResult", obj);
                if (call != null) call.resolve(obj);
                bringAppToFront();
            });
        } catch (Exception e) {
            if (call != null) call.reject(e.getMessage());
        }
    }

    private void bringAppToFront() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Context context = getContext();
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    context.startActivity(intent);
                    
                    // Xiaomi/MIUI hack: sometimes we need to start it again if it was buried
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                         context.startActivity(intent);
                    }, 200);
                }
            } catch (Exception e) {
                android.util.Log.e("ScreenCapture", "Failed to bring app to front: " + e.getMessage());
            }
        }, 300);
    }

    private void saveToCache(Bitmap bitmap) {
        try {
            File file = new File(getContext().getCacheDir(), CACHE_FILENAME);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
        } catch (Exception e) {
            Log.e("ScreenCapture", "Failed to save cache: " + e.getMessage());
        }
    }

    private JSObject loadFromCache() {
        try {
            File file = new File(getContext().getCacheDir(), CACHE_FILENAME);
            if (!file.exists()) return null;

            FileInputStream fis = new FileInputStream(file);
            Bitmap bitmap = BitmapFactory.decodeStream(fis);
            fis.close();

            if (bitmap != null) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);
                String base64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
                JSObject ret = new JSObject();
                ret.put("value", "data:image/jpeg;base64," + base64);
                return ret;
            }
        } catch (Exception e) {
            Log.e("ScreenCapture", "Failed to load from cache: " + e.getMessage());
        }
        return null;
    }

    private void clearCacheFile() {
        try {
            File file = new File(getContext().getCacheDir(), CACHE_FILENAME);
            if (file.exists()) file.delete();
        } catch (Exception e) {}
    }

    private void cleanupVirtualResources() {
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }

    private void stopCapture() {
        cleanupVirtualResources();
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        stopService();
    }

    private Bitmap createCroppedBitmap(Bitmap full, android.graphics.RectF rect, int w, int h) {
        int rx = Math.max(0, (int) rect.left);
        int ry = Math.max(0, (int) rect.top);
        int rw = Math.min(w - rx, (int) rect.width());
        int rh = Math.min(h - ry, (int) rect.height());
        if (rw > 0 && rh > 0) return Bitmap.createBitmap(full, rx, ry, rw, rh);
        return full;
    }

    private void saveToGallery(Bitmap bitmap) {
        try {
            String filename = "SnipText_" + System.currentTimeMillis() + ".jpg";
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/SnipText");
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            }

            android.net.Uri collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            android.net.Uri item = getContext().getContentResolver().insert(collection, values);

            if (item != null) {
                try (java.io.OutputStream out = getContext().getContentResolver().openOutputStream(item)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                    getContext().getContentResolver().update(item, values, null, null);
                }
            }
        } catch (Exception e) {
            Log.e("ScreenCapture", "Failed to save to gallery: " + e.getMessage());
        }
    }

    private void stopService() {
        getContext().stopService(new Intent(getContext(), ScreenCaptureService.class));
    }
}
