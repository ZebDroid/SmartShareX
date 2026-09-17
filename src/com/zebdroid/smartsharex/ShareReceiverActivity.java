package com.zebdroid.smartsharex;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point registered in the manifest for ACTION_SEND / ACTION_SEND_MULTIPLE.
 * Runs standalone — it is NOT an App Inventor Form, so it cannot fire
 * SimpleEvents itself. Its only jobs:
 *
 *  1. Appear instantly when the user picks this app from the Android Sharesheet.
 *  2. Parse the incoming share defensively.
 *  3. Show a bottom sheet immediately: the developer's own AddItem() items if
 *     any were configured (warm start, see SheetConfigCache), otherwise the
 *     built-in "Open in app" / "Save for later" pair (cold start).
 *  4. Publish the resolved data to ShareDataHolder and forward to the host
 *     app's launcher Activity — this ALWAYS happens, because App Inventor
 *     blocks can only run on a live Form, and there's no way to run a
 *     developer's blocks without one.
 *
 * Each item is either:
 *   background = false -> normal open. The host app's launcher Activity
 *     comes to the front exactly as the user would expect, SmartShareX
 *     fires ShareReceived(contentType, actionId, isBackground=false).
 *   background = true -> the host app's launcher Activity is still started
 *     (unavoidable — see above) but with no transition animation, and
 *     ShareReceived fires with isBackground=true. The developer's blocks
 *     are expected to do their (usually short) work and then call
 *     SmartShareX.CloseBackgroundShare() immediately, so the app is visible
 *     for as little time as technically possible. This is a MINIMIZED
 *     flash, not a guaranteed zero-frame invisible operation — genuinely
 *     invisible background work would require the developer's own Screen
 *     to carry a translucent theme, which this extension has no way to set
 *     on their behalf.
 */
public class ShareReceiverActivity extends Activity {

  private NativeBottomSheet sheet;
  private ShareDataHolder.Payload parsedPayload;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    handleIntent(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    // Another share arrived while we were already open (singleTask reuse).
    // Dismiss whatever sheet is up and process the new one instead of stacking.
    if (sheet != null && sheet.isShowing()) {
      sheet.dismiss();
    }
    handleIntent(intent);
  }

  private void handleIntent(Intent intent) {
    if (intent == null) {
      finishSafely();
      return;
    }
    String action = intent.getAction();
    if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
      // Not a share we understand — never crash, just bail out quietly.
      finishSafely();
      return;
    }

    try {
      parsedPayload = parse(intent, action);
    } catch (Exception e) {
      // Malformed intent from a misbehaving sender app — degrade gracefully
      // instead of crashing the receiver.
      ShareDataHolder.publish(new ShareDataHolder.Payload(
          action, intent.getType(), "", "", "", "", new ArrayList<SharedFileInfo>(), "error", "", false));
      finishSafely();
      return;
    }

    showSheetFor(parsedPayload);
  }

  private ShareDataHolder.Payload parse(Intent intent, String action) {
    String intentMime = intent.getType();
    String text = intent.getStringExtra(Intent.EXTRA_TEXT);
    String htmlText = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
    String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
    String url = text != null ? ShareUtils.extractFirstUrl(text) : "";

    List<SharedFileInfo> files = new ArrayList<>();
    String contentType;

    if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
      ArrayList<Uri> uris = getParcelableArrayListCompat(intent);
      if (uris != null) {
        for (Uri u : uris) {
          if (u == null) continue;
          String mime = ShareUtils.resolveMimeType(this, u, intentMime);
          ShareUtils.FileMeta meta = ShareUtils.queryFileMeta(this, u);
          files.add(new SharedFileInfo(u.toString(), meta.name, mime, meta.sizeBytes));
        }
      }
      contentType = files.isEmpty() ? "text" : "multiple_files";
    } else {
      Uri stream = getParcelableExtraCompat(intent);
      if (stream != null) {
        String mime = ShareUtils.resolveMimeType(this, stream, intentMime);
        ShareUtils.FileMeta meta = ShareUtils.queryFileMeta(this, stream);
        files.add(new SharedFileInfo(stream.toString(), meta.name, mime, meta.sizeBytes));
        contentType = "single_file";
      } else {
        contentType = "text";
      }
    }

    return new ShareDataHolder.Payload(
        action, intentMime, text == null ? "" : text, htmlText == null ? "" : htmlText,
        subject == null ? "" : subject, url, files, contentType, "", false);
  }

  @SuppressWarnings("deprecation")
  private ArrayList<Uri> getParcelableArrayListCompat(Intent intent) {
    // getParcelableArrayListExtra(String, Class) needs API 33+; the
    // single-arg overload is deprecated but still fully functional and is
    // the only option that also works on the app's minSdk.
    return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
  }

  @SuppressWarnings("deprecation")
  private Uri getParcelableExtraCompat(Intent intent) {
    Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
    return p instanceof Uri ? (Uri) p : null;
  }

  /**
   * Sheet disabled (SheetConfigCache.isSheetShown() == false) — the user
   * should never see a picker at all, the share should reach the app as
   * directly as opening it normally would. Resolution: if the developer
   * has configured at least one item via AddItem(), its id/background is
   * used as-is (added order = intended priority, and with no sheet only
   * one action can ever run, so the first one is the only sensible choice).
   * With zero configured items, this is a plain passthrough: actionId ""
   * and a normal foreground open, indistinguishable from the app being
   * opened any other way except that GetX() calls will have the share data.
   */
  private void skipSheetAndForward(ShareDataHolder.Payload payload) {
    List<SheetConfigCache.ItemConfig> configuredItems = SheetConfigCache.getItems();
    String actionId = "";
    boolean background = false;
    if (!configuredItems.isEmpty()) {
      SheetConfigCache.ItemConfig first = configuredItems.get(0);
      actionId = first.id;
      background = first.background;
    }
    resolveSelection(actionId, "", !background);
  }

  private void showSheetFor(ShareDataHolder.Payload payload) {
    if (!SheetConfigCache.isSheetShown()) {
      skipSheetAndForward(payload);
      return;
    }

    sheet = new NativeBottomSheet(this);
    sheet.setStyle(SheetConfigCache.getStyle());
    // Default: follow the device's system dark/light setting, same as any
    // other native Android UI. The DarkMode designer property can force it.
    String mode = SheetConfigCache.getDarkMode();
    boolean dark = "dark".equals(mode) || (!"light".equals(mode) && isSystemDarkMode());
    sheet.setDarkMode(dark);

    // Header icon is resolved once, centrally, BEFORE the developer/default
    // branch below — same reasoning as the subtitle fix: if this only ran
    // inside applyDefaultConfig(), the icon would silently disappear the
    // moment a developer called AddItem()/SetSheetTitle() and moved onto
    // the applyDeveloperConfig() path.
    sheet.setHeaderIcon(resolveHeaderIcon(payload));

    if (SheetConfigCache.hasConfig()) {
      applyDeveloperConfig(sheet, payload);
    } else {
      applyDefaultConfig(sheet, payload);
    }

    sheet.setOnItemSelectedListener(new NativeBottomSheet.OnItemSelectedListener() {
      @Override
      public void onItemSelected(String id, String title, boolean foreground) {
        resolveSelection(id, title, foreground);
      }
    });
    sheet.setOnDismissListener(new NativeBottomSheet.OnDismissListener() {
      @Override
      public void onDismiss() {
        if (isFinishing()) return;
        // Sheet closed without an explicit item pick (backdrop tap / swipe /
        // back press). Treated as a silent dismissal, not a selection — the
        // share data is still published (so a later GetX() call from blocks
        // can see it if the app happens to be opened some other way), but we
        // do NOT force-launch the host app just because the sheet closed.
        ShareDataHolder.publish(parsedPayload);
        finishSafely();
      }
    });
    sheet.show();
  }

  /**
   * Resolves the sheet's header icon: developer's custom icon (CUSTOM),
   * explicitly none (HIDDEN), or the automatic file-type badge (AUTO,
   * the default — see FileTypeIcon).
   */
  private Drawable resolveHeaderIcon(ShareDataHolder.Payload payload) {
    switch (SheetConfigCache.getIconMode()) {
      case CUSTOM:
        Drawable custom = loadCustomIcon(SheetConfigCache.getIconPath());
        return custom != null ? custom : FileTypeIcon.forPayload(payload);
      case HIDDEN:
        return null;
      case AUTO:
      default:
        return FileTypeIcon.forPayload(payload);
    }
  }

  /**
   * Loads a developer-supplied icon from an App Inventor asset name first
   * (the common case — media files bundled with the project), falling back
   * to treating it as an absolute file path. Returns null on any failure so
   * the caller can fall back to the automatic file-type badge instead of
   * showing a blank header.
   */
  private Drawable loadCustomIcon(String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    try {
      return Drawable.createFromStream(getAssets().open(path), null);
    } catch (Exception ignoredAsset) {
      try {
        return Drawable.createFromPath(path);
      } catch (Exception ignoredFile) {
        return null;
      }
    }
  }

  private void applyDeveloperConfig(NativeBottomSheet sheet, ShareDataHolder.Payload payload) {
    sheet.setTitle(SheetConfigCache.getTitle());
    sheet.setSubtitle(resolveDeveloperSubtitle(payload));
    sheet.setCancelable(true);
    for (SheetConfigCache.ItemConfig item : SheetConfigCache.getItems()) {
      sheet.addItem(item.id, item.title, null, !item.background);
    }
  }

  /**
   * Resolves what the subtitle should show in developer-config mode. Default
   * (AUTO) mirrors applyDefaultConfig()'s behavior so context info (filename,
   * link, text preview) isn't lost just because a developer called
   * AddItem()/SetSheetTitle() — see SheetConfigCache's class javadoc for why.
   * CUSTOM/HIDDEN are explicit developer opt-outs via SetSheetSubtitle()/
   * HideSheetSubtitle().
   */
  private String resolveDeveloperSubtitle(ShareDataHolder.Payload payload) {
    switch (SheetConfigCache.getSubtitleMode()) {
      case CUSTOM:
        return SheetConfigCache.getSubtitle();
      case HIDDEN:
        return "";
      case AUTO:
      default:
        return defaultSubtitleFor(payload);
    }
  }

  private void applyDefaultConfig(NativeBottomSheet sheet, ShareDataHolder.Payload payload) {
    sheet.setTitle(defaultTitleFor(payload));
    sheet.setSubtitle(defaultSubtitleFor(payload));
    // No developer AddItem() config exists — the built-in "Open"/"Save"
    // pair is used, but each is individually toggle-able and re-titleable
    // via Designer properties (SheetConfigCache.openItem*/saveItem*) so a
    // developer never needs Initialize blocks just to rename or hide one
    // of them. Empty title = keep the automatic per-content-type label.
    boolean addedAny = false;
    if (SheetConfigCache.isOpenItemEnabled()) {
      String title = SheetConfigCache.getOpenItemTitle();
      sheet.addItem("open", title.isEmpty() ? "Open in app" : title, null, true);
      addedAny = true;
    }
    if (SheetConfigCache.isSaveItemEnabled()) {
      String title = SheetConfigCache.getSaveItemTitle();
      sheet.addItem("save_later", title.isEmpty() ? defaultSaveLabelFor(payload) : title, null, false);
      addedAny = true;
    }
    if (!addedAny) {
      // Safety net: a developer who disables BOTH via Designer properties
      // would otherwise get an item-less sheet with nothing to tap —
      // always guarantee at least a way forward.
      sheet.addItem("open", "Open in app", null, true);
    }
  }

  private String defaultTitleFor(ShareDataHolder.Payload payload) {
    switch (payload.contentType) {
      case "single_file": return "File received";
      case "multiple_files": return payload.files.size() + " files received";
      case "error": return "Couldn't read shared content";
      default: return payload.url.isEmpty() ? "Text received" : "Link received";
    }
  }

  private String defaultSubtitleFor(ShareDataHolder.Payload payload) {
    switch (payload.contentType) {
      case "single_file":
        return payload.files.isEmpty() ? "" : cleanPreview(payload.files.get(0).fileName, 60);
      case "multiple_files":
        return "Tap to continue";
      default:
        return !payload.url.isEmpty()
            ? cleanPreview(payload.url, 90)
            : cleanPreview(payload.text, 140);
    }
  }

  /**
   * Collapses a shared string into a clean, single-paragraph preview safe
   * to hand to a 2-line, ellipsized subtitle view: newlines/tabs become
   * plain spaces (so a multi-paragraph share doesn't look like a jagged
   * wrapped blob), repeated whitespace is collapsed, and the result is
   * capped at maxChars with a trailing ellipsis. The view-level
   * maxLines(2) + TruncateAt.END in NativeBottomSheet is the real safety
   * net for height; this just makes the visible text itself read cleanly
   * rather than relying on mid-word line-wrap cutoffs.
   */
  private String cleanPreview(String raw, int maxChars) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    String collapsed = raw.replaceAll("\\s+", " ").trim();
    if (collapsed.length() <= maxChars) {
      return collapsed;
    }
    return collapsed.substring(0, maxChars).trim() + "\u2026";
  }

  /**
   * Label for the built-in "save" item, adapted to what was actually
   * shared. Same actionId ("save_later") and same background=true
   * behavior across every case — this only changes the visible text, so
   * a save-for-later app, a notes app, a downloader, and a backup app all
   * get a label that already sounds native to what they do, with zero
   * per-app configuration required.
   */
  private String defaultSaveLabelFor(ShareDataHolder.Payload payload) {
    switch (payload.contentType) {
      case "single_file":
        return "Save file";
      case "multiple_files":
        return "Save all";
      case "error":
        return "Dismiss";
      default:
        return !payload.url.isEmpty() ? "Save link" : "Save note";
    }
  }

  /**
   * Called once the user has tapped an item (or the sheet resolved to one
   * via {@code applyDefaultConfig}). Both modes forward to the host app —
   * see the class javadoc for why background mode can't skip this — the
   * fork is only in the animation and the isBackground flag passed along.
   */
  private void resolveSelection(String selectedId, String selectedTitle, boolean foreground) {
    ShareDataHolder.publish(parsedPayload.withAction(selectedId, !foreground));

    Intent forward = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (forward != null) {
      forward.putExtra("smartsharex_selected_id", selectedId);
      forward.putExtra("smartsharex_selected_title", selectedTitle);
      forward.putExtra("smartsharex_has_share", true);
      forward.putExtra("smartsharex_background", !foreground);
      forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      attachFileGrants(forward);
      startActivity(forward);
      if (!foreground) {
        // No slide/fade for either end of this handoff — this is the only
        // lever we have to shorten what's visible in background mode. Not
        // affected by the LaunchAnimation property — see its javadoc.
        overridePendingTransition(0, 0);
      } else {
        applyForegroundLaunchAnimation();
      }
    }
    // Not finishSafely() here on purpose: that helper unconditionally calls
    // overridePendingTransition(0, 0), which would immediately stomp
    // whatever transition was just set above for the foreground case (both
    // calls target the SAME pending transition — startActivity()'s and
    // finish()'s overridePendingTransition calls aren't independent, the
    // later one simply wins). Finish plainly instead; the transition was
    // already decided above.
    if (!isFinishing()) {
      finish();
    }
  }

  /**
   * Applies the developer's LaunchAnimation choice to the handoff into the
   * host app, for foreground opens only. "default" deliberately calls
   * nothing — that leaves Android's own activity-transition animation
   * (device/theme default) in place, same as opening the app any other
   * way. "fade"/"slide" use built-in system animation resources so no
   * bundled anim assets are needed.
   */
  private void applyForegroundLaunchAnimation() {
    switch (SheetConfigCache.getLaunchAnimation()) {
      case "fade":
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        break;
      case "slide":
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
        break;
      case "none":
        overridePendingTransition(0, 0);
        break;
      case "default":
      default:
        // Leave it alone.
        break;
    }
  }

  /**
   * FLAG_GRANT_READ_URI_PERMISSION only does something if the intent it's
   * set on actually CARRIES the URI (as setData() or in an EXTRA_STREAM
   * Uri/ArrayList<Uri>) — it does not retroactively re-grant access to
   * URIs that merely exist elsewhere (e.g. in ShareDataHolder's Payload).
   * The previous version set the flag on `forward` without ever putting
   * the URIs on it, so the flag was a no-op and GetFileUri()/GetFileUriAt()
   * could throw SecurityException once the developer's Screen actually
   * tried to read the file, depending on the source app's provider.
   *
   * We fix this two ways for reliability across OEMs/providers:
   *  1. Actually attach the URI(s) to `forward` so the flag has something
   *     to act on for same-process delivery.
   *  2. Explicitly call grantUriPermission() for each file, which is the
   *     only path guaranteed to persist the grant regardless of how (or
   *     whether) the launched Activity reads it back off the intent.
   */
  private void attachFileGrants(Intent forward) {
    List<SharedFileInfo> files = parsedPayload.files;
    if (files.isEmpty()) {
      return;
    }

    String pkg = getPackageName();
    for (SharedFileInfo f : files) {
      if (f.uri.isEmpty()) continue;
      Uri uri = Uri.parse(f.uri);
      if (!"content".equals(uri.getScheme())) continue; // file://, etc. need no grant
      try {
        grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException ignored) {
        // Sender's provider refused the grant (e.g. permission already
        // revoked) — nothing more we can do; downstream GetFileUri() calls
        // may fail for this file, but we must not crash the receiver.
      }
    }

    if (files.size() == 1) {
      forward.setData(Uri.parse(files.get(0).uri));
    } else {
      ArrayList<Uri> uris = new ArrayList<>();
      for (SharedFileInfo f : files) {
        if (!f.uri.isEmpty()) uris.add(Uri.parse(f.uri));
      }
      forward.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
    }
    forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  private boolean isSystemDarkMode() {
    int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return nightMode == Configuration.UI_MODE_NIGHT_YES;
  }

  private void finishSafely() {
    if (!isFinishing()) {
      finish();
    }
    overridePendingTransition(0, 0);
  }
}