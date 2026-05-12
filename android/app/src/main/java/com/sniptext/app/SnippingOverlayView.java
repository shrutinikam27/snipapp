package com.sniptext.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public class SnippingOverlayView extends View {

    private final Paint paint;
    private final Paint transparentPaint;
    private final RectF snipRect = new RectF();
    private final RectF cancelBtnRect = new RectF();
    private float startX, startY;
    private OnSnipListener listener;
    private boolean isCancelSelected = false;

    public interface OnSnipListener {
        void onSnipComplete(RectF rect);
    }

    public SnippingOverlayView(Context context, OnSnipListener listener) {
        super(context);
        this.listener = listener;

        // Dim the background so the unselected area is darkened
        paint = new Paint();
        paint.setColor(Color.parseColor("#88000000")); // Semi-transparent black

        // Paint to 'cut out' the selected selection region
        transparentPaint = new Paint();
        transparentPaint.setAntiAlias(true);
        transparentPaint.setColor(Color.TRANSPARENT);
        transparentPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        // Hardware acceleration required for PorterDuff.Mode.CLEAR, but can break on some devices.
        // We set will not draw to false explicitly.
        setWillNotDraw(false);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setElevation(100f);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Dim the entire screen
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        if (snipRect.width() > 0 && snipRect.height() > 0) {
            // Cut out the selected box to highlight the content underneath
            canvas.drawRect(snipRect, transparentPaint);

            // Draw a white border around the snip box (it will stand out against the dimmed
            // background)
            Paint border = new Paint();
            border.setColor(Color.WHITE);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(5);
            // subtle shadow for extra visibility
            border.setShadowLayer(4, 0, 0, Color.BLACK);
            canvas.drawRect(snipRect, border);
        } else {
            // Give user a visual hint that they should drag (when starting)
            Paint hintPaint = new Paint();
            hintPaint.setColor(Color.WHITE);
            hintPaint.setTextSize(60);
            hintPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Drag to snip", getWidth() / 2f, getHeight() / 2f, hintPaint);
        }

        // Draw Cancel button in top right
        float padding = 50;
        float btnWidth = 250;
        float btnHeight = 100;
        cancelBtnRect.set(getWidth() - btnWidth - padding, padding, getWidth() - padding, padding + btnHeight);
        
        Paint btnPaint = new Paint();
        btnPaint.setColor(isCancelSelected ? Color.parseColor("#FF5252") : Color.parseColor("#424242"));
        btnPaint.setAntiAlias(true);
        canvas.drawRoundRect(cancelBtnRect, 15, 15, btnPaint);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("CANCEL", cancelBtnRect.centerX(), cancelBtnRect.centerY() + 15, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (cancelBtnRect.contains(x, y)) {
                    isCancelSelected = true;
                    invalidate();
                    return true;
                }
                isCancelSelected = false;
                startX = x;
                startY = y;
                snipRect.set(startX, startY, startX, startY);
                invalidate();
                break;

            case MotionEvent.ACTION_MOVE:
                if (isCancelSelected) {
                    boolean nowInside = cancelBtnRect.contains(x, y);
                    if (nowInside != isCancelSelected) {
                        isCancelSelected = nowInside;
                        invalidate();
                    }
                    return true;
                }
                snipRect.set(
                        Math.min(startX, x),
                        Math.min(startY, y),
                        Math.max(startX, x),
                        Math.max(startY, y));
                invalidate();
                break;

            case MotionEvent.ACTION_UP:
                if (isCancelSelected) {
                    isCancelSelected = false;
                    if (cancelBtnRect.contains(x, y)) {
                        if (listener != null) {
                            listener.onSnipComplete(null);
                        }
                    } else {
                        invalidate();
                    }
                    return true;
                }
                if (snipRect.width() > 10 && snipRect.height() > 10) {
                    if (listener != null) {
                        // Vibrate to signal selection complete
                        android.os.Vibrator v = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                        if (v != null && v.hasVibrator()) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                v.vibrate(android.os.VibrationEffect.createOneShot(25, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                            } else {
                                v.vibrate(25);
                            }
                        }
                        listener.onSnipComplete(new RectF(snipRect));
                    }
                } else {
                    // Cancel selection if it's too small
                    snipRect.setEmpty();
                    invalidate();
                    if (listener != null) {
                        listener.onSnipComplete(new RectF(snipRect));
                    }
                }
                break;
        }
        return true;
    }
}
