package com.zebdroid.smartsharex;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free helpers. Everything here is defensive: content://
 * URIs from arbitrary sender apps can throw SecurityException (revoked
 * permission), return nulls, or point at rows that don't have the columns
 * we expect — never trust them.
 */
public final class ShareUtils {

  private ShareUtils() {}

  // Generic URL matcher — deliberately NOT tied to any specific domain
  // (no Instagram/YouTube/TikTok special-casing, per spec).
  private static final Pattern URL_PATTERN = Pattern.compile(
      "(https?://\\S+)",
      Pattern.CASE_INSENSITIVE);

  /**
   * Pulls the first http(s) URL out of an arbitrary shared text string.
   * Returns "" (never null) if none is found, and strips common trailing
   * punctuation that gets swept up by the greedy \S+ match.
   */
  public static String extractFirstUrl(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    Matcher m = URL_PATTERN.matcher(text);
    if (!m.find()) {
      return "";
    }
    String url = m.group(1);
    // Trim trailing punctuation that isn't part of the URL itself.
    while (url.length() > 0 && ")].,!?\"'".indexOf(url.charAt(url.length() - 1)) >= 0) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  /** Plain holder for a single-query display-name + size lookup. */
  public static final class FileMeta {
    public final String name;
    public final long sizeBytes;

    FileMeta(String name, long sizeBytes) {
      this.name = name;
      this.sizeBytes = sizeBytes;
    }
  }

  /**
   * Best-effort display name + size for a content:// or file:// URI, in a
   * SINGLE cursor query. Both OpenableColumns.DISPLAY_NAME and .SIZE come
   * back from the same provider round-trip, so there is no reason to query
   * twice — the previous version called queryDisplayName() and querySize()
   * separately, doubling the cross-process IPC cost per file. For
   * ACTION_SEND_MULTIPLE with many files, all of this runs synchronously on
   * the main thread in onCreate() before the sheet is shown, so halving the
   * per-file query count directly cuts into how long the sheet takes to
   * appear. Never throws.
   */
  public static FileMeta queryFileMeta(Context context, Uri uri) {
    if (context == null || uri == null) {
      return new FileMeta("", -1);
    }
    if ("content".equals(uri.getScheme())) {
      Cursor cursor = null;
      try {
        cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
          String name = null;
          long size = -1;
          int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
          if (nameIdx >= 0) {
            name = cursor.getString(nameIdx);
          }
          int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
            size = cursor.getLong(sizeIdx);
          }
          if (name != null) {
            return new FileMeta(name, size);
          }
          // Name column missing/null — still keep whatever size we got,
          // fall through to the path-based name guess below.
          String path = uri.getLastPathSegment();
          return new FileMeta(path == null ? "" : path, size);
        }
      } catch (Exception ignored) {
        // Revoked permission / dead provider — fall through to path guess.
      } finally {
        if (cursor != null) {
          cursor.close();
        }
      }
    }
    String path = uri.getLastPathSegment();
    return new FileMeta(path == null ? "" : path, -1);
  }

  /** Resolves a MIME type for a URI, falling back to the extension, then to a safe default. */
  public static String resolveMimeType(Context context, Uri uri, String intentMimeType) {
    if (intentMimeType != null && !intentMimeType.isEmpty() && !"*/*".equals(intentMimeType)) {
      return intentMimeType;
    }
    if (context != null && uri != null) {
      try {
        String fromResolver = context.getContentResolver().getType(uri);
        if (fromResolver != null) {
          return fromResolver;
        }
      } catch (Exception ignored) {
        // fall through
      }
      String ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
      if (ext != null && !ext.isEmpty()) {
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        if (guessed != null) {
          return guessed;
        }
      }
    }
    return "application/octet-stream";
  }
}
