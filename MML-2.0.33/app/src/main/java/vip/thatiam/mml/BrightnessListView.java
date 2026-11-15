package vip.thatiam.mml;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;
import android.widget.ListView;

/** A log-only ListView with horizontal brightness drag and native vertical scroll. */
final class BrightnessListView extends ListView {
  interface Listener {
    int currentPercent();
    void started();
    void changed(int percent);
    void finished(int percent);
  }

  private final BrightnessGesture gesture;
  private final Listener listener;
  private int pointerId = -1, lastPercent;
  private long downTime;
  private boolean claimed, suppress, bypass;

  BrightnessListView(Context context, Listener listener) {
    super(context);
    this.listener = listener;
    gesture = new BrightnessGesture(ViewConfiguration.get(context).getScaledTouchSlop());
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      finishGesture();
      suppress = false;
      downTime = event.getDownTime();
      AccessibilityManager accessibility = getContext().getSystemService(AccessibilityManager.class);
      bypass = listener == null || (accessibility != null && accessibility.isTouchExplorationEnabled());
      if (!bypass) {
        pointerId = event.getPointerId(0);
        gesture.begin(event.getX(0), event.getY(0), getWidth(), listener.currentPercent());
        lastPercent = gesture.percent();
      }
    }
    if (bypass) return super.dispatchTouchEvent(event);

    if (action == MotionEvent.ACTION_CANCEL) {
      boolean consumed = claimed || suppress;
      if (!consumed) super.dispatchTouchEvent(event);
      finishGesture();
      suppress = false;
      return true;
    }
    if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
      if (!suppress && !claimed) cancelNativeTouch(event);
      finishGesture();
      gesture.cancel();
      suppress = true;
      return true;
    }
    if (suppress) {
      if (action == MotionEvent.ACTION_UP) {
        suppress = false;
        gesture.end();
      }
      return true;
    }
    if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
      int index = event.findPointerIndex(pointerId);
      if (index < 0) {
        if (!claimed) cancelNativeTouch(event);
        finishGesture();
        gesture.cancel();
        suppress = action != MotionEvent.ACTION_UP;
        return true;
      }
      int direction = gesture.move(event.getX(index), event.getY(index));
      if (direction == BrightnessGesture.HORIZONTAL) {
        if (!claimed) {
          cancelNativeTouch(event);
          claimed = true;
          ViewParent parent = getParent();
          if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
          listener.started();
        }
        int percent = gesture.percent();
        if (percent != lastPercent) {
          lastPercent = percent;
          listener.changed(percent);
        }
        if (action == MotionEvent.ACTION_UP) finishGesture();
        return true;
      }
      if (direction == BrightnessGesture.CANCELLED) {
        cancelNativeTouch(event);
        finishGesture();
        suppress = action != MotionEvent.ACTION_UP;
        return true;
      }
      if (action == MotionEvent.ACTION_UP) gesture.end();
    }
    return super.dispatchTouchEvent(event);
  }

  /** Stop tracking before opening details or leaving this log. */
  void cancelBrightnessGesture() {
    // A caller may cancel while still below the horizontal threshold. End the
    // native press immediately so its delayed long-press callback cannot fire.
    long now = SystemClock.uptimeMillis();
    MotionEvent cancel = MotionEvent.obtain(downTime > 0 ? downTime : now, now,
        MotionEvent.ACTION_CANCEL, 0, 0, 0);
    super.dispatchTouchEvent(cancel);
    cancel.recycle();
    finishGesture();
    gesture.cancel();
    suppress = true;
  }

  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    if (!hasWindowFocus) cancelBrightnessGesture();
    super.onWindowFocusChanged(hasWindowFocus);
  }

  @Override
  protected void onDetachedFromWindow() {
    cancelBrightnessGesture();
    super.onDetachedFromWindow();
  }

  private void cancelNativeTouch(MotionEvent event) {
    MotionEvent cancel = MotionEvent.obtain(event);
    cancel.setAction(MotionEvent.ACTION_CANCEL);
    super.dispatchTouchEvent(cancel);
    cancel.recycle();
  }

  private void finishGesture() {
    boolean wasClaimed = claimed;
    claimed = false;
    gesture.end();
    pointerId = -1;
    if (wasClaimed) {
      ViewParent parent = getParent();
      if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
      listener.finished(lastPercent);
    }
  }
}
