package com.zebdroid.smartsharex;

import java.util.ArrayList;
import java.util.List;

/**
 * In-process bridge between {@link ShareReceiverActivity} (a plain Activity,
 * NOT an App Inventor Form — it cannot fire blocks events) and the
 * {@link SmartShareX} extension component, which is always attached to a
 * Form and is the only thing that CAN fire events.
 *
 * SINGLE, PREDICTABLE DELIVERY PATH:
 * ShareReceiverActivity always calls {@link #publish} once it knows what to
 * deliver, and that's it — the payload is simply parked here as "pending".
 * SmartShareX only ever picks it up from its own onResume()/onNewIntent(),
 * i.e. exactly when its Screen is actually about to become visible again.
 *
 * A previous version also had an immediate "live listener" delivery path
 * for when a SmartShareX instance was already registered. That looked like
 * a nice optimization but was the actual source of a real bug: being
 * "registered" only means the component object exists (its Screen hasn't
 * been destroyed) — it says NOTHING about whether that Screen is currently
 * the foreground/resumed Activity. Since ShareReceiverActivity always
 * forwards to the host app's launcher Activity as a separate step, that
 * live path fired ShareReceived while the Screen was still paused/off
 * screen. Blocks ran invisibly, the payload was already consumed, and
 * nothing re-fired once the app actually became visible — from the
 * developer's side it looked like the event "wasn't firing" when it had
 * really just fired at the wrong moment. Always queuing as pending and only
 * consuming it from onResume()/onNewIntent() removes that whole class of
 * timing bug, at the cost of a few milliseconds of latency nobody notices.
 */
public final class ShareDataHolder {

  private ShareDataHolder() {}

  public static final class Payload {
    public final String action;              // ACTION_SEND or ACTION_SEND_MULTIPLE
    public final String mimeType;
    public final String text;
    public final String htmlText;
    public final String subject;
    public final String url;                 // generically extracted, "" if none
    public final List<SharedFileInfo> files;  // empty list, never null
    public final String contentType;         // "text" | "single_file" | "multiple_files"
    public final String actionId;            // id of the sheet item the user tapped, "" if none
    public final boolean background;         // true = resolved via a "background" mode item

    public Payload(String action, String mimeType, String text, String htmlText,
                    String subject, String url, List<SharedFileInfo> files, String contentType,
                    String actionId, boolean background) {
      this.action = action;
      this.mimeType = mimeType;
      this.text = text;
      this.htmlText = htmlText;
      this.subject = subject;
      this.url = url;
      this.files = files == null ? new ArrayList<SharedFileInfo>() : files;
      this.contentType = contentType;
      this.actionId = actionId == null ? "" : actionId;
      this.background = background;
    }

    /** Returns a copy with actionId/background filled in once the user's sheet choice is known. */
    public Payload withAction(String actionId, boolean background) {
      return new Payload(action, mimeType, text, htmlText, subject, url, files, contentType,
          actionId, background);
    }
  }

  private static volatile Payload pending;

  /** Called by ShareReceiverActivity once it has resolved what to deliver. */
  public static synchronized void publish(Payload payload) {
    pending = payload;
  }

  /** Called by SmartShareX on resume/new-intent to pick up the latest share, if any. */
  public static synchronized Payload consumePending() {
    Payload p = pending;
    pending = null;
    return p;
  }

  public static synchronized boolean hasPending() {
    return pending != null;
  }
}
