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
import java.nio.ByteBuffer;

@CapacitorPlugin(name = "ScreenCapture")
public class ScreenCapturePlugin extends Plugin {

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private android.graphics.RectF pendingRect = null;
    
    private static JSObject pendingCaptureVal = null;

    @PluginMethod
    public void checkPendingCapture(PluginCall call) {
        if (pendingCaptureVal != null) {
            call.resolve(pendingCaptureVal);
            pendingCaptureVal = null;
        } else {
            call.resolve();
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
        if (rect == null || rect.width() <= 10 || rect.height() <= 10) {
            android.util.Log.d("ScreenCapture", "handleBubbleCapture: Ignoring small or null rect");
            return;
        }
        android.util.Log.d("ScreenCapture", "handleBubbleCapture: Processing snip " + rect.toString());
        getActivity().runOnUiThread(() -> {
            this.pendingRect = rect;
            if (mediaProjection != null) {
                // Flash message to user - Safe check
                if (getContext() != null) {
                    android.widget.Toast.makeText(getContext(), "Processing Snip...", android.widget.Toast.LENGTH_SHORT).show();
                }
                takeScreenshot(null, rect);
                this.pendingRect = null;
            } else {
                android.util.Log.d("ScreenCapture", "handleBubbleCapture: MediaProjection null, requesting...");
                if (getContext() != null) {
                    android.widget.Toast.makeText(getContext(), "Setting up screen access...", android.widget.Toast.LENGTH_SHORT).show();
                }
                bringAppToFront(); // Bring app to front to show the permission dialog
                new Handler(Looper.getMainLooper()).postDelayed(() -> startCapture(null), 1000);
            }
        });
    }

    @PluginMethod
    public void enableFloating(PluginCall call) {
        android.util.Log.d("ScreenCapture", "enableFloating: Requesting bubble...");
        getActivity().runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getContext())) {
                    android.util.Log.w("ScreenCapture", "enableFloating: Missing overlay permission!");
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getContext().getPackageName()));
                    getActivity().startActivity(intent);
                    call.reject("Overlay Permission not granted");
                    return;
                }
            }

            // NEW: If we don't have media projection yet, we MUST request it while the app is in the foreground.
            if (mediaProjection == null) {
                android.util.Log.d("ScreenCapture", "enableFloating: MediaProjection null, requesting permission first...");
                projectionManager = (MediaProjectionManager) getActivity().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                Intent captureIntent = projectionManager.createScreenCaptureIntent();
                startActivityForResult(call, captureIntent, "captureResult");
                return;
            }

            android.util.Log.d("ScreenCapture", "enableFloating: Starting service...");
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
                        android.util.Log.d("ScreenCapture", "enableFloating: Service found, showing bubble");
                        service.showFloatingBubble(rect -> handleBubbleCapture(rect));
                        android.widget.Toast.makeText(getContext(), "Floating mode enabled!", android.widget.Toast.LENGTH_SHORT).show();
                        call.resolve();
                    } else if (retries < 15) {
                        retries++;
                        android.util.Log.d("ScreenCapture", "enableFloating: Waiting for service... retry " + retries);
                        new Handler(Looper.getMainLooper()).postDelayed(this, 300);
                    } else {
                        android.util.Log.e("ScreenCapture", "enableFloating: TIMEOUT waiting for service");
                        call.reject("Could not enable floating mode (service timeout).");
                    }
                }
            }, 500);
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
            android.widget.Toast.makeText(getContext(), "Drag to snip, tap to cancel", 1).show();
        });
    }

    private void processInternalBitmap(PluginCall call, Bitmap bitmap, android.graphics.RectF rect, int sw, int sh) {
        try {
            int x = Math.max(0, (int) rect.left);
            int y = Math.max(0, (int) rect.top);
            int w = Math.min(sw - x, (int) rect.width());
            int h = Math.min(sh - y, (int) rect.height());
            Bitmap cropped = (w > 0 && h > 0) ? Bitmap.createBitmap(bitmap, x, y, w, h) : bitmap;
            
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, os);
            String base64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            
            JSObject ret = new JSObject();
            ret.put("value", "data:image/jpeg;base64," + base64);
            pendingCaptureVal = ret;
            notifyListeners("onCaptureResult", ret);
            call.resolve(ret);
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
                    service.showOverlay(rect -> {
                        android.util.Log.d("ScreenCapture", "Overlay snip complete, taking screenshot...");
                        takeScreenshot(call, rect);
                    });
                    new Handler(Looper.getMainLooper()).postDelayed(() -> getActivity().moveTaskToBack(true), 300);
                } else {
                    android.util.Log.e("ScreenCapture", "service is NULL in startCapture!");
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
                    android.util.Log.e("ScreenCapture", "Exception in startCapture: " + e.getMessage());
                    if (call != null) call.reject("Failed to start capture: " + e.getMessage());
                }
            }
        });
    }

    @ActivityCallback
    public void captureResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            try {
                // REQUIRED for Android 14+: Service must be running BEFORE getMediaProjection
                Intent intent = new Intent(getContext(), ScreenCaptureService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getContext().startForegroundService(intent);
                } else {
                    getContext().startService(intent);
                }

                mediaProjection = projectionManager.getMediaProjection(result.getResultCode(), result.getData());
                
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        cleanup();
                    }
                }, new Handler(Looper.getMainLooper()));

                // Wait for service to be fully ready before showing UI
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    int retries = 0;
                    @Override
                    public void run() {
                        ScreenCaptureService service = ScreenCaptureService.getInstance();
                        if (service != null) {
                            if (call != null && "enableFloating".equals(call.getMethodName())) {
                                service.showFloatingBubble(rect -> handleBubbleCapture(rect));
                                call.resolve();
                            } else {
                                service.showOverlay(rect -> takeScreenshot(call, rect));
                                getActivity().moveTaskToBack(true);
                            }
                        } else if (retries < 10) {
                            retries++;
                            new Handler(Looper.getMainLooper()).postDelayed(this, 300);
                        } else {
                            if (call != null) call.reject("Service initialization timeout");
                        }
                    }
                }, 500);
            } catch (Exception e) {
                if (call != null) call.reject(e.getMessage());
            }
        } else {
            if (call != null) call.reject("User cancelled");
        }
    }

    private void takeScreenshot(final PluginCall call, final android.graphics.RectF rect) {
        getActivity().runOnUiThread(() -> {
            try {
                if (mediaProjection == null) {
                    if (call != null) call.reject("No permission");
                    return;
                }

                DisplayMetrics metrics = new DisplayMetrics();
                getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                int w = metrics.widthPixels;
                int h = metrics.heightPixels;

                imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                virtualDisplay = mediaProjection.createVirtualDisplay("Snip", w, h, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);

                ScreenCaptureService service = ScreenCaptureService.getInstance();
                if (service != null) service.hideOverlay();

                imageReader.setOnImageAvailableListener(reader -> {
                    try {
                        Image img = reader.acquireNextImage(); 
                        if (img != null) {
                            processCapturedImage(call, img, rect, w, h);
                            img.close();
                            cleanup();
                        }
                    } catch (Exception e) {
                        if (call != null) call.reject(e.getMessage());
                        cleanup();
                    }
                }, new Handler(Looper.getMainLooper()));
                
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (virtualDisplay != null) {
                        cleanup();
                        if (call != null) call.reject("Timeout");
                    }
                }, 4000); 

            } catch (Exception e) {
                cleanup();
                if (call != null) call.reject(e.getMessage());
            }
        });
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
            
            Bitmap cropped = full;
            if (rect != null) {
                int rx = Math.max(0, (int) rect.left);
                int ry = Math.max(0, (int) rect.top);
                int rw = Math.min(w - rx, (int) rect.width());
                int rh = Math.min(h - ry, (int) rect.height());
                if (rw > 0 && rh > 0) cropped = Bitmap.createBitmap(full, rx, ry, rw, rh);
            }

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, os);
            JSObject obj = new JSObject();
            obj.put("value", "data:image/jpeg;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP));
            pendingCaptureVal = obj;
            notifyListeners("onCaptureResult", obj);
            if (call != null) call.resolve(obj);
            bringAppToFront();
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
                }
            } catch (Exception e) {
                android.util.Log.e("ScreenCapture", "Failed to bring app to front: " + e.getMessage());
            }
        }, 300);
    }

    private void cleanup() {
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
    }

    private void stopCapture() {
        cleanup();
        stopService();
    }

    private void stopService() {
        getContext().stopService(new Intent(getContext(), ScreenCaptureService.class));
    }
}
