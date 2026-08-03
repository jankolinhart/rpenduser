package com.reelypops.rpenduser.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit coverage for the M5.3c client-release state machine — the published/announced pointers + the human gate. */
@ExtendWith(MockitoExtension.class)
class ClientReleaseServiceTest {

    @Mock
    ClientReleaseStateRepository repo;

    private ClientReleaseService dev() {
        return new ClientReleaseService(repo, "dev");
    }

    private ClientReleaseService prod() {
        return new ClientReleaseService(repo, "prod");
    }

    @Test
    void publishIgnoresBlankOrNull() {
        dev().updatePublishedVersion("   ");
        dev().updatePublishedVersion(null);
        verifyNoInteractions(repo);
    }

    @Test
    void publishWithTheGateOnSetsAPendingReleaseNotAnnounced() {
        when(repo.findById(1)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dev().updatePublishedVersion("0.2.0-SNAPSHOT.abc");

        ArgumentCaptor<ClientReleaseState> saved = ArgumentCaptor.forClass(ClientReleaseState.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getPublishedVersion()).isEqualTo("0.2.0-SNAPSHOT.abc");
        assertThat(saved.getValue().getAnnouncedVersion()).isNull();   // gate on → awaiting the admin gate
    }

    @Test
    void publishWithTheGateOffAutoAnnounces() {
        ClientReleaseState existing = ClientReleaseState.initial();
        existing.gate(false);
        when(repo.findById(1)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dev().updatePublishedVersion("0.2.0-SNAPSHOT.abc");

        assertThat(existing.getAnnouncedVersion()).isEqualTo("0.2.0-SNAPSHOT.abc");
        assertThat(existing.getAnnouncementUrgency()).isEqualTo(UpdateUrgency.NORMAL);
    }

    @Test
    void publishingTheSameVersionIsANoOp() {
        ClientReleaseState existing = ClientReleaseState.initial();
        existing.publish("0.2.0", Instant.now());
        when(repo.findById(1)).thenReturn(Optional.of(existing));

        dev().updatePublishedVersion("0.2.0");

        verify(repo, never()).save(any());
    }

    @Test
    void announcePromotesThePublishedVersionWithACleanedBlurb() {
        ClientReleaseState existing = ClientReleaseState.initial();
        existing.publish("0.4.0", Instant.now());
        when(repo.findById(1)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<PendingReleaseView> view =
                dev().announce(List.of(" Faster ", "", "scans\n done"), UpdateUrgency.RECOMMENDED);

        assertThat(view).isPresent();
        assertThat(view.get().announcedVersion()).isEqualTo("0.4.0");
        assertThat(view.get().pendingAnnouncement()).isFalse();          // announced == published
        assertThat(view.get().urgency()).isEqualTo(UpdateUrgency.RECOMMENDED);
        assertThat(view.get().highlights()).containsExactly("Faster", "scans done");  // blanks dropped, whitespace collapsed
    }

    @Test
    void announceDefaultsNullUrgencyToNormalAndNullHighlightsToEmpty() {
        ClientReleaseState existing = ClientReleaseState.initial();
        existing.publish("0.4.0", Instant.now());
        when(repo.findById(1)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PendingReleaseView view = dev().announce(null, null).orElseThrow();

        assertThat(view.urgency()).isEqualTo(UpdateUrgency.NORMAL);
        assertThat(view.highlights()).isEmpty();
    }

    @Test
    void announceIsEmptyWhenNothingIsPublished() {
        when(repo.findById(1)).thenReturn(Optional.empty());

        assertThat(dev().announce(List.of("x"), UpdateUrgency.URGENT)).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void gateCanBeToggledOnDev() {
        when(repo.findById(1)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(dev().setGateEnabled(false).gateEnabled()).isFalse();
    }

    @Test
    void gateIsForcedOnForProd() {
        when(repo.findById(1)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(prod().setGateEnabled(false).gateEnabled()).isTrue();   // PROD ignores the request to disable
    }

    @Test
    void pendingIsEmptyWhenNothingIsPublished() {
        when(repo.findById(1)).thenReturn(Optional.empty());

        PendingReleaseView view = dev().pending();
        assertThat(view.publishedVersion()).isNull();
        assertThat(view.pendingAnnouncement()).isFalse();
        assertThat(view.gateEnabled()).isTrue();
        assertThat(view.highlights()).isEmpty();
    }

    @Test
    void pendingShowsAPublishedButNotYetAnnouncedRelease() {
        ClientReleaseState existing = ClientReleaseState.initial();
        existing.publish("0.5.0", Instant.now());
        when(repo.findById(1)).thenReturn(Optional.of(existing));

        PendingReleaseView view = dev().pending();
        assertThat(view.publishedVersion()).isEqualTo("0.5.0");
        assertThat(view.announcedVersion()).isNull();
        assertThat(view.pendingAnnouncement()).isTrue();
        assertThat(view.highlights()).isEmpty();   // null highlights → decoded empty
    }

    @Test
    void announcedVersionAndPayloadArePresentWhenAnnounced() {
        ClientReleaseState existing = ClientReleaseState.initial();
        existing.publish("0.4.0", Instant.now());
        existing.announce("0.4.0", UpdateUrgency.URGENT, "Security fixes", Instant.now());
        when(repo.findById(1)).thenReturn(Optional.of(existing));

        ClientReleaseService svc = dev();
        assertThat(svc.announcedVersion()).isEqualTo("0.4.0");
        ReleaseAnnouncement ann = svc.announcement();
        assertThat(ann.version()).isEqualTo("0.4.0");
        assertThat(ann.urgency()).isEqualTo(UpdateUrgency.URGENT);
        assertThat(ann.highlights()).containsExactly("Security fixes");
    }

    @Test
    void announcedVersionAndPayloadAreAbsentWhenNothingIsAnnounced() {
        when(repo.findById(1)).thenReturn(Optional.empty());

        ClientReleaseService svc = dev();
        assertThat(svc.announcedVersion()).isNull();
        assertThat(svc.announcement()).isNull();
    }

    @Test
    void announcementDefaultsANullStoredUrgencyToNormal() {
        ClientReleaseState existing = ClientReleaseState.initial();
        existing.announce("0.4.0", null, "note", Instant.now());
        when(repo.findById(1)).thenReturn(Optional.of(existing));

        assertThat(dev().announcement().urgency()).isEqualTo(UpdateUrgency.NORMAL);
    }
}
