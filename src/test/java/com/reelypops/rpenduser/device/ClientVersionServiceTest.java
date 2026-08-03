package com.reelypops.rpenduser.device;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for the M5.3c client-version comparison (the soft "update available" verdict + all parse branches). */
class ClientVersionServiceTest {

    private static ClientVersionService withLatest(String latest) {
        return new ClientVersionService(latest);
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

    @Test
    void olderClientFlagsAcrossEachComponent() {
        assertThat(withLatest("0.3.0").updateAvailable("0.2.0")).isTrue();   // minor behind
        assertThat(withLatest("2.0.0").updateAvailable("1.9.9")).isTrue();   // major behind
        assertThat(withLatest("0.2.1").updateAvailable("0.2.0")).isTrue();   // patch behind
    }

    @Test
    void sameOrNewerClientDoesNotFlag() {
        assertThat(withLatest("0.3.0").updateAvailable("0.3.0")).isFalse();  // equal
        assertThat(withLatest("0.3.0").updateAvailable("0.4.0")).isFalse();  // ahead
    }

    @Test
    void preReleaseIsOlderThanItsRelease() {
        assertThat(withLatest("0.2.0").updateAvailable("0.2.0-SNAPSHOT")).isTrue();  // snapshot < release
        assertThat(withLatest("0.2.0-SNAPSHOT").updateAvailable("0.2.0")).isFalse(); // release not < its snapshot
    }

    @Test
    void nonNumericOrShortPartsAreParsedLeniently() {
        assertThat(withLatest("0.2.0").updateAvailable("0.x.0")).isTrue();  // 'x' → 0, so 0.0.0 < 0.2.0
        assertThat(withLatest("0.0.1").updateAvailable("0")).isTrue();      // '0' → 0.0.0 < 0.0.1
    }
}
