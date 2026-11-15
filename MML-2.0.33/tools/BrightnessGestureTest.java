package vip.thatiam.mml;

/** Host behavior checks for the production brightness gesture state machine. */
public final class BrightnessGestureTest {
  private static int checks;

  public static void main(String[] args) {
    // Exact user examples, at realistic widths and starting levels.
    percent(40, 210, 1000, 70, "21 percent width adds 30 points");
    percent(20, 350, 1000, 70, "35 percent width adds 50 points");
    percent(90, -350, 1000, 40, "Left drag subtracts 50 points");
    percent(50, 70, 1000, 60, "7 percent width adds 10 points");
    percent(50, -70, 1000, 40, "7 percent width subtracts 10 points");
    percent(1, 700, 1000, 100, "70 percent width reaches upper bound");
    percent(100, -700, 1000, 1, "70 percent width reaches lower bound");
    percent(50, 756, 1080, 100, "1080-pixel screen upper bound");
    percent(50, -756, 1080, 1, "1080-pixel screen lower bound");
    percent(50, 0, 1000, 50, "No movement preserves brightness");
    percent(0, 0, 1000, 1, "Starting brightness minimum");
    percent(140, 0, 1000, 100, "Starting brightness maximum");
    percent(50, Float.MAX_VALUE, 1000, 100, "Huge finite positive displacement saturates");
    percent(50, -Float.MAX_VALUE, 1000, 1, "Huge finite negative displacement saturates");
    percent(50, Float.NaN, 1000, 50, "NaN preserves clamped starting brightness");
    percent(50, Float.POSITIVE_INFINITY, 1000, 50, "Infinite position is rejected");
    percent(50, 500, 0, 50, "Unmeasured view cannot divide by zero");
    percent(50, 500, -1, 50, "Invalid width is rejected");

    BrightnessGesture gesture = new BrightnessGesture(10);
    gesture.begin(100, 100, 1000, 40);
    equal(BrightnessGesture.PENDING, gesture.state(), "Finger down starts pending");
    equal(BrightnessGesture.PENDING, gesture.move(108, 101), "Small movement remains a tap");
    equal(40, gesture.percent(), "Unclaimed movement cannot change brightness");
    equal(BrightnessGesture.HORIZONTAL, gesture.move(310, 104), "Intentional right drag claims brightness");
    equal(70, gesture.percent(), "Claim uses full displacement from finger down");
    gesture.move(310, 104);
    equal(70, gesture.percent(), "Repeated move does not compound the delta");
    gesture.move(100, 104);
    equal(40, gesture.percent(), "Reversing to start restores starting brightness");
    gesture.move(30, 900);
    equal(BrightnessGesture.HORIZONTAL, gesture.state(), "Horizontal lock survives later vertical drift");
    equal(30, gesture.percent(), "Only horizontal displacement changes the locked gesture");
    gesture.cancel();
    equal(BrightnessGesture.CANCELLED, gesture.state(), "Cancellation locks gesture out");
    gesture.move(800, 100);
    equal(BrightnessGesture.CANCELLED, gesture.state(), "Cancelled gesture cannot restart");
    equal(30, gesture.percent(), "Cancelled gesture cannot change brightness");
    gesture.end();
    equal(BrightnessGesture.IDLE, gesture.state(), "Finger release ends gesture");

    gesture.begin(100, 100, 1000, 50);
    equal(BrightnessGesture.VERTICAL, gesture.move(105, 120), "Vertical scroll wins before brightness");
    equal(BrightnessGesture.VERTICAL, gesture.move(800, 120), "Vertical scrolling cannot turn into brightness");
    equal(50, gesture.percent(), "Vertical scrolling leaves brightness unchanged");
    gesture.end();
    gesture.begin(100, 100, 1000, 50);
    equal(BrightnessGesture.VERTICAL, gesture.move(120, 120), "Ambiguous diagonal chooses scrolling");
    equal(50, gesture.percent(), "Ambiguous diagonal does not dim screen");

    gesture.begin(100, 100, 0, 50);
    equal(BrightnessGesture.CANCELLED, gesture.state(), "Zero-width view cannot begin a drag");
    gesture.begin(Float.NaN, 100, 1000, 50);
    equal(BrightnessGesture.CANCELLED, gesture.state(), "Invalid down coordinate cancels");
    gesture.begin(100, 100, 1000, 50);
    equal(BrightnessGesture.CANCELLED, gesture.move(Float.NaN, 100), "Invalid move coordinate cancels");

    // Repeated complete gestures must not leave stale brightness or direction state.
    for (int i = 0; i < 10000; i++) {
      gesture.begin(100, 100, 1000, 40);
      equal(BrightnessGesture.HORIZONTAL, gesture.move(310, 104), "Repeated horizontal state");
      equal(70, gesture.percent(), "Repeated gesture starts from new baseline");
      gesture.end();
      gesture.begin(100, 100, 1000, 40);
      equal(BrightnessGesture.VERTICAL, gesture.move(101, 120), "Repeated vertical state");
      equal(40, gesture.percent(), "Previous gesture does not leak brightness");
      gesture.end();
    }
    System.out.println("BrightnessGestureTest: " + checks + " checks passed");
  }

  private static void percent(int start, float displacement, int width, int expected, String label) {
    equal(expected, BrightnessGesture.percentForDrag(start, displacement, width), label);
  }

  private static void equal(int expected, int actual, String label) {
    checks++;
    if (actual != expected)
      throw new AssertionError(label + ": expected " + expected + ", got " + actual);
  }
}
