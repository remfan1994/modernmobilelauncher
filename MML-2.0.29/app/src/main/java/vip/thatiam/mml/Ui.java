package vip.thatiam.mml;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** Fixed two-gray palette, native text shaping, and minimum 48 dp controls. */
final class Ui {
  static final int DARK_GRAY = 0xff2b2b2b, LIGHT_GRAY = 0xffaaaaaa;
  private static final int[][] ACTIVE_STATES = {
    {android.R.attr.state_enabled, android.R.attr.state_pressed},
    {android.R.attr.state_enabled, android.R.attr.state_focused},
    {android.R.attr.state_enabled, android.R.attr.state_selected},
    {android.R.attr.state_enabled, android.R.attr.state_activated}
  };
  final Activity activity;
  final int background = DARK_GRAY;
  final int foreground = LIGHT_GRAY;
  final int inset = DARK_GRAY;
  final int insetForeground = LIGHT_GRAY;
  final int divider = LIGHT_GRAY;
  final int selectedBackground = LIGHT_GRAY;
  final int selectedForeground = DARK_GRAY;
  final float size;

  Ui(Activity activity) {
    this.activity = activity;
    size = activity.getSharedPreferences("mml2", 0).getInt("font_size", 24);
  }

  int dp(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    v.setBackgroundColor(background);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  LinearLayout listRow() {
    LinearLayout row = column();
    bindListRow(row, false);
    return row;
  }

  void bindListRow(LinearLayout row, boolean outlined) {
    bindListRow(row, outlined, false);
  }

  void bindListRow(LinearLayout row, boolean outlined, boolean actionBorder) {
    row.setBackground(actionBorder ? actionRowBackground() : rowBackground(outlined));
    // Actionable rows stay normal video. Their persistent cue is a border, not a
    // light background, so MML remains compatible with display color inversion.
    ColorStateList colors = actionBorder ? ColorStateList.valueOf(foreground) : textColors(foreground);
    for (int i = 0; i < row.getChildCount(); i++) {
      View child = row.getChildAt(i);
      if (child instanceof TextView) {
        TextView text = (TextView) child;
        text.setDuplicateParentStateEnabled(true);
        text.setTextColor(colors);
      }
    }
  }

  void styleList(ListView list) {
    list.setBackgroundColor(background);
    list.setCacheColorHint(background);
    list.setDivider(new ColorDrawable(divider));
    list.setDividerHeight(dp(2));
    // The row owns reverse-video state. A ListView selector would cover it.
    list.setSelector(new ColorDrawable(0x00000000));
  }

  TextView text(String value, float sp) {
    TextView v = new TextView(activity);
    v.setText(value);
    v.setTextSize(sp);
    v.setTextColor(foreground);
    v.setHighlightColor(DARK_GRAY);
    v.setPadding(dp(12), dp(8), dp(12), dp(8));
    return v;
  }


  void reverse(TextView view) {
    view.setTextColor(DARK_GRAY);
    view.setBackgroundColor(LIGHT_GRAY);
  }

  TextView heading(String value) {
    TextView v = text(value, size);
    bold(v);
    return v;
  }

  void styleMeta(TextView view) {
    styleMeta(view, false);
  }

  void styleMeta(TextView view, boolean actionBorder) {
    view.setDuplicateParentStateEnabled(true);
    view.setTextColor(actionBorder ? ColorStateList.valueOf(foreground) : textColors(foreground));
    view.setBackgroundColor(0x00000000);
  }

  void selectable(TextView view) {
    view.setTextIsSelectable(true);
    tintSelectionHandles(view, foreground);
  }

  private void tintSelectionHandles(TextView view, int color) {
    if (view.getTextSelectHandle() != null) view.getTextSelectHandle().mutate().setTint(color);
    if (view.getTextSelectHandleLeft() != null)
      view.getTextSelectHandleLeft().mutate().setTint(color);
    if (view.getTextSelectHandleRight() != null)
      view.getTextSelectHandleRight().mutate().setTint(color);
  }

  Button button(String label, Runnable action) {
    Button b = new Button(activity);
    b.setText(label);
    b.setTextSize(size);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setAllCaps(false);
    b.setMinHeight(dp(48));
    b.setMinimumWidth(0);
    b.setMinWidth(0);
    b.setTextColor(reverseTextColors());
    StateListDrawable states = new StateListDrawable();
    states.addState(new int[] {-android.R.attr.state_enabled}, fill(DARK_GRAY, LIGHT_GRAY, true));
    for (int[] state : ACTIVE_STATES)
      states.addState(state, fill(DARK_GRAY, LIGHT_GRAY, true));
    states.addState(new int[] {}, fill(LIGHT_GRAY, DARK_GRAY, true));
    b.setBackground(states);
    b.setPadding(dp(8), dp(6), dp(8), dp(6));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  EditText input(String hint) {
    EditText e = new EditText(activity);
    e.setTextSize(size);
    e.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    e.setTextColor(DARK_GRAY);
    e.setHintTextColor(DARK_GRAY);
    e.setHint(hint);
    e.setBackgroundTintList(null);
    StateListDrawable field = new StateListDrawable();
    field.addState(new int[] {android.R.attr.state_focused}, fill(LIGHT_GRAY, DARK_GRAY, true));
    field.addState(new int[] {}, fill(LIGHT_GRAY, DARK_GRAY, true));
    e.setBackground(field);
    GradientDrawable cursor = new GradientDrawable();
    cursor.setColor(DARK_GRAY);
    cursor.setSize(dp(2), dp((int) size + 4));
    e.setTextCursorDrawable(cursor);
    e.setHighlightColor(LIGHT_GRAY);
    tintSelectionHandles(e, DARK_GRAY);
    e.setPadding(dp(10), dp(8), dp(10), dp(8));
    e.setMinHeight(dp(48));
    e.setSaveEnabled(false);
    return e;
  }

  GradientDrawable outline() {
    return fill(background, divider, true);
  }

  StateListDrawable rowBackground(boolean outlined) {
    StateListDrawable states = new StateListDrawable();
    for (int[] state : ACTIVE_STATES)
      states.addState(state, fill(selectedBackground, selectedForeground, outlined));
    states.addState(new int[] {}, fill(background, divider, outlined));
    return states;
  }

  private StateListDrawable actionRowBackground() {
    StateListDrawable states = new StateListDrawable();
    for (int[] state : ACTIVE_STATES)
      states.addState(state, checker(background, divider, 3));
    states.addState(new int[] {}, checker(background, divider, 2));
    return states;
  }

  private Drawable checker(int fillColor, int borderColor, int thicknessDp) {
    return new CheckeredBorderDrawable(
        fillColor, borderColor, dp(6), Math.max(1, dp(thicknessDp)));
  }

  /** Dark fill with a two-gray alternating border, matching MML's inversion-safe palette. */
  private static final class CheckeredBorderDrawable extends Drawable {
    private final int fillColor, borderColor, cell, thickness;
    private final Paint paint = new Paint();

    CheckeredBorderDrawable(int fillColor, int borderColor, int cell, int thickness) {
      this.fillColor = fillColor;
      this.borderColor = borderColor;
      this.cell = Math.max(2, cell);
      this.thickness = Math.max(1, thickness);
      paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
      android.graphics.Rect bounds = getBounds();
      paint.setColor(fillColor);
      canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
      paint.setColor(borderColor);
      int index = 0;
      for (int x = bounds.left; x < bounds.right; x += cell, index++) {
        int right = Math.min(bounds.right, x + cell);
        if ((index & 1) == 0)
          canvas.drawRect(x, bounds.top, right, Math.min(bounds.bottom, bounds.top + thickness), paint);
        if ((index & 1) != 0)
          canvas.drawRect(x, Math.max(bounds.top, bounds.bottom - thickness), right, bounds.bottom, paint);
      }
      index = 0;
      for (int y = bounds.top; y < bounds.bottom; y += cell, index++) {
        int bottom = Math.min(bounds.bottom, y + cell);
        if ((index & 1) != 0)
          canvas.drawRect(bounds.left, y, Math.min(bounds.right, bounds.left + thickness), bottom, paint);
        if ((index & 1) == 0)
          canvas.drawRect(Math.max(bounds.left, bounds.right - thickness), y, bounds.right, bottom, paint);
      }
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
  }

  private GradientDrawable fill(int color, int border, boolean outlined) {
    return fill(color, border, outlined ? 1 : 0);
  }

  private GradientDrawable fill(int color, int border, int strokeDp) {
    GradientDrawable result = new GradientDrawable();
    result.setColor(color);
    if (strokeDp > 0) result.setStroke(Math.max(1, dp(strokeDp)), border);
    return result;
  }

  ColorStateList textColors(int ordinary) {
    int[][] states = new int[ACTIVE_STATES.length + 1][];
    int[] colors = new int[states.length];
    for (int i = 0; i < ACTIVE_STATES.length; i++) {
      states[i] = ACTIVE_STATES[i];
      colors[i] = selectedForeground;
    }
    states[states.length - 1] = new int[] {};
    colors[colors.length - 1] = ordinary;
    return new ColorStateList(states, colors);
  }

  private ColorStateList reverseTextColors() {
    int[][] states = new int[ACTIVE_STATES.length + 2][];
    int[] colors = new int[states.length];
    states[0] = new int[] {-android.R.attr.state_enabled};
    colors[0] = LIGHT_GRAY;
    for (int i = 0; i < ACTIVE_STATES.length; i++) {
      states[i + 1] = ACTIVE_STATES[i];
      colors[i + 1] = LIGHT_GRAY;
    }
    states[states.length - 1] = new int[] {};
    colors[colors.length - 1] = DARK_GRAY;
    return new ColorStateList(states, colors);
  }

  void equal(LinearLayout row, View v) {
    row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
  }

  void bold(TextView v) {
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
  }
}
