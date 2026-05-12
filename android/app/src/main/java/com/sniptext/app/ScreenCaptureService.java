package com.sniptext.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.WindowManager;

public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "ScreenCaptureChannel";
    private static final int NOTIFICATION_ID = 101;
    
    private WindowManager windowManager;
    private SnippingOverlayView overlayView;
    private android.widget.ImageView floatingBubble;
    private android.widget.LinearLayout optionsMenu;
    private static ScreenCaptureService instance;
    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean isMenuOpen = false;

    public static ScreenCaptureService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SnipText Pro")
                .setContentText("Floating bubble is active.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    public void showFloatingBubble(SnippingOverlayView.OnSnipListener listener) {
        android.util.Log.d("ScreenCaptureService", "showFloatingBubble called");
        if (floatingBubble != null) {
            android.util.Log.d("ScreenCaptureService", "Bubble already exists, skipping");
            return;
        }

        floatingBubble = new android.widget.ImageView(this);
        // Use a more reliable icon and styling
        floatingBubble.setImageResource(android.R.drawable.ic_menu_camera); 
        floatingBubble.setBackgroundColor(android.graphics.Color.parseColor("#6366f1")); // Indigo-600
        floatingBubble.setPadding(25, 25, 25, 25);
        
        // Make it circular
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            floatingBubble.setElevation(20);
            floatingBubble.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            floatingBubble.setClipToOutline(true);
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                160, // Fixed size for reliability
                160,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        floatingBubble.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        int diffX = (int) (event.getRawX() - initialTouchX);
                        int diffY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(diffX) < 15 && Math.abs(diffY) < 15) {
                            android.util.Log.d("ScreenCaptureService", "Bubble clicked!");
                            if (!isMenuOpen) {
                                showOptionsMenu(params.x, params.y, listener);
                            } else {
                                hideOptionsMenu();
                            }
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        params.x = (int) (initialX + (event.getRawX() - initialTouchX));
                        params.y = (int) (initialY + (event.getRawY() - initialTouchY));
                        try {
                            windowManager.updateViewLayout(floatingBubble, params);
                        } catch (Exception e) {}
                        return true;
                }
                return false;
            }
        });

        try {
            android.util.Log.d("ScreenCaptureService", "Adding bubble to WindowManager...");
            windowManager.addView(floatingBubble, params);
            android.util.Log.d("ScreenCaptureService", "Bubble added successfully");
        } catch (Exception e) {
            android.util.Log.e("ScreenCaptureService", "Failed to add bubble: " + e.getMessage());
            android.widget.Toast.makeText(this, "Failed to show bubble. Check overlay permission.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showOptionsMenu(int x, int y, SnippingOverlayView.OnSnipListener listener) {
        if (optionsMenu != null) return;

        android.util.Log.d("ScreenCaptureService", "showOptionsMenu: Opening...");
        isMenuOpen = true;
        optionsMenu = new android.widget.LinearLayout(this);
        optionsMenu.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        optionsMenu.setBackgroundResource(android.R.drawable.dialog_holo_dark_frame);
        optionsMenu.setPadding(10, 10, 10, 10);

        android.widget.Button btnEntire = new android.widget.Button(this);
        btnEntire.setText("Entire Screen");
        btnEntire.setTextSize(12);
        btnEntire.setOnClickListener(v -> {
            hideOptionsMenu();
            if (floatingBubble != null) floatingBubble.setVisibility(android.view.View.GONE);
            showOverlay(rect -> {
                hideOverlay();
                if (floatingBubble != null) floatingBubble.setVisibility(android.view.View.VISIBLE);
                if (listener != null) listener.onSnipComplete(rect);
            });
        });

        android.widget.Button btnWithin = new android.widget.Button(this);
        btnWithin.setText("Within App");
        btnWithin.setTextSize(12);
        btnWithin.setOnClickListener(v -> {
            hideOptionsMenu();
            if (floatingBubble != null) floatingBubble.setVisibility(android.view.View.GONE);
            showOverlay(rect -> {
                hideOverlay();
                if (floatingBubble != null) floatingBubble.setVisibility(android.view.View.VISIBLE);
                if (listener != null) listener.onSnipComplete(rect);
            });
        });

        android.widget.Button btnCancel = new android.widget.Button(this);
        btnCancel.setText("Cancel");
        btnCancel.setTextSize(12);
        btnCancel.setOnClickListener(v -> {
            hideOptionsMenu();
        });

        optionsMenu.addView(btnEntire);
        optionsMenu.addView(btnWithin);
        optionsMenu.addView(btnCancel);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x + 120; // Show next to bubble
        params.y = y;

        try {
            windowManager.addView(optionsMenu, params);
        } catch (Exception e) {
            android.util.Log.e("ScreenCaptureService", "Failed to add optionsMenu: " + e.getMessage());
        }
    }

    private void hideOptionsMenu() {
        if (optionsMenu != null) {
            windowManager.removeView(optionsMenu);
            optionsMenu = null;
            isMenuOpen = false;
        }
    }

    public void showOverlay(SnippingOverlayView.OnSnipListener listener) {
        if (overlayView == null) {
            overlayView = new SnippingOverlayView(this, rect -> {
                android.util.Log.d("ScreenCaptureService", "Overlay capture complete");
                if (listener != null) {
                    listener.onSnipComplete(rect);
                } else {
                    android.util.Log.w("ScreenCaptureService", "showOverlay: listener is NULL!");
                }
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                    WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE 
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    }

            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(overlayView, params);
        }
    }

    public void hideOverlay() {
        if (overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
    }

    public void removeBubble() {
        hideOptionsMenu();
        if (floatingBubble != null) {
            windowManager.removeView(floatingBubble);
            floatingBubble = null;
        }
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        removeBubble();
        instance = null;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
