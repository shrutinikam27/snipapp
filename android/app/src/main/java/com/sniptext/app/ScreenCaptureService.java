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
    private android.widget.TextView closeTarget;
    private static ScreenCaptureService instance;
    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean isMenuOpen = false;
    private boolean isOverCloseTarget = false;
    private SnippingOverlayView.OnSnipListener currentSnipListener;

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
        if (floatingBubble != null) return;
        this.currentSnipListener = listener;

        floatingBubble = new android.widget.ImageView(this);
        
        // Use the official app icon for the floating bubble from our app's resources
        floatingBubble.setImageResource(com.sniptext.app.R.mipmap.ic_launcher_round); 
        
        // Circular styling with shadow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            floatingBubble.setElevation(20f);
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                150, 150, // Slightly larger to show the app icon clearly
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;

        floatingBubble.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        
                        // Scale up on touch
                        floatingBubble.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start();
                        
                        showCloseTarget();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        // Scale back down
                        floatingBubble.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                        
                        boolean droppedToClose = isOverCloseTarget;
                        hideCloseTarget();
                        
                        if (droppedToClose) {
                            destroyBubble();
                            stopSelf();
                            return true;
                        }

                        // Check if it was a click instead of a drag
                        float endX = event.getRawX();
                        float endY = event.getRawY();
                        if (Math.abs(endX - initialTouchX) < 15 && Math.abs(endY - initialTouchY) < 15) {
                            if (!isMenuOpen) {
                                showOptionsMenu(params.x, params.y);
                            } else {
                                hideOptionsMenu();
                            }
                        }
                        return true;

                    case android.view.MotionEvent.ACTION_MOVE:
                        float moveX = event.getRawX();
                        float moveY = event.getRawY();
                        
                        params.x = (int) (initialX + (moveX - initialTouchX));
                        params.y = (int) (initialY + (moveY - initialTouchY));
                        
                        // Check collision with close target
                        checkCloseTargetCollision(moveX, moveY);
                        
                        // Magnetic effect: if near close target, snap the bubble visually
                        if (isOverCloseTarget) {
                            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                            params.x = (int) (m.widthPixels / 2f - 75); // Center horizontally
                            params.y = (int) (m.heightPixels - 300); // Near the bottom X
                        }
                        
                        windowManager.updateViewLayout(floatingBubble, params);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingBubble, params);
    }

    private void showCloseTarget() {
        if (closeTarget != null) return;
        closeTarget = new android.widget.TextView(this);
        closeTarget.setText("X");
        closeTarget.setGravity(Gravity.CENTER);
        closeTarget.setTextColor(android.graphics.Color.WHITE);
        closeTarget.setTextSize(24);
        
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(android.graphics.Color.parseColor("#CCFF5555")); // Semi-transparent red
        closeTarget.setBackground(shape);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                160, 160,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 100;
        windowManager.addView(closeTarget, params);
    }

    private void hideCloseTarget() {
        if (closeTarget != null) {
            windowManager.removeView(closeTarget);
            closeTarget = null;
            isOverCloseTarget = false;
        }
    }

    private void checkCloseTargetCollision(float rawX, float rawY) {
        if (closeTarget == null) return;
        
        // Get screen dimensions
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        float screenWidth = metrics.widthPixels;
        float screenHeight = metrics.heightPixels;
        
        // Target is at Bottom Center. 
        // We detect if finger is in the bottom 30% of the screen AND within a wider horizontal range
        boolean isNearBottom = rawY > (screenHeight * 0.70f);
        boolean isNearHorizontalCenter = Math.abs(rawX - (screenWidth / 2f)) < 400;
        
        if (isNearBottom && isNearHorizontalCenter) { 
            if (!isOverCloseTarget) {
                isOverCloseTarget = true;
                // Vibrate only once when entering target
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(70, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                }
                closeTarget.setScaleX(2.2f);
                closeTarget.setScaleY(2.2f);
                closeTarget.setText("RELEASE TO CLOSE"); 
            }
        } else {
            if (isOverCloseTarget) {
                isOverCloseTarget = false;
                closeTarget.setScaleX(1.0f);
                closeTarget.setScaleY(1.0f);
                closeTarget.setText("X");
            }
        }
    }

    private void destroyBubble() {
        if (floatingBubble != null) {
            try {
                if (floatingBubble.getParent() != null) {
                    windowManager.removeView(floatingBubble);
                }
            } catch (Exception e) {}
            floatingBubble = null;
        }
    }

    private void showOptionsMenu(int x, int y) {
        if (optionsMenu != null) return;

        isMenuOpen = true;
        optionsMenu = new android.widget.LinearLayout(this);
        optionsMenu.setOrientation(android.widget.LinearLayout.VERTICAL);
        optionsMenu.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A")); // Dark gray background
        optionsMenu.setPadding(10, 10, 10, 10);
        
        // Rounded corners for the container
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(30f);
            shape.setColor(android.graphics.Color.parseColor("#1A1A1A"));
            shape.setStroke(2, android.graphics.Color.parseColor("#333333"));
            optionsMenu.setBackground(shape);
            optionsMenu.setElevation(20f);
        }

        android.widget.Button btnEntire = new android.widget.Button(this);
        btnEntire.setText("Entire Screen");
        btnEntire.setTextColor(android.graphics.Color.WHITE);
        btnEntire.setAllCaps(false);
        btnEntire.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnEntire.setOnClickListener(v -> {
            hideOptionsMenu();
            floatingBubble.setVisibility(android.view.View.GONE);
            showOverlay(rect -> {
                hideOverlay();
                floatingBubble.setVisibility(android.view.View.VISIBLE);
                if (currentSnipListener != null) currentSnipListener.onSnipComplete(rect);
            });
        });

        android.widget.Button btnWithin = new android.widget.Button(this);
        btnWithin.setText("Within App");
        btnWithin.setTextColor(android.graphics.Color.WHITE);
        btnWithin.setAllCaps(false);
        btnWithin.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnWithin.setOnClickListener(v -> {
            android.widget.Toast.makeText(this, "Capturing current app only...", android.widget.Toast.LENGTH_SHORT).show();
            hideOptionsMenu();
            floatingBubble.setVisibility(android.view.View.GONE);
            showOverlay(rect -> {
                hideOverlay();
                floatingBubble.setVisibility(android.view.View.VISIBLE);
                if (currentSnipListener != null) currentSnipListener.onSnipComplete(rect);
            });
        });

        android.widget.Button btnCancel = new android.widget.Button(this);
        btnCancel.setText("Cancel");
        btnCancel.setTextColor(android.graphics.Color.parseColor("#FF5555")); // Reddish
        btnCancel.setAllCaps(false);
        btnCancel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnCancel.setOnClickListener(v -> hideOptionsMenu());

        optionsMenu.addView(btnEntire);
        optionsMenu.addView(btnWithin);
        optionsMenu.addView(btnCancel);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        // Position dropdown below the bubble
        params.x = x;
        params.y = y + 140; 

        windowManager.addView(optionsMenu, params);
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
                // Keep overlay while we capture frame!
                if (listener != null) listener.onSnipComplete(rect);
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                            WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
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
