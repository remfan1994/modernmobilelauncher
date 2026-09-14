package vip.thatiam.mml;

/** In-memory bridge between the launcher Activity and services in the same MML process. */
final class MmlProcessSession {
    private static volatile String activeSessionId;

    private MmlProcessSession() {
    }

    static void activate(String sessionId) {
        activeSessionId = sessionId;
    }

    static String activeSessionId() {
        return activeSessionId;
    }
}
