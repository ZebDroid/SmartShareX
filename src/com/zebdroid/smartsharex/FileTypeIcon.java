package com.zebdroid.smartsharex;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/**
 * Builds a small, dependency-free "file type" badge for the sheet header —
 * a colored rounded square with a short label (e.g. "PDF", "IMG", "ZIP") —
 * drawn directly with Canvas/Paint so no bundled drawable assets are
 * needed, matching the rest of NativeBottomSheet's hand-built view style.
 *
 * Category + color are picked from the share's mime type (or contentType,
 * for text/link/multiple-file shares) so the sheet gives an at-a-glance
 * sense of *what* was shared before the user even reads the title/subtitle.
 * This is the AUTO icon — see SheetConfigCache.IconMode for how a developer
 * can override or hide it.
 */
final class FileTypeIcon extends Drawable {

  private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final String label;

  private FileTypeIcon(int color, String label) {
    this.label = label;
    bgPaint.setColor(color);
    textPaint.setColor(Color.WHITE);
    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setFakeBoldText(true);
  }

  @Override
  public void draw(Canvas canvas) {
    Rect b = getBounds();
    float r = b.width() * 0.22f;
    canvas.drawRoundRect(new RectF(b), r, r, bgPaint);
    textPaint.setTextSize(b.width() * (label.length() > 3 ? 0.24f : 0.32f));
    Paint.FontMetrics fm = textPaint.getFontMetrics();
    float y = b.centerY() - (fm.ascent + fm.descent) / 2f;
    canvas.drawText(label, b.centerX(), y, textPaint);
  }

  @Override
  public void setAlpha(int alpha) {
    bgPaint.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(ColorFilter colorFilter) {
    bgPaint.setColorFilter(colorFilter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  /** Picks a badge for the current share based on content type / mime type. */
  static Drawable forPayload(ShareDataHolder.Payload payload) {
    switch (payload.contentType) {
      case "multiple_files":
        return new FileTypeIcon(Color.parseColor("#5E35B1"), String.valueOf(payload.files.size()));
      case "single_file":
        String mime = payload.files.isEmpty() ? "" : payload.files.get(0).mimeType;
        String name = payload.files.isEmpty() ? "" : payload.files.get(0).fileName;
        return forMime(mime, name);
      case "error":
        return new FileTypeIcon(Color.parseColor("#757575"), "!");
      default:
        return payload.url.isEmpty()
            ? new FileTypeIcon(Color.parseColor("#757575"), "Aa")
            : new FileTypeIcon(Color.parseColor("#3949AB"), "URL");
    }
  }

  private static Drawable forMime(String mime, String fileName) {
    String safeMime = mime == null ? "" : mime;
    String ext = extensionOf(fileName);

    if (safeMime.startsWith("image/")) return new FileTypeIcon(Color.parseColor("#43A047"), "IMG");
    if (safeMime.startsWith("video/")) return new FileTypeIcon(Color.parseColor("#E53935"), "\u25B6");
    if (safeMime.startsWith("audio/")) return new FileTypeIcon(Color.parseColor("#FB8C00"), "\u266A");
    if (safeMime.equals("application/pdf") || ext.equals("pdf")) {
      return new FileTypeIcon(Color.parseColor("#E53935"), "PDF");
    }
    if (safeMime.contains("wordprocessingml") || safeMime.equals("application/msword")
        || ext.equals("doc") || ext.equals("docx")) {
      return new FileTypeIcon(Color.parseColor("#1E88E5"), "DOC");
    }
    if (safeMime.contains("spreadsheetml") || safeMime.equals("application/vnd.ms-excel")
        || ext.equals("xls") || ext.equals("xlsx") || ext.equals("csv")) {
      return new FileTypeIcon(Color.parseColor("#2E7D32"), "XLS");
    }
    if (safeMime.contains("presentationml") || safeMime.equals("application/vnd.ms-powerpoint")
        || ext.equals("ppt") || ext.equals("pptx")) {
      return new FileTypeIcon(Color.parseColor("#F4511E"), "PPT");
    }
    if (ext.equals("zip") || ext.equals("rar") || ext.equals("7z") || safeMime.contains("zip")) {
      return new FileTypeIcon(Color.parseColor("#6D4C41"), "ZIP");
    }
    if (ext.equals("apk") || safeMime.contains("android.package-archive")) {
      return new FileTypeIcon(Color.parseColor("#43A047"), "APK");
    }
    if (safeMime.startsWith("text/") || ext.equals("txt")) {
      return new FileTypeIcon(Color.parseColor("#757575"), "TXT");
    }
    return new FileTypeIcon(Color.parseColor("#757575"), "FILE");
  }

  private static String extensionOf(String fileName) {
    if (fileName == null) return "";
    int dot = fileName.lastIndexOf('.');
    return dot >= 0 && dot < fileName.length() - 1
        ? fileName.substring(dot + 1).toLowerCase(Locale.US)
        : "";
  }
}
