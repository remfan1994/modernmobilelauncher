package vip.thatiam.mml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Immutable journal input. Row IDs belong to this installation; UIDs survive plaintext export. */
public final class Entry {
  public static final int MAX_USER_ENTRY = 262144;
  private static final char[] HEX = "0123456789abcdef".toCharArray();
  public final long rowId, timeMs, capturedMs, notifiedMs;
  public final String uid, day, zone, session, kind, source, type, title, body;
  public final String stateKey, stateValue;
  public final String notificationKeyHash;

  Entry(
      long rowId,
      String uid,
      long timeMs,
      long capturedMs,
      String day,
      String zone,
      String session,
      String kind,
      String source,
      String type,
      String title,
      String body,
      String stateKey,
      String stateValue) {
    this(
        rowId,
        uid,
        timeMs,
        capturedMs,
        day,
        zone,
        session,
        kind,
        source,
        type,
        title,
        body,
        stateKey,
        stateValue,
        "",
        0);
  }

  Entry(
      long rowId,
      String uid,
      long timeMs,
      long capturedMs,
      String day,
      String zone,
      String session,
      String kind,
      String source,
      String type,
      String title,
      String body,
      String stateKey,
      String stateValue,
      String notificationKeyHash) {
    this(
        rowId,
        uid,
        timeMs,
        capturedMs,
        day,
        zone,
        session,
        kind,
        source,
        type,
        title,
        body,
        stateKey,
        stateValue,
        notificationKeyHash,
        0);
  }

  Entry(
      long rowId,
      String uid,
      long timeMs,
      long capturedMs,
      String day,
      String zone,
      String session,
      String kind,
      String source,
      String type,
      String title,
      String body,
      String stateKey,
      String stateValue,
      String notificationKeyHash,
      long notifiedMs) {
    this.rowId = rowId;
    this.uid = require(uid, 256);
    this.timeMs = timeMs;
    this.capturedMs = capturedMs;
    this.notifiedMs = Math.max(0, notifiedMs);
    this.day = LocalDate.parse(day).toString();
    this.zone = require(zone, 128);
    this.session = require(session, 256);
    this.kind = require(kind, 64);
    this.source = require(source, 512);
    this.type = require(type, 128);
    this.title = clip(title, 2048);
    this.body = clip(body, "USER".equals(kind) ? MAX_USER_ENTRY : 65536);
    this.stateKey = require(stateKey, 1024);
    this.stateValue = require(stateValue, 256);
    String keyHash = require(notificationKeyHash, 64);
    if (!keyHash.isEmpty()) {
      if (keyHash.length() != 64)
        throw new IllegalArgumentException("Invalid notification identity hash.");
      for (int i = 0; i < keyHash.length(); i++) {
        char value = keyHash.charAt(i);
        if (!(value >= '0' && value <= '9') && !(value >= 'a' && value <= 'f'))
          throw new IllegalArgumentException("Invalid notification identity hash.");
      }
    }
    this.notificationKeyHash = keyHash;
  }

  public static Entry event(String kind, String source, String type, String title, String body) {
    return at(System.currentTimeMillis(), kind, source, type, title, body);
  }

  public static Entry at(
      long timeMs, String kind, String source, String type, String title, String body) {
    ZoneId zone = ZoneId.systemDefault();
    return new Entry(
        0,
        UUID.randomUUID().toString(),
        timeMs,
        System.currentTimeMillis(),
        Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toString(),
        zone.getId(),
        "",
        kind,
        source,
        type,
        title,
        body,
        "",
        "");
  }

  /** A deliberate user-originated text log record. */
  public static Entry user(String body) {
    return event("USER", "You", "manual", "Log entry", body);
  }

  /** Text received through Android's ordinary Share action. */
  public static Entry sharedText(String body) {
    return event("USER", "Android share", "shared_text", "Shared text", body);
  }

  /** A deliberate Home-screen app launch. */
  public static Entry appLaunch(String packageName, String label) {
    String safeLabel = label == null || label.trim().isEmpty() ? packageName : label.trim();
    return event("USER", packageName, "app_launch", "App launch: " + safeLabel, "");
  }

  public Entry withIdentity(String identity) {
    return copy(identity, day, session, stateKey, stateValue);
  }

  public Entry withDay(String localDay) {
    return copy(uid, localDay, session, stateKey, stateValue);
  }

  public Entry withSession(String value) {
    return copy(uid, day, value, stateKey, stateValue);
  }

  public Entry withState(String key, String value) {
    return copy(uid, day, session, key, value);
  }

  public Entry withNotificationKeyHash(String value) {
    return new Entry(
        rowId,
        uid,
        timeMs,
        capturedMs,
        day,
        zone,
        session,
        kind,
        source,
        type,
        title,
        body,
        stateKey,
        stateValue,
        value,
        notifiedMs);
  }

  public Entry withNotifiedMs(long value) {
    return new Entry(
        rowId,
        uid,
        timeMs,
        capturedMs,
        day,
        zone,
        session,
        kind,
        source,
        type,
        title,
        body,
        stateKey,
        stateValue,
        notificationKeyHash,
        value);
  }

  private Entry copy(
      String identity, String localDay, String currentSession, String key, String value) {
    return new Entry(
        rowId,
        identity,
        timeMs,
        capturedMs,
        localDay,
        zone,
        currentSession,
        kind,
        source,
        type,
        title,
        body,
        key,
        value,
        notificationKeyHash,
        notifiedMs);
  }

  public static String hash(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      char[] result = new char[digest.length * 2];
      for (int i = 0; i < digest.length; i++) {
        int value = digest[i] & 255;
        result[i * 2] = HEX[value >>> 4];
        result[i * 2 + 1] = HEX[value & 15];
      }
      return new String(result);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String require(String value, int limit) {
    String actual = value == null ? "" : value;
    if (actual.length() > limit) throw new IllegalArgumentException("Journal metadata is too long.");
    return actual;
  }

  public static String clip(String value, int limit) {
    if (value == null) return "";
    if (value.length() <= limit) return value;
    int end = limit - 36;
    if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
    return value.substring(0, end) + "\n[Text shortened at capture limit]";
  }
}
