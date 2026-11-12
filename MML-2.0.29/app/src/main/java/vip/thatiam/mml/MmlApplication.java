package vip.thatiam.mml;

import android.app.Application;

/** Installs bounded crash diagnostics before any Activity or Service starts. */
public final class MmlApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    CrashRecorder.install(this);
  }
}
