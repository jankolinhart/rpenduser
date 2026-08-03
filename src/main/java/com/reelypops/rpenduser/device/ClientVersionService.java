package com.reelypops.rpenduser.device;

import com.reelypops.rpenduser.release.ClientReleaseService;
import com.reelypops.rpenduser.release.ReleaseAnnouncement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * M5.3c: decides whether a heartbeating client is behind the latest desktop client ON ITS OWN CHANNEL. The "latest"
 * is the admin-ANNOUNCED version held in the client-release state ({@link ClientReleaseService}) — the version the
 * admin gate has deliberately published to the fleet — falling back to the {@code rp.client.latest-version} floor
 * (a bootstrap/override; DEV {@code x.x.x-SNAPSHOT.<sha>} / TEST {@code x.x.x-rc.<sha>} / PROD clean {@code x.x.x}).
 * Blank ⇒ nothing is ever flagged. The verdict is CHANNEL-aware: a DEV or TEST build is NEVER offered to a PROD
 * client (and vice-versa). It is a soft, non-blocking "update available" hint (no forced-update gate).
 */
@Service
public class ClientVersionService {

    private final String envFloor;
    private final ClientReleaseService releases;

    public ClientVersionService(@Value("${rp.client.latest-version:}") String envFloor, ClientReleaseService releases) {
        this.envFloor = envFloor == null ? "" : envFloor.trim();
        this.releases = releases;
    }

    /**
     * The effective latest client version echoed to the client — the admin-ANNOUNCED version if there is one, else the
     * {@code rp.client.latest-version} floor (blank when neither is set).
     */
    public String latestVersion() {
        String announced = releases.announcedVersion();
        return announced != null && !announced.isBlank() ? announced.trim() : envFloor;
    }

    /**
     * A soft, non-blocking "update available" verdict that is CHANNEL-aware: each stage advertises only its OWN
     * channel's latest, so an update is offered ONLY when the client and the latest are on the SAME channel — a DEV
     * or TEST build is NEVER offered to a PROD client (and vice-versa). Within a channel a newer core version flags;
     * on the pre-release channels (SNAPSHOT / RC) a same-core build with a different build id (sha) flags (the sha is
     * the per-build distinguisher); the release channel flags only on a strictly-higher version number. Never flags
     * when either version is blank.
     */
    public boolean updateAvailable(String clientVersion) {
        String latest = latestVersion();
        if (latest.isBlank() || clientVersion == null || clientVersion.isBlank()) {
            return false;
        }
        return isBehind(clientVersion.trim(), latest);
    }

    /** The public announcement payload (urgency + curated blurb) for the announced version, or {@code null} when none. */
    public ReleaseAnnouncement announcement() {
        return releases.announcement();
    }

    /** Whether {@code client} is behind {@code latest} on the SAME channel (see {@link #updateAvailable}). */
    private static boolean isBehind(String client, String latest) {
        if (channelOf(client) != channelOf(latest)) {
            return false;   // never cross-channel: a DEV/TEST build is never offered to a PROD client, or vice-versa
        }
        int cmp = compareCore(core(latest), core(client));
        if (cmp != 0) {
            return cmp > 0;   // a newer core on the same channel flags; a client ahead of latest does not
        }
        // Same channel + same core: a release is identical (up to date); a pre-release differs iff its build id (sha) differs.
        return channelOf(client) != Channel.RELEASE && !client.equalsIgnoreCase(latest);
    }

    /** The release channel of a version, from its qualifier: {@code -rc.} → RC, {@code -snapshot} → SNAPSHOT, else RELEASE. */
    private static Channel channelOf(String version) {
        String v = version.toLowerCase();
        if (v.contains("-rc.")) {
            return Channel.RC;
        }
        if (v.contains("-snapshot")) {
            return Channel.SNAPSHOT;
        }
        return Channel.RELEASE;
    }

    /** major.minor.patch compare: {@code >0} if {@code a} is newer, {@code <0} if older, {@code 0} if equal. */
    private static int compareCore(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static int[] core(String version) {
        String[] parts = version.split("[-+]", 2)[0].split("\\.");
        int[] c = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            c[i] = parseSafe(parts[i]);
        }
        return c;
    }

    private static int parseSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The three release channels; a client is only ever offered an update on its own channel. */
    private enum Channel { SNAPSHOT, RC, RELEASE }
}
