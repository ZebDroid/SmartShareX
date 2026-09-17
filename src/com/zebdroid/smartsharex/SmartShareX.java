package com.zebdroid.smartsharex;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.common.PropertyTypeConstants;

import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.Form;
import com.google.appinventor.components.runtime.OnDestroyListener;
import com.google.appinventor.components.runtime.OnNewIntentListener;
import com.google.appinventor.components.runtime.OnResumeListener;

import java.util.ArrayList;
import java.util.List;

/**
 * SmartShareX — universal Android Share Target extension. Deliberately
 * small: one event to react to, a handful of GetX() blocks to read what was
 * shared, and just enough sheet control (AddItem/SetSheetTitle) to
 * customize the two built-in options. Nothing else.
 *
 * Data flow is a single, predictable path: ShareReceiverActivity resolves
 * the incoming share and parks it in ShareDataHolder as "pending"; this
 * class picks it up in onResume()/onNewIntent(). See ShareDataHolder's
 * javadoc for why there is deliberately no other, "faster" path.
 *
 * checkPending() is never called directly from onResume()/onNewIntent() —
 * it's posted via mainHandler.post() instead. Reason: on a COLD start,
 * Android runs onCreate -> onStart -> onResume for a freshly launched
 * Activity as one synchronous batch, before the main Looper's message queue
 * gets a chance to drain. Niotron/AI2 dispatches the Screen's Initialize
 * event as a Handler-posted Runnable from onCreate(), which means on cold
 * start it is still SITTING IN THE QUEUE, not yet run, at the exact moment
 * onResume() fires. Calling checkPending()/ShareReceived() synchronously
 * from onResume() would then run before Initialize's own AddItem/
 * SetSheetTitle calls (or worse, race unpredictably depending on
 * device/OEM scheduling — which is exactly what caused the "only fires
 * when the app is already alive, and even then sometimes" symptom).
 * Posting defers our check to the NEXT queue iteration, so anything already
 * queued ahead of it (Initialize's Runnable) runs first, in FIFO order —
 * no dependency on any framework-specific "onInitialize" hook needed.
 */
public class SmartShareX extends AndroidNonvisibleComponent
    implements OnResumeListener, OnNewIntentListener, OnDestroyListener {

  private final Form form;
  private final Context context;

  private String lastText = "";
  private String lastUrl = "";
  private String lastContentType = "";
  private String lastActionId = "";
  private boolean lastIsBackground = false;
  private List<SharedFileInfo> lastFiles = new ArrayList<>();

  // Safety net for background-mode shares: if the developer never calls
  // CloseBackgroundShare(), the app must not just sit there forever having
  // silently "opened" — auto-finish after a grace period. Configurable via
  // SetBackgroundTimeout(); 8s if the developer never sets it.
  private long backgroundAutoCloseMs = 8000;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable autoCloseRunnable = new Runnable() {
    @Override public void run() { CloseBackgroundShare(); }
  };
  private final Runnable checkPendingRunnable = new Runnable() {
    @Override public void run() { checkPending(); }
  };

  public SmartShareX(ComponentContainer container) {
    super(container.$form());
    this.form = container.$form();
    this.context = form;
    form.registerForOnResume(this);
    form.registerForOnNewIntent(this);
    form.registerForOnDestroy(this);

    // A fresh SmartShareX instance means a fresh Screen1 instance (this
    // constructor runs once per Screen1 creation, before Initialize). Any
    // AddItem()/SetSheetTitle() config left over in the static
    // SheetConfigCache belonged to whatever RENDERED sheet already launched
    // this instance (that render is done) — clear it now so this instance's
    // own Initialize block starts from a clean slate instead of appending on
    // top of the previous instance's items across repeated shares.
    SheetConfigCache.reset();
  }

  @Override
  public void onResume() {
    mainHandler.removeCallbacks(checkPendingRunnable);
    mainHandler.post(checkPendingRunnable);
  }

  @Override
  public void onNewIntent(Intent intent) {
    mainHandler.removeCallbacks(checkPendingRunnable);
    mainHandler.post(checkPendingRunnable);
  }

  @Override
  public void onDestroy() {
    mainHandler.removeCallbacks(autoCloseRunnable);
    mainHandler.removeCallbacks(checkPendingRunnable);
  }

  private void checkPending() {
    ShareDataHolder.Payload payload = ShareDataHolder.consumePending();
    if (payload == null) {
      return;
    }
    lastText = payload.text;
    lastUrl = payload.url;
    lastContentType = payload.contentType;
    lastActionId = payload.actionId;
    lastIsBackground = payload.background;
    lastFiles = payload.files;

    if ("error".equals(payload.contentType)) {
      ShareError("Received a malformed share intent and could not parse it.");
      return;
    }
    if (payload.background) {
      mainHandler.removeCallbacks(autoCloseRunnable);
      mainHandler.postDelayed(autoCloseRunnable, backgroundAutoCloseMs);
    }
    ShareReceived(lastContentType, lastActionId, lastIsBackground);
  }

  // ==========================================================================
  // Events
  // ==========================================================================

  @SimpleEvent(description = "Fired whenever a share is received. contentType is \"text\", \"single_file\", or \"multiple_files\". actionId is the id of the item the user tapped (\"open\"/\"save_later\" for the built-in options, or your own id from AddItem). isBackground is true for \"quiet\" shares — see CloseBackgroundShare.")
  public void ShareReceived(String contentType, String actionId, boolean isBackground) {
    EventDispatcher.dispatchEvent(this, "ShareReceived", contentType, actionId, isBackground);
  }

  @SimpleEvent(description = "Fired if an incoming share could not be read.")
  public void ShareError(String message) {
    EventDispatcher.dispatchEvent(this, "ShareError", message);
  }

  // ==========================================================================
  // Background-mode control
  // ==========================================================================

  @SimpleFunction(description = "Call this as soon as your blocks are done handling a background share (isBackground = true). Closes the app again so the user goes straight back to whatever app they shared from. Safe to call anytime — does nothing if the current share wasn't background mode. If you never call it, the app closes itself after a few seconds anyway.")
  public void CloseBackgroundShare() {
    mainHandler.removeCallbacks(autoCloseRunnable);
    if (!lastIsBackground) {
      return;
    }
    if (context instanceof Activity) {
      Activity activity = (Activity) context;
      if (!activity.isFinishing()) {
        activity.finish();
        activity.overridePendingTransition(0, 0);
      }
    }
  }

  @SimpleFunction(description = "How many seconds a background share (isBackground = true) waits for CloseBackgroundShare() before auto-closing itself. Default is 8 seconds. Call from Initialize; values below 1 are ignored.")
  public void SetBackgroundTimeout(int seconds) {
    if (seconds < 1) {
      return;
    }
    backgroundAutoCloseMs = seconds * 1000L;
  }

  // ==========================================================================
  // Reading what was shared
  // ==========================================================================

  @SimpleFunction(description = "One-block summary of what was just shared — the simplest way to react to ShareReceived. For text it's the text itself (or the URL, if a link was shared). For a single file it's the file name. For multiple files it's \"N files\". Use the blocks below only if you need more than this.")
  public String GetSharedSummary() {
    switch (lastContentType) {
      case "single_file":
        return lastFiles.isEmpty() ? "" : lastFiles.get(0).fileName;
      case "multiple_files":
        return lastFiles.size() + " files";
      case "text":
        return lastUrl.isEmpty() ? lastText : lastUrl;
      default:
        return "";
    }
  }

  @SimpleFunction(description = "Plain text of the last share, if any.")
  public String GetText() {
    return lastText;
  }

  @SimpleFunction(description = "The first http(s) URL found inside the shared text, or empty if none.")
  public String GetUrl() {
    return lastUrl;
  }

  @SimpleFunction(description = "Content URI of the shared file (for a single_file share).")
  public String GetFileUri() {
    return lastFiles.isEmpty() ? "" : lastFiles.get(0).uri;
  }

  @SimpleFunction(description = "Display name of the shared file (for a single_file share).")
  public String GetFileName() {
    return lastFiles.isEmpty() ? "" : lastFiles.get(0).fileName;
  }

  @SimpleFunction(description = "Number of files in the last share (0 for a text-only share).")
  public int GetFileCount() {
    return lastFiles.size();
  }

  @SimpleFunction(description = "Content URI of the file at the given 0-based index (for a multiple_files share).")
  public String GetFileUriAt(int index) {
    return (index >= 0 && index < lastFiles.size()) ? lastFiles.get(index).uri : "";
  }

  @SimpleFunction(description = "File name of the file at the given 0-based index (for a multiple_files share).")
  public String GetFileNameAt(int index) {
    return (index >= 0 && index < lastFiles.size()) ? lastFiles.get(index).fileName : "";
  }

  // ==========================================================================
  // Sheet customization — optional. If you never call these, the sheet
  // shows the built-in "Open in app" / "Save for later" pair on its own.
  // ==========================================================================

  @SimpleFunction(description = "Changes the sheet's title.")
  public void SetSheetTitle(String title) {
    SheetConfigCache.setTitle(title);
  }

  @SimpleFunction(description = "Sets custom subtitle text under the sheet's title. Overrides the automatic filename/link/text preview that would otherwise show. Call from Initialize.")
  public void SetSheetSubtitle(String subtitle) {
    SheetConfigCache.setSubtitle(subtitle);
  }

  @SimpleFunction(description = "Hides the sheet's subtitle completely, even though a filename/link/text preview would normally be shown. Use this if you want a cleaner sheet with just a title and your own items. Call from Initialize.")
  public void HideSheetSubtitle() {
    SheetConfigCache.hideSubtitle();
  }

  @SimpleFunction(description = "Reverts the sheet's subtitle back to the automatic filename/link/text preview shown by default, undoing any earlier SetSheetSubtitle or HideSheetSubtitle call. You normally won't need this unless you're toggling subtitle behavior conditionally.")
  public void UseDefaultSheetSubtitle() {
    SheetConfigCache.useDefaultSubtitle();
  }

  @SimpleFunction(description = "Sets a custom header icon for the sheet, from an asset file name (e.g. \"icon.png\", if bundled as a media asset) or an absolute file path. Overrides the automatic file-type badge (PDF/IMG/ZIP/etc.) shown by default. Call from Initialize.")
  public void SetSheetIcon(String iconPath) {
    SheetConfigCache.setIcon(iconPath);
  }

  @SimpleFunction(description = "Hides the sheet's header icon completely, even though an automatic file-type badge (PDF/IMG/ZIP/etc.) would normally show.")
  public void HideSheetIcon() {
    SheetConfigCache.hideIcon();
  }

  @SimpleFunction(description = "Reverts the sheet's header icon back to the automatic file-type badge (PDF/IMG/ZIP/etc.), undoing any earlier SetSheetIcon or HideSheetIcon call.")
  public void UseDefaultSheetIcon() {
    SheetConfigCache.useDefaultIcon();
  }

  @SimpleFunction(description = "Adds your own item to the sheet, replacing the built-in \"Open in app\" / \"Save for later\" pair (call once per item you want). background = false opens the app normally; true handles it quietly and expects you to call CloseBackgroundShare() when done.")
  public void AddItem(String id, String title, boolean background) {
    SheetConfigCache.addItem(id, title, background);
  }

  @SimpleFunction(description = "Removes every item added so far (including the built-in default, once any AddItem call has run), so the next AddItem calls define a completely fresh set instead of appending to the old one. You normally don't need this in Initialize — items already start empty there — it's for redefining the item set again later, conditionally, while the Screen is still running.")
  public void ClearItems() {
    SheetConfigCache.clearItems();
  }

  // ==========================================================================
  // Designer-time customization of the built-in default "Open"/"Save" pair.
  // No blocks/Initialize needed — the App Inventor runtime applies these
  // automatically to every fresh Screen instance right after it's created
  // (before Initialize even runs), so whatever is set in the Properties
  // panel just always applies, with no risk of it being "forgotten" between
  // shares. These only affect the built-in default sheet (i.e. as long as
  // AddItem() is never called) — they do not enable "developer config" mode.
  // ==========================================================================

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN, defaultValue = "True")
  @SimpleProperty(description = "Whether the built-in default sheet's \"Open in app\" item is shown.")
  public void OpenItemEnabled(boolean enabled) {
    SheetConfigCache.setOpenItemEnabled(enabled);
  }

  @SimpleProperty
  public boolean OpenItemEnabled() {
    return SheetConfigCache.isOpenItemEnabled();
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
  @SimpleProperty(description = "Custom label for the built-in \"Open in app\" item. Leave empty to keep the default label \"Open in app\".")
  public void OpenItemTitle(String title) {
    SheetConfigCache.setOpenItemTitle(title);
  }

  @SimpleProperty
  public String OpenItemTitle() {
    return SheetConfigCache.getOpenItemTitle();
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN, defaultValue = "True")
  @SimpleProperty(description = "Whether the built-in default sheet's \"Save\" item is shown.")
  public void SaveItemEnabled(boolean enabled) {
    SheetConfigCache.setSaveItemEnabled(enabled);
  }

  @SimpleProperty
  public boolean SaveItemEnabled() {
    return SheetConfigCache.isSaveItemEnabled();
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
  @SimpleProperty(description = "Custom label for the built-in \"Save\" item. Leave empty to keep the automatic label, which adapts to what was shared (\"Save link\", \"Save file\", \"Save all\", \"Save note\").")
  public void SaveItemTitle(String title) {
    SheetConfigCache.setSaveItemTitle(title);
  }

  @SimpleProperty
  public String SaveItemTitle() {
    return SheetConfigCache.getSaveItemTitle();
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "system")
  @SimpleProperty(description = "\"system\" (default — sheet follows the device's dark/light setting automatically), \"light\", or \"dark\" to force it.")
  public void DarkMode(String mode) {
    SheetConfigCache.setDarkMode(mode);
  }

  @SimpleProperty
  public String DarkMode() {
    return SheetConfigCache.getDarkMode();
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN, defaultValue = "True")
  @SimpleProperty(description = "Whether the picker sheet shows at all. When false, an incoming share skips straight to the app with no picker — as if the app had been opened normally, just with the share data already available via GetX() blocks. If you've added items via AddItem(), the first one is used as the action; with none added, it's a plain open.")
  public void SheetShown(boolean shown) {
    SheetConfigCache.setSheetShown(shown);
  }

  @SimpleProperty
  public boolean SheetShown() {
    return SheetConfigCache.isSheetShown();
  }

  @SimpleFunction(description = "Sets the sheet's visual style: \"bottom_sheet\" (default — full-width, slides up from the bottom) or \"popup_menu\" (a compact card centered on screen). Call from Initialize.")
  public void SetSheetStyle(String style) {
    SheetConfigCache.setStyle(style);
  }

  @SimpleFunction(description = "The sheet's current visual style — \"bottom_sheet\" or \"popup_menu\".")
  public String GetSheetStyle() {
    return SheetConfigCache.getStyle();
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "default")
  @SimpleProperty(description = "Animation used when handing off from the sheet to the app itself: \"default\" (the device/theme's own app-open animation, unchanged), \"fade\", \"slide\", or \"none\" (instant). Only affects foreground opens — background-mode shares always use no animation, regardless of this.")
  public void LaunchAnimation(String animation) {
    SheetConfigCache.setLaunchAnimation(animation);
  }

  @SimpleProperty
  public String LaunchAnimation() {
    return SheetConfigCache.getLaunchAnimation();
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN, defaultValue = "True")
  @SimpleProperty(description = "Whether this app appears as a target in the system Sharesheet. Set false here for something like a \"disabled until logged in\" app — you can still flip it at runtime with EnableShareTarget()/DisableShareTarget() (e.g. right after login).")
  public void ShareTargetEnabled(boolean enabled) {
    setReceiverEnabled(enabled);
  }

  @SimpleProperty
  public boolean ShareTargetEnabled() {
    return isReceiverEnabled();
  }

  @SimpleFunction(description = "Makes this app appear as a target in the system Sharesheet. Same as setting the ShareTargetEnabled property to true, but usable at any point at runtime (e.g. right after a user logs in).")
  public void EnableShareTarget() {
    setReceiverEnabled(true);
  }

  @SimpleFunction(description = "Removes this app from the system Sharesheet until EnableShareTarget()/ShareTargetEnabled is turned back on. Useful for a user-facing \"allow sharing into this app\" toggle. Note: some launchers cache the Sharesheet list, so the change may only be visible the next time the target app's share menu is opened.")
  public void DisableShareTarget() {
    setReceiverEnabled(false);
  }

  @SimpleFunction(description = "Whether this app currently appears as a Share target.")
  public boolean IsShareTargetEnabled() {
    return isReceiverEnabled();
  }

  private boolean isReceiverEnabled() {
    try {
      PackageManager pm = context.getPackageManager();
      ComponentName component = new ComponentName(context, "com.zebdroid.smartsharex.ShareReceiverActivity");
      int state = pm.getComponentEnabledSetting(component);
      return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    } catch (Exception e) {
      return true;
    }
  }

  private void setReceiverEnabled(boolean enable) {
    try {
      PackageManager pm = context.getPackageManager();
      ComponentName component = new ComponentName(context, "com.zebdroid.smartsharex.ShareReceiverActivity");
      pm.setComponentEnabledSetting(
          component,
          enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                 : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP);
    } catch (Exception e) {
      ShareError("Could not change share target state: " + e.getMessage());
    }
  }
}
