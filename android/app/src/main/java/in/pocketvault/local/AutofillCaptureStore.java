package in.pocketvault.local;

import java.util.UUID;

final class AutofillCaptureStore {
    static final class CapturedLogin {
        final String token;
        final String title;
        final String username;
        final String password;

        CapturedLogin(String title, String username, String password) {
            this.token = UUID.randomUUID().toString();
            this.title = title;
            this.username = username;
            this.password = password;
        }
    }

    private static CapturedLogin pending;

    private AutofillCaptureStore() {}

    static synchronized CapturedLogin put(String title, String username, String password) {
        pending = new CapturedLogin(title, username, password);
        return pending;
    }

    static synchronized CapturedLogin peek() {
        return pending;
    }

    static synchronized boolean clear(String token) {
        if (pending == null || token == null || !pending.token.equals(token)) return false;
        pending = null;
        return true;
    }
}
