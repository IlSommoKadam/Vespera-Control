package com.vaonis.vesperacontrol.ui.anteprima;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/**
 * Livelli + autostretch (stesso modello dell'editor Windows / Siril).
 */
public final class ImageAdjust {

    public static final class Params {
        public float black = 0f;       // 0..1
        public float white = 1f;       // 0..1
        public float mid = 1f;         // gamma 0.2..3
        public float saturation = 1f;  // 0..2

        public Params() {}

        public Params(float black, float white, float mid, float saturation) {
            this.black = black;
            this.white = white;
            this.mid = mid;
            this.saturation = saturation;
        }

        public Params copy() {
            return new Params(black, white, mid, saturation);
        }
    }

    private ImageAdjust() {}

    public static Params normalize(Params in) {
        Params p = in == null ? new Params() : in.copy();
        p.black = clamp(p.black, 0f, 0.98f);
        p.white = clamp(p.white, p.black + 0.01f, 1f);
        p.mid = clamp(p.mid, 0.2f, 3f);
        p.saturation = clamp(p.saturation, 0f, 2.5f);
        return p;
    }

    public static Params estimateAutostretch(Bitmap src) {
        return estimateAutostretch(src, 0.5f, 99.8f);
    }

    public static Params estimateAutostretch(Bitmap src, float lowPct, float highPct) {
        Params out = new Params();
        if (src == null || src.isRecycled()) return out;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return out;

        int step = 1;
        long pixels = (long) w * h;
        if (pixels > 2_000_000L) {
            step = (int) Math.ceil(Math.sqrt(pixels / 2_000_000.0));
        }

        int[] hist = new int[256];
        int[] row = new int[w];
        long total = 0;
        for (int y = 0; y < h; y += step) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += step) {
                int c = row[x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int yv = (r * 30 + g * 59 + b * 11) / 100;
                hist[yv]++;
                total++;
            }
        }
        if (total <= 0) return out;
        int lo = percentileFromHist(hist, total, lowPct);
        int hi = percentileFromHist(hist, total, highPct);
        if (hi <= lo) hi = Math.min(255, lo + 1);
        out.black = lo / 255f;
        out.white = hi / 255f;
        out.mid = 1f;
        out.saturation = 1f;
        return out;
    }

    public static Bitmap apply(Bitmap src, Params params) {
        if (src == null || src.isRecycled()) return null;
        Params p = normalize(params);
        float black = p.black;
        float white = p.white;
        float mid = p.mid;
        float sat = p.saturation;

        int[] lut = buildLut(black, white, mid);
        int w = src.getWidth();
        int h = src.getHeight();
        Bitmap leveled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = (c >>> 24) & 0xFF;
            int r = lut[(c >> 16) & 0xFF];
            int g = lut[(c >> 8) & 0xFF];
            int b = lut[c & 0xFF];
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        leveled.setPixels(pixels, 0, w, 0, 0, w, h);

        if (Math.abs(sat - 1f) <= 0.01f) {
            return leveled;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(sat);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(leveled, 0, 0, paint);
        if (leveled != src) leveled.recycle();
        return out;
    }

    private static int[] buildLut(float black, float white, float mid) {
        int b = Math.round(black * 255f);
        int w = Math.round(white * 255f);
        int span = Math.max(1, w - b);
        double invGamma = 1.0 / mid;
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double v;
            if (i <= b) {
                v = 0.0;
            } else if (i >= w) {
                v = 1.0;
            } else {
                v = (i - b) / (double) span;
                v = Math.pow(v, invGamma);
            }
            lut[i] = clampByte((int) Math.round(v * 255.0));
        }
        return lut;
    }

    private static int percentileFromHist(int[] hist, long total, float pct) {
        double target = Math.max(0.0, Math.min(100.0, pct)) / 100.0 * total;
        long acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= target) return i;
        }
        return 255;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
