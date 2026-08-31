package com.reelypops.rpenduser.device;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "IS THIS CLIENT RUNNING?" — THREE ANSWERS, AND THE AGE ALONGSIDE.
 *
 * <p>Before this, nothing in the system computed a general liveness at all. The only predicate was
 * {@code isLive} against {@code CLAIM_TTL}, package-private and called from exactly one method to resolve a
 * contested Instagram handle. The {@code online} boolean the client sends every 60s was written to the
 * database and read by nothing — and was not even client liveness, but a Google 204 reachability probe.
 *
 * <p>The bands here are a DISPLAY threshold and are deliberately not {@code CLAIM_TTL}:
 *
 * <ul>
 *   <li>{@code CLAIM_TTL} (5 min) answers "may another machine take this contested handle?" — being wrong
 *       means two machines liking one account at double the pacing. Safety, so it wants to be lenient.</li>
 *   <li>rpauth's seat-idle timeout (24 h) answers "is this seat abandoned?" — commercial, more lenient
 *       still.</li>
 *   <li>Presence answers "should the console show this as running?" — being wrong costs a stale badge, so
 *       it can be tight.</li>
 * </ul>
 *
 * <p>Bending one to serve another corrupts both, which is why there are three numbers and not one.
 */
class DevicePresenceTest {

    private static final UUID USER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceService service = new DeviceService(devices);

    private final Instant now = Instant.parse("2026-08-31T12:00:00Z");

    private Device seenAt(Instant lastSeen) {
        Device d = Device.register(USER, "dev-1", "Mac OS X 14.5");
        ReflectionTestUtils.setField(d, "lastSeenAt", lastSeen);
        return d;
    }

    @Test
    void aDeviceHeardFromWithinThreeMinutesIsLIVE() {
        assertThat(service.presenceOf(seenAt(now.minus(Duration.ofSeconds(90))), now))
                .isEqualTo(DeviceService.Presence.LIVE);
    }

    @Test
    void threeMissedBEATSratherThanTwo_becauseTheBeatCanStretch() {
        // The client schedules with a fixed DELAY measured from completion and no jitter, so a slow or
        // timing-out beat pushes the next one out. Two missed beats would mark a healthy machine down.
        assertThat(service.presenceOf(seenAt(now.minus(Duration.ofSeconds(150))), now))
                .isEqualTo(DeviceService.Presence.LIVE);
    }

    @Test
    void pastThreeMinutesItIsSTALE_notOffline() {
        // STALE is what lets LIVE be tight. Without it, LIVE has to be generous to avoid crying wolf — which
        // is what pushes the first boundary out to an hour and lets a machine that died half an hour ago
        // read as running.
        assertThat(service.presenceOf(seenAt(now.minus(Duration.ofMinutes(20))), now))
                .isEqualTo(DeviceService.Presence.STALE);
    }

    @Test
    void pastAnHourItIsOFFLINE() {
        assertThat(service.presenceOf(seenAt(now.minus(Duration.ofHours(3))), now))
                .isEqualTo(DeviceService.Presence.OFFLINE);
    }

    @Test
    void aDeviceNEVERheardFromIsOfflineRatherThanLive() {
        Device never = Device.register(USER, "dev-never", null);
        ReflectionTestUtils.setField(never, "lastSeenAt", null);

        assertThat(service.presenceOf(never, now)).isEqualTo(DeviceService.Presence.OFFLINE);
        assertThat(service.presenceOf(null, now)).isEqualTo(DeviceService.Presence.OFFLINE);
    }

    // ── the goodbye ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aCLEANgoodbyeIsBelievedIMMEDIATELY() {
        // The entire point: without it, a laptop closed thirty seconds ago is indistinguishable from one
        // mid-run for up to five minutes.
        // THE REAL CLOCK THROUGHOUT. sayGoodbye stamps Instant.now(), so judging it against the fixed `now`
        // above compared two different clocks — the device landed past the 1h band on skew alone and the
        // test passed whether or not the goodbye was honoured at all. Seen seconds ago, it is LIVE by every
        // measure except the goodbye, which is precisely what this has to prove.
        Device d = seenAt(Instant.now());
        d.sayGoodbye();

        assertThat(service.presenceOf(d, Instant.now())).isEqualTo(DeviceService.Presence.OFFLINE);
    }

    @Test
    void comingBackCLEARStheGoodbye_soADeviceThatReturnsIsSimplyBack() {
        Device d = seenAt(now.minus(Duration.ofHours(2)));
        d.sayGoodbye();
        assertThat(d.getShutdownAt()).isNotNull();

        d.checkIn(true);

        assertThat(d.getShutdownAt()).isNull();
        assertThat(service.presenceOf(d, Instant.now())).isEqualTo(DeviceService.Presence.LIVE);
    }

    @Test
    void goodbyeUPSERTS_soOneArrivingBeforeAnyRegistrationStillLands() {
        when(devices.findByUserIdAndDeviceId(USER, "dev-unknown")).thenReturn(Optional.empty());
        when(devices.save(any(Device.class))).thenAnswer(call -> call.getArgument(0));

        service.goodbye(USER, "dev-unknown");

        verify(devices).save(any(Device.class));
    }

    @Test
    void goodbyeALSOmovesLastSeen_soTheAgeShownIsNotOlderThanTheEvent() {
        // Against the REAL clock: sayGoodbye stamps Instant.now(), while `now` above is a fixed instant used
        // only for the band arithmetic. Leaving last-seen stale would make the age shown beside the badge
        // older than the event that produced it.
        Device d = seenAt(Instant.now().minus(Duration.ofHours(6)));
        Instant beforeTheCall = Instant.now();

        d.sayGoodbye();

        assertThat(d.getLastSeenAt()).isAfterOrEqualTo(beforeTheCall);
    }
}
