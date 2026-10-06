package com.vaonis.vesperacontrol.ui.anteprima;

import android.graphics.Bitmap;

/** Passaggio bitmap alla Activity editor (evita Intent oversized). */
public final class PreviewEditorSession {
    private static Bitmap source;
    private static String title = "Editor anteprima";
    private static String suggestedName = "vespera-preview-edited.jpg";

    private PreviewEditorSession() {}

    public static synchronized void set(Bitmap bitmap, String label, String fileName) {
        source = bitmap;
        if (label != null && !label.trim().isEmpty()) {
            title = "Editor · " + label.trim();
        } else {
            title = "Editor anteprima";
        }
        String name = (fileName == null || fileName.trim().isEmpty())
                ? "vespera-preview.jpg"
                : fileName.trim();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".jpg";
        if (!stem.toLowerCase().endsWith("-edited")) {
            stem = stem + "-edited";
        }
        suggestedName = stem + ext;
    }

    public static synchronized Bitmap getSource() {
        return source;
    }

    public static synchronized String getTitle() {
        return title;
    }

    public static synchronized String getSuggestedName() {
        return suggestedName;
    }

    public static synchronized void clear() {
        source = null;
    }
}
