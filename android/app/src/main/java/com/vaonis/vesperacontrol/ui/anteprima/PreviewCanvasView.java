package com.vaonis.vesperacontrol.ui.anteprima;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.vaonis.vesperacontrol.R;

/** Canvas pan/zoom + selezione crop ridimensionabile (coordinate immagine). */
public class PreviewCanvasView extends View {

    public interface CropListener {
        void onCropSelectionChanged(@Nullable RectF imageRect);
    }

    private enum DragMode {
        NONE, CREATE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TL, TR, BL, BR
    }

    private final Matrix matrix = new Matrix();
    private final Matrix invert = new Matrix();
    private final float[] pts = new float[2];
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint helpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap bitmap;
    private boolean cropMode;
    private RectF cropRect; // in image coords
    private float cropStartX;
    private float cropStartY;
    private float moveOriginX;
    private float moveOriginY;
    private RectF moveBase;
    private DragMode dragMode = DragMode.NONE;
    private CropListener cropListener;
    private String helpText = "";
    private int visibleInsetTop;
    private int visibleInsetBottom;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float minScale = 0.08f;
    private float maxScale = 10f;

    public PreviewCanvasView(Context context) {
        this(context, null);
    }

    public PreviewCanvasView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PreviewCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(true);
        shadePaint.setColor(0x99000000);
        cropPaint.setStyle(Paint.Style.STROKE);
        cropPaint.setStrokeWidth(dp(2));
        cropPaint.setColor(0xFFFFEB3B);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFFEB3B);
        helpPaint.setColor(0xFFB0BEC5);
        helpPaint.setTextSize(dp(12));
        helpPaint.setAntiAlias(true);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (bitmap == null || cropMode) return false;
                float factor = detector.getScaleFactor();
                float[] values = new float[9];
                matrix.getValues(values);
                float scale = values[Matrix.MSCALE_X];
                float next = clamp(scale * factor, minScale, maxScale);
                factor = next / scale;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (bitmap == null || cropMode || scaleDetector.isInProgress()) return false;
                matrix.postTranslate(-distanceX, -distanceY);
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (bitmap == null || cropMode) return false;
                fit();
                return true;
            }
        });
    }

    public void setCropListener(CropListener listener) {
        this.cropListener = listener;
    }

    public void setHelpText(String text) {
        this.helpText = text == null ? "" : text;
        invalidate();
    }

    /** Area libera sopra la barra strumenti: fit e testo di aiuto restano visibili. */
    public void setVisibleInsets(int top, int bottom) {
        top = Math.max(0, top);
        bottom = Math.max(0, bottom);
        if (visibleInsetTop == top && visibleInsetBottom == bottom) return;
        visibleInsetTop = top;
        visibleInsetBottom = bottom;
        fit();
    }

    public void setImage(@Nullable Bitmap bmp) {
        this.bitmap = bmp;
        post(this::fit);
        invalidate();
    }

    public void setCropMode(boolean enabled) {
        this.cropMode = enabled;
        this.dragMode = DragMode.NONE;
        invalidate();
    }

    public boolean isCropMode() {
        return cropMode;
    }

    @Nullable
    public RectF getCropRect() {
        return cropRect == null ? null : new RectF(cropRect);
    }

    /** Imposta selezione crop in coordinate immagine (dopo validazione). */
    public void setCropRect(@Nullable RectF rect) {
        if (bitmap == null || rect == null) {
            clearCropSelection();
            return;
        }
        float l = clamp(Math.min(rect.left, rect.right), 0f, bitmap.getWidth());
        float r = clamp(Math.max(rect.left, rect.right), 0f, bitmap.getWidth());
        float t = clamp(Math.min(rect.top, rect.bottom), 0f, bitmap.getHeight());
        float b = clamp(Math.max(rect.top, rect.bottom), 0f, bitmap.getHeight());
        if (r - l < 2f || b - t < 2f) {
            clearCropSelection();
            return;
        }
        cropRect = new RectF(l, t, r, b);
        notifyCrop();
        invalidate();
    }

    /** Imposta larghezza/altezza tenendo fisso l’angolo in alto a sinistra. */
    public void setCropSize(int width, int height) {
        if (bitmap == null) return;
        float left;
        float top;
        if (cropRect != null) {
            left = cropRect.left;
            top = cropRect.top;
        } else {
            left = Math.max(0f, (bitmap.getWidth() - width) / 2f);
            top = Math.max(0f, (bitmap.getHeight() - height) / 2f);
        }
        float right = Math.min(bitmap.getWidth(), left + Math.max(2, width));
        float bottom = Math.min(bitmap.getHeight(), top + Math.max(2, height));
        if (right - left < 2f || bottom - top < 2f) return;
        // Se esce a destra/basso, sposta l’origine.
        if (right >= bitmap.getWidth()) left = Math.max(0f, bitmap.getWidth() - (right - left));
        if (bottom >= bitmap.getHeight()) top = Math.max(0f, bitmap.getHeight() - (bottom - top));
        right = Math.min(bitmap.getWidth(), left + Math.max(2, width));
        bottom = Math.min(bitmap.getHeight(), top + Math.max(2, height));
        cropRect = new RectF(left, top, right, bottom);
        notifyCrop();
        invalidate();
    }

    public void clearCropSelection() {
        cropRect = null;
        dragMode = DragMode.NONE;
        notifyCrop();
        invalidate();
    }

    public boolean hasValidCrop() {
        return cropRect != null
                && cropRect.width() >= 2f
                && cropRect.height() >= 2f;
    }

    public void fit() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
        float topInset = visibleInsetTop;
        float bottomInset = visibleInsetBottom;
        float cw = getWidth();
        float ch = getHeight() - topInset - bottomInset;
        if (ch < dp(48)) {
            topInset = 0f;
            bottomInset = 0f;
            ch = getHeight();
        }
        float iw = bitmap.getWidth();
        float ih = bitmap.getHeight();
        float scale = Math.min(cw / iw, ch / ih);
        scale = Math.min(scale, 1f);
        float scaledW = iw * scale;
        float scaledH = ih * scale;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((cw - scaledW) / 2f, topInset + Math.max(0f, (ch - scaledH) / 2f));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bitmap != null && (oldw == 0 || oldh == 0)) {
            fit();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(ContextCompat.getColor(getContext(), R.color.vespera_ink));
        if (bitmap == null || bitmap.isRecycled()) return;
        canvas.drawBitmap(bitmap, matrix, bitmapPaint);
        if (cropMode || cropRect != null) {
            drawCropOverlay(canvas);
        }
        if (!helpText.isEmpty()) {
            float helpY = getHeight() - visibleInsetBottom - dp(10);
            if (helpY < visibleInsetTop + helpPaint.getTextSize()) {
                helpY = getHeight() - dp(12);
            }
            canvas.drawText(helpText, dp(12), helpY, helpPaint);
        }
    }

    private void drawCropOverlay(Canvas canvas) {
        if (cropRect == null) return;
        RectF viewRect = imageRectToView(cropRect);
        canvas.drawRect(0, 0, getWidth(), viewRect.top, shadePaint);
        canvas.drawRect(0, viewRect.bottom, getWidth(), getHeight(), shadePaint);
        canvas.drawRect(0, viewRect.top, viewRect.left, viewRect.bottom, shadePaint);
        canvas.drawRect(viewRect.right, viewRect.top, getWidth(), viewRect.bottom, shadePaint);
        canvas.drawRect(viewRect, cropPaint);
        float hs = dp(5);
        canvas.drawRect(viewRect.left - hs, viewRect.top - hs, viewRect.left + hs, viewRect.top + hs, handlePaint);
        canvas.drawRect(viewRect.right - hs, viewRect.top - hs, viewRect.right + hs, viewRect.top + hs, handlePaint);
        canvas.drawRect(viewRect.left - hs, viewRect.bottom - hs, viewRect.left + hs, viewRect.bottom + hs, handlePaint);
        canvas.drawRect(viewRect.right - hs, viewRect.bottom - hs, viewRect.right + hs, viewRect.bottom + hs, handlePaint);
        // Maniglie bordi medi
        float mx = (viewRect.left + viewRect.right) / 2f;
        float my = (viewRect.top + viewRect.bottom) / 2f;
        canvas.drawRect(mx - hs, viewRect.top - hs, mx + hs, viewRect.top + hs, handlePaint);
        canvas.drawRect(mx - hs, viewRect.bottom - hs, mx + hs, viewRect.bottom + hs, handlePaint);
        canvas.drawRect(viewRect.left - hs, my - hs, viewRect.left + hs, my + hs, handlePaint);
        canvas.drawRect(viewRect.right - hs, my - hs, viewRect.right + hs, my + hs, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return super.onTouchEvent(event);
        if (cropMode) {
            return handleCropTouch(event);
        }
        boolean scale = scaleDetector.onTouchEvent(event);
        boolean gest = gestureDetector.onTouchEvent(event);
        return scale || gest || super.onTouchEvent(event);
    }

    private boolean handleCropTouch(MotionEvent event) {
        float[] img = viewToImage(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragMode = hitTest(event.getX(), event.getY());
                if (dragMode == DragMode.NONE) {
                    dragMode = DragMode.CREATE;
                    cropStartX = img[0];
                    cropStartY = img[1];
                    cropRect = new RectF(cropStartX, cropStartY, cropStartX, cropStartY);
                } else if (dragMode == DragMode.MOVE && cropRect != null) {
                    moveOriginX = img[0];
                    moveOriginY = img[1];
                    moveBase = new RectF(cropRect);
                } else if (cropRect != null) {
                    cropStartX = img[0];
                    cropStartY = img[1];
                    moveBase = new RectF(cropRect);
                }
                notifyCrop();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragMode == DragMode.NONE) return true;
                applyDrag(img[0], img[1]);
                notifyCrop();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = DragMode.NONE;
                moveBase = null;
                if (!hasValidCrop()) {
                    cropRect = null;
                }
                notifyCrop();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void applyDrag(float x, float y) {
        if (bitmap == null) return;
        if (dragMode == DragMode.CREATE) {
            cropRect = normalize(cropStartX, cropStartY, x, y);
            clampCropToBitmap();
            return;
        }
        if (cropRect == null || moveBase == null) return;
        float min = 2f;
        float maxW = bitmap.getWidth();
        float maxH = bitmap.getHeight();
        RectF next = new RectF(moveBase);
        switch (dragMode) {
            case MOVE: {
                float dx = x - moveOriginX;
                float dy = y - moveOriginY;
                next.offset(dx, dy);
                if (next.left < 0) next.offset(-next.left, 0);
                if (next.top < 0) next.offset(0, -next.top);
                if (next.right > maxW) next.offset(maxW - next.right, 0);
                if (next.bottom > maxH) next.offset(0, maxH - next.bottom);
                break;
            }
            case LEFT:
                next.left = clamp(x, 0f, next.right - min);
                break;
            case RIGHT:
                next.right = clamp(x, next.left + min, maxW);
                break;
            case TOP:
                next.top = clamp(y, 0f, next.bottom - min);
                break;
            case BOTTOM:
                next.bottom = clamp(y, next.top + min, maxH);
                break;
            case TL:
                next.left = clamp(x, 0f, next.right - min);
                next.top = clamp(y, 0f, next.bottom - min);
                break;
            case TR:
                next.right = clamp(x, next.left + min, maxW);
                next.top = clamp(y, 0f, next.bottom - min);
                break;
            case BL:
                next.left = clamp(x, 0f, next.right - min);
                next.bottom = clamp(y, next.top + min, maxH);
                break;
            case BR:
                next.right = clamp(x, next.left + min, maxW);
                next.bottom = clamp(y, next.top + min, maxH);
                break;
            default:
                break;
        }
        cropRect = next;
    }

    private DragMode hitTest(float viewX, float viewY) {
        if (cropRect == null || !hasValidCrop()) return DragMode.NONE;
        RectF viewRect = imageRectToView(cropRect);
        float hs = dp(14);
        if (near(viewX, viewY, viewRect.left, viewRect.top, hs)) return DragMode.TL;
        if (near(viewX, viewY, viewRect.right, viewRect.top, hs)) return DragMode.TR;
        if (near(viewX, viewY, viewRect.left, viewRect.bottom, hs)) return DragMode.BL;
        if (near(viewX, viewY, viewRect.right, viewRect.bottom, hs)) return DragMode.BR;
        float mx = (viewRect.left + viewRect.right) / 2f;
        float my = (viewRect.top + viewRect.bottom) / 2f;
        if (near(viewX, viewY, mx, viewRect.top, hs)) return DragMode.TOP;
        if (near(viewX, viewY, mx, viewRect.bottom, hs)) return DragMode.BOTTOM;
        if (near(viewX, viewY, viewRect.left, my, hs)) return DragMode.LEFT;
        if (near(viewX, viewY, viewRect.right, my, hs)) return DragMode.RIGHT;
        if (viewRect.contains(viewX, viewY)) return DragMode.MOVE;
        return DragMode.NONE;
    }

    private static boolean near(float x, float y, float cx, float cy, float r) {
        return Math.abs(x - cx) <= r && Math.abs(y - cy) <= r;
    }

    private void clampCropToBitmap() {
        if (cropRect == null || bitmap == null) return;
        cropRect.left = clamp(cropRect.left, 0f, bitmap.getWidth());
        cropRect.right = clamp(cropRect.right, 0f, bitmap.getWidth());
        cropRect.top = clamp(cropRect.top, 0f, bitmap.getHeight());
        cropRect.bottom = clamp(cropRect.bottom, 0f, bitmap.getHeight());
    }

    private void notifyCrop() {
        if (cropListener != null) {
            cropListener.onCropSelectionChanged(getCropRect());
        }
    }

    private float[] viewToImage(float x, float y) {
        pts[0] = x;
        pts[1] = y;
        matrix.invert(invert);
        invert.mapPoints(pts);
        if (bitmap != null) {
            pts[0] = clamp(pts[0], 0f, bitmap.getWidth());
            pts[1] = clamp(pts[1], 0f, bitmap.getHeight());
        }
        return new float[] {pts[0], pts[1]};
    }

    private RectF imageRectToView(RectF image) {
        float[] tl = {image.left, image.top};
        float[] br = {image.right, image.bottom};
        matrix.mapPoints(tl);
        matrix.mapPoints(br);
        return normalize(tl[0], tl[1], br[0], br[1]);
    }

    private static RectF normalize(float x0, float y0, float x1, float y1) {
        return new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
