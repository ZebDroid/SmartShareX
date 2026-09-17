package com.zebdroid.smartsharex;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A dependency-free bottom sheet: a {@link Dialog} styled edge-to-edge and
 * transparent, holding a hand-built view hierarchy. Deliberately avoids
 * com.google.android.material.bottomsheet.BottomSheetDialog so the
 * extension never has to bundle (and risk version-clashing) the Material
 * Components library inside the host app's APK.
 *
 * Drag-to-dismiss is implemented manually via touch-slop tracking on the
 * drag handle + content container, rather than BottomSheetBehavior.
 */
public class NativeBottomSheet {

  /** Fired when the user taps an item. */
  public interface OnItemSelectedListener {
    void onItemSelected(String id, String title, boolean openApp);
  }

  /** Fired when the sheet is dismissed for any reason (item pick, swipe, backdrop tap, back press). */
  public interface OnDismissListener {
    void onDismiss();
  }

  private static final class Item {
    final String id;
    final String title;
    final Drawable icon; // nullable
    final boolean openApp;

    Item(String id, String title, Drawable icon, boolean openApp) {
      this.id = id;
      this.title = title;
      this.icon = icon;
      this.openApp = openApp;
    }
  }

  private final Context context;
  private final Dialog dialog;
  private final List<Item> items = new ArrayList<>();

  private FrameLayout root;
  private LinearLayout sheetContainer;
  private LinearLayout itemsContainer;
  private TextView titleView;
  private TextView subtitleView;
  private ImageView iconView;

  private String title = "";
  private String subtitle = "";
  private Drawable headerIcon;
  private int heightDp = -1; // -1 = wrap content
  private boolean cancelable = true;
  private float dimAmount = 0.4f;
  private boolean darkMode = false;
  private String style = "bottom_sheet"; // "bottom_sheet" | "popup_menu"

  private OnItemSelectedListener itemSelectedListener;
  private OnDismissListener dismissListener;

  private float dragStartY;
  private float sheetStartTranslation;

  // True while the sheet is closing because an item was tapped (as opposed
  // to a swipe/backdrop/back-press dismissal). Lets us skip firing
  // onDismiss for that case — set in buildItemRow()'s click handler.
  private boolean closingViaSelection = false;

  public NativeBottomSheet(Context context) {
    this.context = context;
    this.dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      // Gravity/size depend on `style`, which may still change via
      // setStyle() after construction (it's set right after `new
      // NativeBottomSheet(...)`, before show()) — applied for real in
      // applyWindowLayoutForStyle(), called from buildViews().
      window.setDimAmount(dimAmount);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
      @Override
      public void onDismiss(android.content.DialogInterface d) {
        // An item tap always closes the sheet too (see buildItemRow), but
        // that is NOT a "the user backed out" dismissal — only actually
        // swiping down / tapping the backdrop / pressing back should fire
        // OnDismiss. Without this guard, every single item pick was
        // immediately followed by a spurious OnDismiss ~180ms later.
        if (closingViaSelection) {
          closingViaSelection = false;
          return;
        }
        if (dismissListener != null) {
          dismissListener.onDismiss();
        }
      }
    });
  }

  // ---- configuration -----------------------------------------------------

  public void setTitle(String title) {
    this.title = title == null ? "" : title;
    if (titleView != null) {
      titleView.setText(this.title);
      titleView.setVisibility(this.title.isEmpty() ? View.GONE : View.VISIBLE);
    }
  }

  public void setSubtitle(String subtitle) {
    this.subtitle = subtitle == null ? "" : subtitle;
    if (subtitleView != null) {
      subtitleView.setText(this.subtitle);
      subtitleView.setVisibility(this.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    }
  }

  public void setHeaderIcon(Drawable icon) {
    this.headerIcon = icon;
    if (iconView != null) {
      iconView.setImageDrawable(icon);
      iconView.setVisibility(icon == null ? View.GONE : View.VISIBLE);
    }
  }

  public void setHeightDp(int dp) {
    this.heightDp = dp;
    if (sheetContainer != null) {
      ViewGroup.LayoutParams lp = sheetContainer.getLayoutParams();
      lp.height = dp > 0 ? dpToPx(dp) : ViewGroup.LayoutParams.WRAP_CONTENT;
      sheetContainer.setLayoutParams(lp);
    }
  }

  public void setCancelable(boolean cancelable) {
    this.cancelable = cancelable;
    dialog.setCancelable(cancelable);
    dialog.setCanceledOnTouchOutside(cancelable);
  }

  public void setDimAmount(float amount) {
    this.dimAmount = amount;
    Window window = dialog.getWindow();
    if (window != null) {
      window.setDimAmount(amount);
    }
  }

  public void setDarkMode(boolean dark) {
    this.darkMode = dark;
    if (sheetContainer != null) {
      applyTheme();
    }
  }

  /**
   * "bottom_sheet" (default): full-width, slides up from the bottom, drag
   * handle + drag-to-dismiss. "popup_menu": a compact centered card, no
   * drag handle. Must be called BEFORE show() — the style is baked into
   * the view hierarchy the first time it's built and is not switchable on
   * an already-showing/-built sheet.
   */
  public void setStyle(String style) {
    this.style = "popup_menu".equals(style) ? "popup_menu" : "bottom_sheet";
  }

  public void addItem(String id, String title, Drawable icon, boolean openApp) {
    items.add(new Item(id, title, icon, openApp));
    if (itemsContainer != null) {
      itemsContainer.addView(buildItemRow(items.get(items.size() - 1)));
    }
  }

  public void removeItem(String id) {
    for (int i = items.size() - 1; i >= 0; i--) {
      if (items.get(i).id.equals(id)) {
        items.remove(i);
      }
    }
    rebuildItems();
  }

  public void clearItems() {
    items.clear();
    if (itemsContainer != null) {
      itemsContainer.removeAllViews();
    }
  }

  public void setOnItemSelectedListener(OnItemSelectedListener listener) {
    this.itemSelectedListener = listener;
  }

  public void setOnDismissListener(OnDismissListener listener) {
    this.dismissListener = listener;
  }

  // ---- show / dismiss -----------------------------------------------------

  public void show() {
    if (root == null) {
      buildViews();
    }
    try {
      dialog.show();
    } catch (WindowManager.BadTokenException | IllegalStateException e) {
      // Host Activity's window was already gone by the time show() actually
      // ran (e.g. a second share arrived and the Activity is mid-finish, or
      // the process is being torn down) — nothing to show, bail quietly
      // instead of crashing the host app.
      return;
    }
    if ("popup_menu".equals(style)) {
      // Centered popup: fade + scale in from 90%, no directional slide —
      // there's no screen edge for a centered card to slide in from.
      sheetContainer.setAlpha(0f);
      sheetContainer.setScaleX(0.9f);
      sheetContainer.setScaleY(0.9f);
      sheetContainer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
    } else {
      // Slide up from below the screen once the dialog window is measured.
      sheetContainer.setTranslationY(dpToPx(400));
      sheetContainer.post(new Runnable() {
        @Override
        public void run() {
          ObjectAnimator anim = ObjectAnimator.ofFloat(sheetContainer, "translationY", 0f);
          anim.setDuration(220);
          anim.start();
        }
      });
    }
  }

  public void dismiss() {
    if (sheetContainer == null || !dialog.isShowing()) {
      safeDismiss();
      return;
    }
    if ("popup_menu".equals(style)) {
      sheetContainer.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(120)
          .setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
              safeDismiss();
            }
          }).start();
      return;
    }
    ObjectAnimator anim = ObjectAnimator.ofFloat(
        sheetContainer, "translationY", sheetContainer.getHeight());
    anim.setDuration(180);
    anim.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        safeDismiss();
      }
    });
    anim.start();
  }

  /**
   * dialog.dismiss() can throw if the host Activity/window was already torn
   * down by the OS while this sheet's slide-down animation was still in
   * flight — a real, reproducible crash whenever an item tap triggers
   * Activity.finish() (see ShareReceiverActivity.resolveSelection) faster
   * than the 180ms dismiss animation completes. Swallow that specific,
   * harmless race instead of crashing the host app; there is nothing left
   * to clean up once the window is already gone.
   */
  private void safeDismiss() {
    try {
      if (dialog.isShowing()) {
        dialog.dismiss();
      }
    } catch (IllegalArgumentException | WindowManager.BadTokenException e) {
      // Window already removed by the system — no-op.
    }
  }

  public boolean isShowing() {
    return dialog.isShowing();
  }

  // ---- view construction ----------------------------------------------------

  private void buildViews() {
    boolean isPopup = "popup_menu".equals(style);
    applyWindowLayoutForStyle(isPopup);

    root = new FrameLayout(context);

    sheetContainer = new LinearLayout(context);
    sheetContainer.setOrientation(LinearLayout.VERTICAL);
    FrameLayout.LayoutParams sheetLp;
    if (isPopup) {
      sheetLp = new FrameLayout.LayoutParams(dpToPx(280), ViewGroup.LayoutParams.WRAP_CONTENT);
      sheetLp.gravity = Gravity.CENTER;
    } else {
      sheetLp = new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          heightDp > 0 ? dpToPx(heightDp) : ViewGroup.LayoutParams.WRAP_CONTENT);
      sheetLp.gravity = Gravity.BOTTOM;
    }
    sheetContainer.setLayoutParams(sheetLp);
    sheetContainer.setPadding(dpToPx(16), dpToPx(isPopup ? 16 : 8), dpToPx(16), dpToPx(isPopup ? 16 : 20));

    // Drag handle — bottom-sheet style only; a centered popup has nothing
    // to drag toward (there's no "off-screen edge" it's anchored to).
    View handle = null;
    if (!isPopup) {
      handle = new View(context);
      LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(4));
      handleLp.gravity = Gravity.CENTER_HORIZONTAL;
      handleLp.bottomMargin = dpToPx(12);
      handle.setLayoutParams(handleLp);
      GradientDrawable handleBg = new GradientDrawable();
      handleBg.setColor(Color.parseColor("#40808080"));
      handleBg.setCornerRadius(dpToPx(2));
      handle.setBackground(handleBg);
      sheetContainer.addView(handle);
    }

    // Header: icon + title/subtitle
    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(0, 0, 0, dpToPx(12));

    iconView = new ImageView(context);
    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(28), dpToPx(28));
    iconLp.rightMargin = dpToPx(10);
    iconView.setLayoutParams(iconLp);
    iconView.setVisibility(headerIcon == null ? View.GONE : View.VISIBLE);
    if (headerIcon != null) {
      iconView.setImageDrawable(headerIcon);
    }
    header.addView(iconView);

    LinearLayout textCol = new LinearLayout(context);
    textCol.setOrientation(LinearLayout.VERTICAL);

    titleView = new TextView(context);
    titleView.setTextSize(18);
    titleView.setText(title);
    titleView.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
    // Long developer titles or long shared text used as a title should
    // never be allowed to wrap into multiple lines and blow up the
    // sheet's height — a single, clean, ellipsized line is always safer.
    titleView.setMaxLines(1);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    textCol.addView(titleView);

    subtitleView = new TextView(context);
    subtitleView.setTextSize(13);
    subtitleView.setAlpha(0.7f);
    subtitleView.setText(subtitle);
    subtitleView.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    // Shared text/URLs can be arbitrarily long (a whole paragraph, or a
    // huge tracking URL) — cap at 2 lines with an ellipsis so the sheet's
    // height stays predictable no matter what was actually shared.
    subtitleView.setMaxLines(2);
    subtitleView.setEllipsize(TextUtils.TruncateAt.END);
    textCol.addView(subtitleView);

    header.addView(textCol);
    sheetContainer.addView(header);

    // Items
    itemsContainer = new LinearLayout(context);
    itemsContainer.setOrientation(LinearLayout.VERTICAL);
    sheetContainer.addView(itemsContainer);
    rebuildItems();

    // Rounded background — all corners for a centered popup, top-only for
    // a bottom sheet (bottom edge meets the screen edge, no need to round it).
    GradientDrawable bg = new GradientDrawable();
    float r = dpToPx(isPopup ? 16 : 20);
    bg.setCornerRadii(isPopup
        ? new float[]{r, r, r, r, r, r, r, r}
        : new float[]{r, r, r, r, 0, 0, 0, 0});
    sheetContainer.setBackground(bg);
    applyTheme();

    root.addView(sheetContainer);
    root.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (cancelable) {
          dismiss();
        }
      }
    });

    if (handle != null) {
      attachDragHandling(handle);
      attachDragHandling(header);
    }

    dialog.setContentView(root, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    dialog.setCancelable(cancelable);
    dialog.setCanceledOnTouchOutside(cancelable);
  }

  private void rebuildItems() {
    if (itemsContainer == null) {
      return;
    }
    itemsContainer.removeAllViews();
    for (Item item : items) {
      itemsContainer.addView(buildItemRow(item));
    }
  }

  private View buildItemRow(Item item) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dpToPx(4), dpToPx(14), dpToPx(4), dpToPx(14));
    row.setClickable(true);
    row.setFocusable(true);
    row.setContentDescription(item.title);

    if (item.icon != null) {
      ImageView icon = new ImageView(context);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
      lp.rightMargin = dpToPx(12);
      icon.setLayoutParams(lp);
      icon.setImageDrawable(item.icon);
      row.addView(icon);
    }

    TextView label = new TextView(context);
    label.setText(item.title);
    label.setTextSize(16);
    label.setMaxLines(1);
    label.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    label.setLayoutParams(labelLp);
    row.addView(label);

    RadioButton bullet = new RadioButton(context);
    bullet.setClickable(false);
    bullet.setFocusable(false);
    row.addView(bullet);

    row.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        closingViaSelection = true;
        if (itemSelectedListener != null) {
          itemSelectedListener.onItemSelected(item.id, item.title, item.openApp);
        }
        dismiss();
      }
    });

    return row;
  }

  private void applyTheme() {
    GradientDrawable bg = (GradientDrawable) sheetContainer.getBackground();
    int bgColor = darkMode ? Color.parseColor("#1E1E1E") : Color.WHITE;
    int textColor = darkMode ? Color.WHITE : Color.BLACK;
    if (bg != null) {
      bg.setColor(bgColor);
    }
    if (titleView != null) titleView.setTextColor(textColor);
    if (subtitleView != null) subtitleView.setTextColor(textColor);
    for (int i = 0; i < itemsContainer.getChildCount(); i++) {
      View child = itemsContainer.getChildAt(i);
      if (child instanceof LinearLayout) {
        LinearLayout row = (LinearLayout) child;
        for (int j = 0; j < row.getChildCount(); j++) {
          View grandchild = row.getChildAt(j);
          if (grandchild instanceof TextView) {
            ((TextView) grandchild).setTextColor(textColor);
          }
        }
      }
    }
  }

  // ---- manual drag-to-dismiss ----------------------------------------------

  @SuppressWarnings("ClickableViewAccessibility")
  private void attachDragHandling(View dragSource) {
    dragSource.setOnTouchListener(new View.OnTouchListener() {
      @Override
      public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            dragStartY = event.getRawY();
            sheetStartTranslation = sheetContainer.getTranslationY();
            return true;
          case MotionEvent.ACTION_MOVE:
            float delta = event.getRawY() - dragStartY;
            if (delta > 0) {
              sheetContainer.setTranslationY(sheetStartTranslation + delta);
            }
            return true;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            float moved = sheetContainer.getTranslationY() - sheetStartTranslation;
            if (moved > dpToPx(80)) {
              dismiss();
            } else {
              ObjectAnimator.ofFloat(sheetContainer, "translationY", 0f).setDuration(150).start();
            }
            return true;
          default:
            return false;
        }
      }
    });
  }

  private void applyWindowLayoutForStyle(boolean isPopup) {
    Window window = dialog.getWindow();
    if (window == null) return;
    if (isPopup) {
      window.setGravity(Gravity.CENTER);
      window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    } else {
      window.setGravity(Gravity.BOTTOM);
      window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
  }

  private int dpToPx(int dp) {
    float density = context.getResources().getDisplayMetrics().density;
    return Math.round(dp * density);
  }
}
