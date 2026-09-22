package com.reelypops.rpenduser.scrape;

import com.reelypops.rpenduser.device.Device;
import com.reelypops.rpenduser.device.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WHAT EACH MACHINE IS DOING ABOUT A GROUP'S DEEP SCRAPE.
 *
 * <p>Machines write here as they work; the admin console reads a whole group at once. The two sides never meet
 * — a machine knows only itself, and the console wants "every client running @thisgroup" — which is why this
 * lives in the registry that already owns what a machine is, rather than being derived on either side.</p>
 */
@Service
public class ScrapeProgressService {

    /**
     * The states a machine may report. <strong>IDLE is deliberately not among them</strong>: idleness is the
     * absent row, and a machine that wanted to say "I am doing nothing" would be making a claim this table
     * cannot keep true — the moment it closes, the row would still say idle.
     */
    static final Set<String> STATES = Set.of("REQUESTED", "DELIVERED", "RUNNING", "DONE", "FAILED", "REFUSED");

    private final DeviceScrapeProgressRepository progress;
    private final DeviceRepository devices;

    public ScrapeProgressService(DeviceScrapeProgressRepository progress, DeviceRepository devices) {
        this.progress = progress;
        this.devices = devices;
    }

    /** Thrown when the reporting machine is not registered to this customer, or reports a state we do not know. */
    public static class UnknownScrapeReport extends RuntimeException {
        public UnknownScrapeReport(String message) {
            super(message);
        }
    }

    @Transactional
    public void report(UUID userId, ScrapeProgressRequest req) {
        String state = req.state() == null ? "" : req.state().trim().toUpperCase(Locale.ROOT);
        if (!STATES.contains(state)) {
            throw new UnknownScrapeReport("Unknown scrape state: " + req.state());
        }
        Device device = devices.findByUserIdAndDeviceId(userId, req.deviceId())
                .orElseThrow(() -> new UnknownScrapeReport("No such device for this user: " + req.deviceId()));

        String group = norm(req.igAccount());
        DeviceScrapeProgress row = progress.findByDeviceUuidAndIgAccount(device.getId(), group)
                .orElseGet(() -> new DeviceScrapeProgress(device.getId(), group));
        row.record(state, req.scannedCount(), req.startedAt(), req.finishedAt(), trim(req.message()),
                Instant.now());
        progress.save(row);
    }

    /**
     * Every machine's state for one group.
     *
     * <p>The skip is belt-and-braces and says so: the foreign key cascades, so a progress row cannot outlive
     * its device and this filter should never drop anything. It is kept because the alternative if that
     * invariant ever broke is a row with a null machine — something the console would draw, and an operator
     * could not act on.
     */
    @Transactional(readOnly = true)
    public List<ScrapeProgressView> forGroup(String igAccount) {
        List<DeviceScrapeProgress> rows = progress.findByIgAccount(norm(igAccount));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> fingerprintByRow = new HashMap<>();
        devices.findAllById(rows.stream().map(DeviceScrapeProgress::getDeviceUuid).distinct().toList())
                .forEach(d -> fingerprintByRow.put(d.getId(), d.getDeviceId()));

        return rows.stream()
                .filter(r -> fingerprintByRow.containsKey(r.getDeviceUuid()))
                .map(r -> new ScrapeProgressView(
                        fingerprintByRow.get(r.getDeviceUuid()), r.getIgAccount(), r.getState(),
                        r.getScannedCount(), r.getStartedAt(), r.getFinishedAt(), r.getMessage(),
                        r.getUpdatedAt()))
                .toList();
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** The column holds 500 characters; a client's exception text is not allowed to fail the whole report. */
    private static String trim(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String t = message.trim();
        return t.length() <= 500 ? t : t.substring(0, 500);
    }
}
