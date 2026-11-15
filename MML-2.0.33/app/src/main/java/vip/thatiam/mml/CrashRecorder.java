package vip.thatiam.mml;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded local diagnostics for crashes that otherwise look random to the user. */
final class CrashRecorder {
  private static final String FILE = "mml-last-crash.txt";
  private static final int MAX_BYTES = 24 * 1024;
  private static final AtomicBoolean installed = new AtomicBoolean();

  private CrashRecorder() {}

  static void install(Context context) {
    if (!installed.compareAndSet(false, true)) return;
    Context app = context.getApplicationContext();
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            write(app, thread, error);
          } catch (Throwable ignored) {
            // Never replace the original failure with a diagnostic failure.
          }
          if (previous != null) previous.uncaughtException(thread, error);
          else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
          }
        });
  }

  private static void write(Context context, Thread thread, Throwable error) throws Exception {
    StringBuilder text = new StringBuilder();
    text.append("Recorded: ")
        .append(formatTime(System.currentTimeMillis()))
        .append('\n')
        .append("Thread: ")
        .append(thread == null ? "unknown" : thread.getName())
        .append('\n');
    Throwable current = error;
    for (int cause = 0; current != null && cause < 5 && text.length() < MAX_BYTES; cause++) {
      if (cause > 0) text.append("\nCaused by:\n");
      text.append(current.getClass().getName());
      if (current.getMessage() != null) text.append(": ").append(current.getMessage());
      text.append('\n');
      StackTraceElement[] stack = current.getStackTrace();
      for (int i = 0; i < stack.length && i < 80 && text.length() < MAX_BYTES; i++)
        text.append("  at ").append(stack[i]).append('\n');
      current = current.getCause();
    }
    byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) {
      byte[] clipped = new byte[MAX_BYTES];
      System.arraycopy(bytes, 0, clipped, 0, clipped.length);
      bytes = clipped;
    }
    File target = new File(context.getFilesDir(), FILE);
    File pending = new File(context.getFilesDir(), FILE + ".new");
    try (FileOutputStream output = new FileOutputStream(pending, false)) {
      output.write(bytes);
      output.flush();
      output.getFD().sync();
    }
    if (!pending.renameTo(target)) {
      try (FileOutputStream output = new FileOutputStream(target, false)) {
        output.write(bytes);
        output.flush();
      }
      pending.delete();
    }
  }

  static String report(Context context) {
    StringBuilder result = new StringBuilder();
    File crash = new File(context.getFilesDir(), FILE);
    if (crash.isFile()) {
      result.append("Last uncaught Java failure:\n");
      try (FileInputStream input = new FileInputStream(crash)) {
        byte[] bytes = new byte[(int) Math.min(MAX_BYTES, crash.length())];
        int offset = 0;
        while (offset < bytes.length) {
          int read = input.read(bytes, offset, bytes.length - offset);
          if (read < 0) break;
          offset += read;
        }
        result.append(new String(bytes, 0, offset, StandardCharsets.UTF_8).trim());
      } catch (Exception error) {
        result.append("Could not read crash record: ").append(Journal.describe(error));
      }
    } else result.append("No uncaught Java failure has been recorded by this version.");

    result.append("\n\nAndroid previous-process information:\n");
    try {
      ActivityManager manager = context.getSystemService(ActivityManager.class);
      List<ApplicationExitInfo> exits =
          manager == null
              ? java.util.Collections.emptyList()
              : manager.getHistoricalProcessExitReasons(null, 0, 1);
      if (exits.isEmpty()) result.append("No previous exit information available.");
      else {
        ApplicationExitInfo exit = exits.get(0);
        result.append("Time: ").append(formatTime(exit.getTimestamp()));
        result.append("\nReason: ")
            .append(exitReasonName(exit.getReason()))
            .append(" (")
            .append(exit.getReason())
            .append(')');
        result.append("\nStatus: ").append(exit.getStatus());
        result.append("\nImportance: ").append(exit.getImportance());
        String description = exit.getDescription();
        if (description != null && !description.trim().isEmpty())
          result.append("\nDescription: ").append(Entry.clip(description, 2048));
        try (InputStream trace = exit.getTraceInputStream()) {
          if (trace != null) {
            String snippet = readBounded(trace, 16 * 1024).trim();
            if (!snippet.isEmpty()) result.append("\n\nAndroid trace snippet:\n").append(snippet);
          }
        } catch (Exception ignored) {
          // Exit metadata is still useful when Android withholds the trace stream.
        }
      }
    } catch (RuntimeException error) {
      result.append("Unavailable: ").append(Journal.describe(error));
    }

    try {
      android.content.SharedPreferences prefs = context.getSharedPreferences("mml2", 0);
      String windowError = prefs.getString("ui_window_error", "");
      if (!windowError.isEmpty()) {
        result.append("\n\nLast prevented UI window failure:\nTime: ")
            .append(formatTime(prefs.getLong("ui_window_error_ms", 0)))
            .append("\n")
            .append(Entry.clip(windowError, 4096));
      }
    } catch (RuntimeException ignored) {
      // Do not let diagnostics become another failure path.
    }
    return result.toString();
  }

  private static String readBounded(InputStream input, int limit) throws Exception {
    byte[] buffer = new byte[Math.max(1, Math.min(limit, 4096))];
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    while (output.size() < limit) {
      int read = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
      if (read < 0) break;
      if (read == 0) continue;
      output.write(buffer, 0, read);
    }
    return new String(output.toByteArray(), StandardCharsets.UTF_8);
  }

  private static String exitReasonName(int reason) {
    switch (reason) {
      case ApplicationExitInfo.REASON_EXIT_SELF:
        return "EXIT_SELF";
      case ApplicationExitInfo.REASON_SIGNALED:
        return "SIGNALED";
      case ApplicationExitInfo.REASON_LOW_MEMORY:
        return "LOW_MEMORY";
      case ApplicationExitInfo.REASON_CRASH:
        return "CRASH";
      case ApplicationExitInfo.REASON_CRASH_NATIVE:
        return "CRASH_NATIVE";
      case ApplicationExitInfo.REASON_ANR:
        return "ANR";
      case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
        return "INITIALIZATION_FAILURE";
      case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
        return "PERMISSION_CHANGE";
      case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
        return "EXCESSIVE_RESOURCE_USAGE";
      case ApplicationExitInfo.REASON_USER_REQUESTED:
        return "USER_REQUESTED";
      case ApplicationExitInfo.REASON_USER_STOPPED:
        return "USER_STOPPED";
      case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
        return "DEPENDENCY_DIED";
      case ApplicationExitInfo.REASON_OTHER:
        return "OTHER";
      case ApplicationExitInfo.REASON_FREEZER:
        return "FREEZER";
      case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
        return "PACKAGE_STATE_CHANGE";
      case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
        return "PACKAGE_UPDATED";
      default:
        return "UNKNOWN";
    }
  }

  private static String formatTime(long millis) {
    if (millis <= 0) return "unknown";
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z"));
  }
}
