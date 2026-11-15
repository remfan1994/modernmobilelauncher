package vip.thatiam.mml;

/** Pure gesture policy, independent of Android views and stored preferences. */
final class BrightnessGesture {
  static final int IDLE = 0, PENDING = 1, HORIZONTAL = 2, VERTICAL = 3, CANCELLED = 4;
  private final float slop;
  private float startX, startY;
  private int width, startPercent, percent, state = IDLE;

  BrightnessGesture(float touchSlop) {
    slop = Float.isFinite(touchSlop) ? Math.max(1, touchSlop) : 1;
  }

  void begin(float x, float y, int screenWidth, int startingPercent) {
    startX = x;
    startY = y;
    width = screenWidth;
    startPercent = clamp(startingPercent);
    percent = startPercent;
    state = width > 0 && Float.isFinite(x) && Float.isFinite(y) ? PENDING : CANCELLED;
  }

  int move(float x, float y) {
    if (state == IDLE || state == VERTICAL || state == CANCELLED) return state;
    if (!Float.isFinite(x) || !Float.isFinite(y)) {
      cancel();
      return state;
    }
    float dx = x - startX;
    float dy = y - startY;
    if (!Float.isFinite(dx) || !Float.isFinite(dy)) {
      cancel();
      return state;
    }
    if (state == PENDING) {
      float horizontal = Math.abs(dx), vertical = Math.abs(dy);
      if (horizontal > slop && horizontal > 1.5f * vertical) state = HORIZONTAL;
      else if (vertical > slop) state = VERTICAL;
    }
    if (state == HORIZONTAL) percent = percentForDrag(startPercent, dx, width);
    return state;
  }

  int state() {
    return state;
  }

  int percent() {
    return percent;
  }

  void cancel() {
    state = CANCELLED;
  }

  void end() {
    state = IDLE;
  }

  static int percentForDrag(int startingPercent, float displacement, int screenWidth) {
    int start = clamp(startingPercent);
    if (screenWidth <= 0 || !Float.isFinite(displacement)) return start;
    double delta = 100.0 * displacement / (0.70 * screenWidth);
    // Clamp before converting to int so extremely large coordinates cannot wrap.
    if (delta >= 100) return 100;
    if (delta <= -100) return 1;
    return clamp(start + (int) Math.round(delta));
  }

  private static int clamp(int percent) {
    return Math.max(1, Math.min(100, percent));
  }
}
