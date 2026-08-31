package com.reelypops.rpenduser.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Device-registry logic (D3). Registration is an idempotent upsert keyed on (user, device): a returning
 * device is a heartbeat, not a duplicate. Owned by rpenduser; rppayment reads it for seat enforcement.
 */
@Service
public class DeviceService {

    private final DeviceRepository devices;

    public DeviceService(DeviceRepository devices) {
        this.devices = devices;
    }

    @Transactional
    public Device register(UUID userId, String deviceId, String platform) {
        return devices.findByUserIdAndDeviceId(userId, deviceId)
                .map(existing -> {
                    existing.heartbeat(platform);
                    return devices.save(existing);
                })
                .orElseGet(() -> devices.save(Device.register(userId, deviceId, platform)));
    }

    /**
     * M5.1 heartbeat: refresh the device's liveness ({@code online} + last-seen) and report whether the backend
     * wants a fresh full report — i.e. the client's current {@code stateHash} differs from the last one we stored
     * (or we have never stored one). Idempotent-upserts so a heartbeat before the first registration still lands.
     */
    @Transactional
    public boolean heartbeat(UUID userId, String deviceId, boolean online, String stateHash) {
        Device device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseGet(() -> Device.register(userId, deviceId, null));
        device.checkIn(online);
        boolean reportNeeded = !Objects.equals(stateHash, device.getStateHash());
        devices.save(device);
        return reportNeeded;
    }

    /**
     * M5.1 report: store the client's full backward-contract snapshot ({@code report}) + its {@code stateHash}, so
     * a subsequent heartbeat carrying the same hash no longer asks for a report. Idempotent-upserts.
     */
    @Transactional
    public Device applyReport(UUID userId, String deviceId, String report, String stateHash) {
        Device device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseGet(() -> Device.register(userId, deviceId, null));
        device.applyReport(report, stateHash);
        device.setFocusedHandle(focusedHandleOf(report));
        return devices.save(device);
    }

    /**
     * The handle {@code igAccounts[]} marks {@code inFocus}, or {@code null}.
     *
     * <p>TOTAL, and deliberately so: a report is stored opaquely and additively, so this must survive a body
     * that is malformed, differently shaped, or from a future client. Anything it cannot read means the
     * device claims NOTHING — never an exception, because a projection failure must not fail the report that
     * carries it, and never a blank, because a blank would collide with every other silent device.
     *
     * <p>More than one {@code inFocus} cannot happen on a correct client (the focus is a singleton row) and is
     * not worth an error here: the first is taken, which is the same answer the scheduler would act on.
     */
    static String focusedHandleOf(String report) {
        if (report == null || report.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode accounts =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(report).path("igAccounts");
            for (com.fasterxml.jackson.databind.JsonNode account : accounts) {
                if (account.path("inFocus").asBoolean(false)) {
                    String handle = account.path("handle").asText(null);
                    return handle == null || handle.isBlank() ? null : handle;
                }
            }
        } catch (Exception e) {
            // A report we cannot parse is a report that claims nothing. It is still STORED — the blob is the
            // source of truth and a later reader may understand it — so nothing is lost by declining to guess.
            return null;
        }
        return null;
    }

    /**
     * How long a device may go unheard before its claim stops blocking anyone. Operator, 28/08/2026: "a few
     * minutes at best, otherwise the user will assume a fault when nothing is moving."
     *
     * <p>Five heartbeats. The asymmetry decides the direction: expiring EARLY hands a live machine's handle to
     * a second one and recreates the double-like-rate this whole mechanism exists to prevent, while expiring
     * LATE is only an inconvenience — and one the user can end themselves, because the holder is named and a
     * takeover is offered. So the window errs long, and the UI carries the weight rather than the timeout.
     *
     * <p>Measured on {@code lastSeenAt}, NEVER {@code lastReportAt}. A report is re-sent only when the
     * stateHash CHANGES, so a device sitting stably on one handle — doing exactly what it should — stops
     * reporting entirely. Expiring on the report clock would steal the handle from the healthiest device on
     * the account.
     */
    public static final Duration CLAIM_TTL = Duration.ofMinutes(5);

    /**
     * PRESENCE — what an operator should be told about a machine. Three states, not two.
     *
     * <p>Deliberately NOT {@link #CLAIM_TTL}. That answers "may another machine take this contested handle?",
     * where being wrong means two machines liking one account at double the pacing — a SAFETY threshold, and
     * safety wants to be lenient. This is a DISPLAY threshold, where being wrong costs a stale badge, so it
     * can be tight. Bending one to serve the other corrupts both. (rpauth's 24h seat-idle timeout is a third
     * question again, on a commercial clock.)
     *
     * <p>Live is three missed 60s beats rather than two, because the client's heartbeat is scheduled with a
     * fixed DELAY measured from completion and carries no jitter — a slow beat stretches the interval, and
     * two would mark a healthy-but-slow machine down.
     *
     * <p>STALE is what makes a tight LIVE safe: "not beating, but recently was — a blip or a crash". Without
     * it, LIVE has to be generous to avoid crying wolf, which is what pushes the first boundary out to an
     * hour and lets a machine that died half an hour ago read as running.
     *
     * <p>The age is always shown alongside, so these bands are a convenience rather than the truth.
     */
    public enum Presence { LIVE, STALE, OFFLINE }

    @Value("${rp.device.presence.live-within:PT3M}")
    private Duration liveWithin = Duration.ofMinutes(3);

    @Value("${rp.device.presence.stale-within:PT1H}")
    private Duration staleWithin = Duration.ofHours(1);

    /** What to show for this device now. See {@link Presence}. */
    public Presence presenceOf(Device device, Instant now) {
        if (device == null || device.getLastSeenAt() == null) {
            return Presence.OFFLINE;   // never heard from is not "live"
        }
        // A clean goodbye is believed IMMEDIATELY — that is the whole point of it. checkIn clears the stamp,
        // so a device that came back cannot still be holding one.
        if (device.getShutdownAt() != null) {
            return Presence.OFFLINE;
        }
        if (!device.getLastSeenAt().isBefore(now.minus(liveWithin))) {
            return Presence.LIVE;
        }
        if (!device.getLastSeenAt().isBefore(now.minus(staleWithin))) {
            return Presence.STALE;
        }
        return Presence.OFFLINE;
    }

    /**
     * The client is closing cleanly and says so.
     *
     * <p>Upserts like every other device write, so a goodbye that arrives before any registration still
     * lands rather than being dropped.
     */
    @Transactional
    public void goodbye(UUID userId, String deviceId) {
        Device device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseGet(() -> Device.register(userId, deviceId, null));
        device.sayGoodbye();
        devices.save(device);
    }

    /**
     * The user's other devices claiming {@code handle}, whether live or not. Empty when the handle is absent,
     * so a caller cannot turn "nothing reported" into a conflict.
     */
    @Transactional(readOnly = true)
    public List<Device> claimingSameHandle(UUID userId, String deviceId, String handle) {
        if (handle == null || handle.isBlank()) {
            return List.of();
        }
        return devices.findByUserIdAndFocusedHandleAndDeviceIdNot(
                userId, handle.trim().toLowerCase(java.util.Locale.ROOT), deviceId);
    }

    /** Heard from within {@link #CLAIM_TTL}. A device with no lastSeenAt at all has never checked in. */
    static boolean isLive(Device device, Instant now) {
        return device.getLastSeenAt() != null && !device.getLastSeenAt().isBefore(now.minus(CLAIM_TTL));
    }

    /**
     * Who HOLDS {@code handle} for this user, or empty when nobody live does.
     *
     * <p>The incumbent holds — the operator's rule of 28/08/2026 — so of the LIVE claimants the one with the
     * earliest claim stamp wins. A second machine must never be able to interrupt a round already in flight.
     * A claimant that has gone quiet past {@link #CLAIM_TTL} is not a holder: it blocks nobody, which is what
     * stops a closed laptop freezing the account it was working.
     */
    @Transactional(readOnly = true)
    public Optional<Device> holderOf(UUID userId, String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return devices.findByUserIdAndFocusedHandle(userId, handle.trim().toLowerCase(java.util.Locale.ROOT))
                .stream()
                .filter(device -> isLive(device, now))
                .min(Comparator.comparing(Device::getFocusedHandleAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /**
     * Hand {@code handle} to {@code toDeviceId}: every OTHER device of this user releases its claim.
     *
     * <p>The loser keeps running — this takes away the CLAIM, not the machine. It learns on its next report
     * that it no longer holds, and because a re-report stamps it afresh it becomes the junior claim and does
     * not win the handle back. Returns the device ids that yielded, so the caller can say what happened.
     */
    @Transactional
    public List<String> takeOverHandle(UUID userId, String toDeviceId, String handle) {
        if (handle == null || handle.isBlank() || toDeviceId == null || toDeviceId.isBlank()) {
            return List.of();
        }
        List<Device> yielding = devices.findByUserIdAndFocusedHandleAndDeviceIdNot(
                userId, handle.trim().toLowerCase(java.util.Locale.ROOT), toDeviceId);
        List<String> yielded = yielding.stream().map(Device::getDeviceId).toList();
        yielding.forEach(Device::releaseFocusedHandle);
        devices.saveAll(yielding);
        return yielded;
    }

    @Transactional(readOnly = true)
    public List<Device> list(UUID userId) {
        return devices.findByUserIdOrderByLastSeenAtDesc(userId);
    }

    /** Per-user device counts for the admin dashboard (internal surface). */
    @Transactional(readOnly = true)
    public List<DeviceCount> counts() {
        return devices.countByUser();
    }

    @Transactional
    public boolean remove(UUID userId, String deviceId) {
        return devices.deleteByUserIdAndDeviceId(userId, deviceId) > 0;
    }
}
