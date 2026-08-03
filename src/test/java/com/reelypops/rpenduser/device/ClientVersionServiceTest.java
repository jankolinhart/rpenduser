package com.reelypops.rpenduser.device;

import com.reelypops.rpenduser.release.ClientReleaseService;
import com.reelypops.rpenduser.release.ReleaseAnnouncement;
import com.reelypops.rpenduser.release.UpdateUrgency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit coverage for the M5.3c client-version comparison (the soft "update available" verdict + all parse branches). */
class ClientVersionServiceTest {

    /** A service whose "latest" comes only from the rp.client.latest-version floor (no admin-announced version). */
    private static ClientVersionService withLatest(String latest) {
        return new ClientVersionService(latest, mock(ClientReleaseService.class));
    }

    @Test
    void blankLatestNeverFlagsAndEchoesEmpty() {
        ClientVersionService svc = withLatest("");
        assertThat(svc.latestVersion()).isEmpty();
        assertThat(svc.updateAvailable("0.1.0")).isFalse();
    }

    @Test
    void nullLatestIsTreatedAsBlank() {
        ClientVersionService svc = withLatest(null);
        assertThat(svc.latestVersion()).isEmpty();
        assertThat(svc.updateAvailable("0.1.0")).isFalse();
    }

    @Test
    void latestVersionIsTrimmed() {
        assertThat(withLatest("  0.3.0  ").latestVersion()).isEqualTo("0.3.0");
    }

    @Test
    void nullOrBlankClientNeverFlags() {
        ClientVersionService svc = withLatest("0.3.0");
        assertThat(svc.updateAvailable(null)).isFalse();
        assertThat(svc.updateAvailable("   ")).isFalse();
    }

    // --- PROD / RELEASE channel: strictly version-number driven ---

    @Test
    void releaseChannelFlagsAStrictlyNewerVersion() {
        assertThat(withLatest("0.3.0").updateAvailable("0.2.0")).isTrue();   // minor behind
        assertThat(withLatest("2.0.0").updateAvailable("1.9.9")).isTrue();   // major behind
        assertThat(withLatest("0.2.1").updateAvailable("0.2.0")).isTrue();   // patch behind
    }

    @Test
    void releaseChannelDoesNotFlagWhenEqualOrAhead() {
        assertThat(withLatest("0.3.0").updateAvailable("0.3.0")).isFalse();  // equal
        assertThat(withLatest("0.3.0").updateAvailable("0.4.0")).isFalse();  // client ahead
    }

    // --- DEV / SNAPSHOT + TEST / RC channels: the sha distinguishes builds of the same core ---

    @Test
    void snapshotChannelFlagsADifferentBuildOfTheSameCore() {
        assertThat(withLatest("0.2.0-SNAPSHOT.def5678").updateAvailable("0.2.0-SNAPSHOT.abc1234")).isTrue();  // new sha
        assertThat(withLatest("0.2.0-SNAPSHOT.abc1234").updateAvailable("0.2.0-SNAPSHOT.abc1234")).isFalse(); // same build
        assertThat(withLatest("0.3.0-SNAPSHOT.aaa").updateAvailable("0.2.0-SNAPSHOT.bbb")).isTrue();          // newer core
    }

    @Test
    void rcChannelFlagsADifferentBuildOfTheSameCore() {
        assertThat(withLatest("0.2.0-rc.def5678").updateAvailable("0.2.0-rc.abc1234")).isTrue();   // new sha
        assertThat(withLatest("0.2.0-rc.abc1234").updateAvailable("0.2.0-rc.abc1234")).isFalse();  // same build
        assertThat(withLatest("0.2.0-rc.aaa").updateAvailable("0.3.0-rc.bbb")).isFalse();          // client core ahead
    }

    // --- CROSS-CHANNEL: a DEV/TEST build is NEVER offered to a PROD client (and vice-versa) ---

    @Test
    void neverOffersAcrossChannels() {
        // a PROD (release) client is only ever offered a release
        assertThat(withLatest("0.3.0-SNAPSHOT.x").updateAvailable("0.2.0")).isFalse();  // release client, snapshot latest
        assertThat(withLatest("0.3.0-rc.x").updateAvailable("0.2.0")).isFalse();        // release client, rc latest
        // a DEV (snapshot) client is never offered a release or an rc
        assertThat(withLatest("0.3.0").updateAvailable("0.2.0-SNAPSHOT.x")).isFalse();
        assertThat(withLatest("0.3.0-rc.y").updateAvailable("0.2.0-SNAPSHOT.x")).isFalse();
        // a TEST (rc) client is never offered a release or a snapshot
        assertThat(withLatest("0.3.0").updateAvailable("0.2.0-rc.x")).isFalse();
        assertThat(withLatest("0.3.0-SNAPSHOT.y").updateAvailable("0.2.0-rc.x")).isFalse();
    }

    @Test
    void nonNumericOrShortPartsAreParsedLeniently() {
        assertThat(withLatest("0.2.0").updateAvailable("0.x.0")).isTrue();  // 'x' → 0, so 0.0.0 < 0.2.0 (both release)
        assertThat(withLatest("0.0.1").updateAvailable("0")).isTrue();      // '0' → 0.0.0 < 0.0.1 (both release)
    }

    // --- M5.3c: the admin-ANNOUNCED version is the source of "latest" (the env value is only a fallback floor) ---

    @Test
    void announcedVersionOverridesTheEnvFloor() {
        ClientReleaseService releases = mock(ClientReleaseService.class);
        when(releases.announcedVersion()).thenReturn("0.4.0");
        ClientVersionService svc = new ClientVersionService("0.1.0", releases);  // the 0.1.0 floor is ignored
        assertThat(svc.latestVersion()).isEqualTo("0.4.0");
        assertThat(svc.updateAvailable("0.3.0")).isTrue();   // behind the announced version
        assertThat(svc.updateAvailable("0.4.0")).isFalse();  // up to date with it
    }

    @Test
    void blankAnnouncedFallsBackToTheEnvFloor() {
        ClientReleaseService releases = mock(ClientReleaseService.class);
        when(releases.announcedVersion()).thenReturn("   ");
        assertThat(new ClientVersionService("0.5.0", releases).latestVersion()).isEqualTo("0.5.0");
    }

    @Test
    void announcementIsPassedThroughFromTheReleaseState() {
        ClientReleaseService releases = mock(ClientReleaseService.class);
        ReleaseAnnouncement ann = new ReleaseAnnouncement("0.4.0", UpdateUrgency.URGENT, List.of("Security fixes"));
        when(releases.announcement()).thenReturn(ann);
        assertThat(new ClientVersionService("", releases).announcement()).isSameAs(ann);
    }
}
