package com.zebdroid.smartsharex;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges blocks-side sheet config to {@link ShareReceiverActivity}, which
 * runs before any Form/Screen exists and so can't read the developer's
 * blocks state directly. Kept deliberately tiny: a title, a subtitle (with
 * an explicit mode — see {@link SubtitleMode}), and a flat list of
 * (id, title, background) items. If the developer never calls AddItem(),
 * {@link #hasConfig()} stays false and ShareReceiverActivity falls back to
 * its built-in "Open in app" / "Save for later" pair — see
 * ShareReceiverActivity.applyDefaultConfig().
 *
 * SUBTITLE HANDLING:
 * Unlike the title (which a developer must set explicitly via
 * SetSheetTitle()), the subtitle defaults to showing the same automatic
 * filename/link/text preview as the built-in sheet — see
 * ShareReceiverActivity.defaultSubtitleFor(). This preserves useful context
 * (which file, which link) even for developers who only called AddItem()
 * and never thought about the subtitle at all. A developer who *does* want
 * different behavior can opt into it explicitly via SetSheetSubtitle() or
 * HideSheetSubtitle() (see {@link SubtitleMode}).
 */
public final class SheetConfigCache {

  private SheetConfigCache() {}

  public static final class ItemConfig {
    public final String id;
    public final String title;
    public final boolean background; // false = opens the app normally, true = quiet/background mode

    public ItemConfig(String id, String title, boolean background) {
      this.id = id;
      this.title = title;
      this.background = background;
    }
  }

  /**
   * How the sheet's subtitle should be resolved by ShareReceiverActivity:
   *  - AUTO:   show the same automatic filename/link/text preview the
   *            built-in default sheet would show (the default, so existing
   *            developer configs keep their context info without any
   *            changes on their part).
   *  - CUSTOM: show the developer-supplied text from SetSheetSubtitle().
   *  - HIDDEN: show no subtitle at all, even though a preview would
   *            normally be available — an explicit opt-out.
   */
  public enum SubtitleMode { AUTO, CUSTOM, HIDDEN }

  /**
   * How the sheet's header icon should be resolved by ShareReceiverActivity:
   *  - AUTO:   show the automatic file-type badge (PDF/IMG/ZIP/etc. — see
   *            FileTypeIcon) built from the current share's mime type.
   *  - CUSTOM: show the developer-supplied icon from SetSheetIcon().
   *  - HIDDEN: show no header icon at all.
   */
  public enum IconMode { AUTO, CUSTOM, HIDDEN }

  private static volatile String title = "";
  private static volatile String subtitle = "";
  private static volatile SubtitleMode subtitleMode = SubtitleMode.AUTO;
  private static volatile String iconPath = "";
  private static volatile IconMode iconMode = IconMode.AUTO;
  private static final List<ItemConfig> items = new ArrayList<>();
  private static volatile boolean configured = false;

  // Whether ShareReceiverActivity should show a sheet at all. Default true.
  // When false, the FIRST item added via AddItem (or, if none, a plain
  // "direct receive" with no sheet) is used immediately — the share still
  // reaches the app exactly as before, just without asking the user to
  // pick anything. Independent of `configured`/hasConfig(): a developer who
  // only calls SetSheetShown(false) and never AddItem() still gets a
  // no-sheet passthrough, not the built-in two-item default sheet.
  private static volatile boolean sheetShown = true;

  // Visual style ShareReceiverActivity renders the sheet in. Default
  // "bottom_sheet" (slides up, full width). "popup_menu" is a compact
  // centered card instead — see NativeBottomSheet.setStyle().
  private static volatile String style = "bottom_sheet";

  // Transition animation used when handing off from ShareReceiverActivity
  // to the host app's launcher Activity, for FOREGROUND opens only —
  // background-mode handoffs always force no animation regardless of this
  // (see the "background mode" javadoc in ShareReceiverActivity for why: it
  // exists purely to minimize how long anything is visible, so it can't be
  // a style choice). "default" = don't touch it, Android/the device's own
  // activity-transition animation plays (the same as opening the app any
  // other way). "fade" / "slide" use Android's built-in system animation
  // resources — no bundled assets needed. "none" = instant, no animation.
  private static volatile String launchAnimation = "default";

  // "system" (default, follows device dark/light) | "light" | "dark".
  // Kept as a String (not a nullable Boolean) so it's directly usable as a
  // @DesignerProperty in the Properties panel — a plain checkbox can't
  // represent "follow system", which is the setting most apps actually want.
  private static volatile String darkMode = "system";

  // Designer-level customization of the built-in default sheet's two items
  // (Open/Save). Deliberately kept SEPARATE from `configured`/hasConfig() —
  // setting these must NOT switch ShareReceiverActivity onto the
  // applyDeveloperConfig() (custom AddItem list) path; it should keep using
  // applyDefaultConfig()'s smart per-content-type auto-labeling, just with
  // these as overrides/toggles. Empty title string = keep the automatic
  // label (e.g. "Save link"/"Save file", adapted per content type).
  private static volatile boolean openItemEnabled = true;
  private static volatile String openItemTitle = "";
  private static volatile boolean saveItemEnabled = true;
  private static volatile String saveItemTitle = "";

  public static synchronized void setOpenItemEnabled(boolean enabled) { openItemEnabled = enabled; }
  public static synchronized boolean isOpenItemEnabled() { return openItemEnabled; }
  public static synchronized void setOpenItemTitle(String t) { openItemTitle = t == null ? "" : t; }
  public static synchronized String getOpenItemTitle() { return openItemTitle; }
  public static synchronized void setSaveItemEnabled(boolean enabled) { saveItemEnabled = enabled; }
  public static synchronized boolean isSaveItemEnabled() { return saveItemEnabled; }
  public static synchronized void setSaveItemTitle(String t) { saveItemTitle = t == null ? "" : t; }
  public static synchronized String getSaveItemTitle() { return saveItemTitle; }

  public static synchronized void setTitle(String t) {
    title = t == null ? "" : t;
    configured = true;
  }

  /** Custom subtitle text, overriding the automatic filename/link/text preview. */
  public static synchronized void setSubtitle(String s) {
    subtitle = s == null ? "" : s;
    subtitleMode = SubtitleMode.CUSTOM;
    configured = true;
  }

  /** Explicitly hides the subtitle, even though a preview would normally show. */
  public static synchronized void hideSubtitle() {
    subtitle = "";
    subtitleMode = SubtitleMode.HIDDEN;
    configured = true;
  }

  /**
   * Reverts to the automatic filename/link/text preview, undoing any earlier
   * setSubtitle()/hideSubtitle() call. Does not by itself mark the sheet as
   * "configured" — calling only this, with no title/items, should not force
   * developer mode over the built-in default sheet.
   */
  public static synchronized void useDefaultSubtitle() {
    subtitle = "";
    subtitleMode = SubtitleMode.AUTO;
  }

  /** Custom header icon, given as an asset file name or absolute file path. */
  public static synchronized void setIcon(String path) {
    iconPath = path == null ? "" : path;
    iconMode = IconMode.CUSTOM;
    configured = true;
  }

  /** Explicitly hides the header icon, even though a file-type badge would normally show. */
  public static synchronized void hideIcon() {
    iconPath = "";
    iconMode = IconMode.HIDDEN;
    configured = true;
  }

  /** Reverts to the automatic file-type badge, undoing setIcon()/hideIcon(). */
  public static synchronized void useDefaultIcon() {
    iconPath = "";
    iconMode = IconMode.AUTO;
  }

  public static synchronized void setSheetShown(boolean shown) {
    sheetShown = shown;
  }

  public static synchronized boolean isSheetShown() {
    return sheetShown;
  }

  public static synchronized void setStyle(String s) {
    style = "popup_menu".equals(s) ? "popup_menu" : "bottom_sheet";
  }

  public static synchronized String getStyle() {
    return style;
  }

  public static synchronized void setLaunchAnimation(String a) {
    launchAnimation = ("fade".equals(a) || "slide".equals(a) || "none".equals(a)) ? a : "default";
  }

  public static synchronized String getLaunchAnimation() {
    return launchAnimation;
  }

  public static synchronized void addItem(String id, String label, boolean background) {
    items.add(new ItemConfig(id, label, background));
    configured = true;
  }

  /**
   * Wipes the item list back to empty WITHOUT touching title/subtitle/icon/
   * darkMode/sheetShown/style — this is the "set items" primitive: call it,
   * then AddItem() as many times as needed, to fully replace whatever set
   * of items existed before (built-in default, or an earlier AddItem batch
   * from this same running session) instead of appending onto it. Not
   * needed on a normal cold Initialize (items already start empty per
   * Screen instance — see reset()); it's for a developer who wants to
   * redefine the whole item set again LATER, conditionally, without
   * restarting the Screen.
   */
  public static synchronized void clearItems() {
    items.clear();
    configured = true;
  }

  public static synchronized void setDarkMode(String mode) {
    darkMode = ("light".equals(mode) || "dark".equals(mode)) ? mode : "system";
  }

  /**
   * Clears title/items/theme-override back to defaults. Must be called once
   * per fresh Screen1 instance (from SmartShareX's constructor) — otherwise
   * every AddItem()/SetSheetTitle() call made from Initialize keeps
   * APPENDING on top of whatever a previous Screen1 instance already
   * configured in this same process (e.g. after a rotation, or the Sharesheet
   * relaunching Screen1 while the process is still warm), so the sheet ends
   * up showing duplicated items over repeated shares.
   */
  public static synchronized void reset() {
    title = "";
    subtitle = "";
    subtitleMode = SubtitleMode.AUTO;
    iconPath = "";
    iconMode = IconMode.AUTO;
    items.clear();
    configured = false;
    darkMode = "system";
    sheetShown = true;
    style = "bottom_sheet";
    launchAnimation = "default";
    openItemEnabled = true;
    openItemTitle = "";
    saveItemEnabled = true;
    saveItemTitle = "";
  }

  /** True once the developer has called AddItem/SetSheetTitle at least once this process. */
  public static synchronized boolean hasConfig() {
    return configured;
  }

  public static synchronized String getTitle() {
    return title;
  }

  public static synchronized String getSubtitle() {
    return subtitle;
  }

  public static synchronized SubtitleMode getSubtitleMode() {
    return subtitleMode;
  }

  public static synchronized String getIconPath() {
    return iconPath;
  }

  public static synchronized IconMode getIconMode() {
    return iconMode;
  }

  public static synchronized List<ItemConfig> getItems() {
    return new ArrayList<>(items);
  }

  /** "system" (default), "light", or "dark". */
  public static synchronized String getDarkMode() {
    return darkMode;
  }
}
