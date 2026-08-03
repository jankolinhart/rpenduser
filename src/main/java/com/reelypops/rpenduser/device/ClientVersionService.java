package com.reelypops.rpenduser.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * M5.3c: decides whether a heartbeating client is behind the latest released desktop client. The latest version is a
 * static config value ({@code rp.client.latest-version}, set per stage via the IaC), so cutting a release just bumps
 * it — blank ⇒ nothing is ever flagged. The verdict is a soft, non-blocking "update available" (no forced-update gate).
 */
@Service
public class ClientVersionService {

    private final String latestVersion;

    public ClientVersionService(@Value("${rp.client.latest-version:}") String latestVersion) {
        this.latestVersion = latestVersion == null ? "" : latestVersion.trim();
    }

    /** The configured latest client version, echoed to the client so it can show "update to X" (blank when unset). */
    public String latestVersion() {
        return latestVersion;
    }

    /** True when {@code clientVersion} is strictly older than the configured latest; never flags when either is blank. */
    public boolean updateAvailable(String clientVersion) {
        if (latestVersion.isBlank() || clientVersion == null || clientVersion.isBlank()) {
            return false;
        }
        return isOlder(clientVersion, latestVersion);
    }

    /** major.minor.patch numeric compare; a pre-release suffix (e.g. -SNAPSHOT) on an otherwise-equal core is older than the release. */
    private static boolean isOlder(String a, String b) {
        int[] ca = core(a);
        int[] cb = core(b);
        for (int i = 0; i < 3; i++) {
            if (ca[i] != cb[i]) {
                return ca[i] < cb[i];
            }
        }
        return preRelease(a) && !preRelease(b);
    }

    private static int[] core(String version) {
        String[] parts = version.split("[-+]", 2)[0].split("\\.");
        int[] c = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            c[i] = parseSafe(parts[i]);
        }
        return c;
    }

    private static boolean preRelease(String version) {
        return version.indexOf('-') >= 0;
    }

    private static int parseSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
