package com.radioparadise.tracker.service;

import com.radioparadise.tracker.client.MusicBrainzClient;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecordingLinksServiceTest {
    private final SongRepository songs = mock(SongRepository.class);
    private final MusicBrainzClient catalog = mock(MusicBrainzClient.class);
    private final RecordingLinksService service = new RecordingLinksService(songs, catalog);
    private final Song song = Song.builder().id(1L).artist("Artist").title("Title")
            .album("Album").durationSeconds(120.0).playCount(7).build();

    @Test
    void persistsIsrcAndProvenanceWithTargetedUpdateOnly() {
        when(catalog.find("Artist", "Title", "Album", 120.0))
                .thenReturn(new MusicBrainzClient.Result("matched", "GBABC1200001", "source", "spotify", null));

        service.refresh(song);

        verify(songs).updateRecordingLinks(eq(1L), eq("GBABC1200001"), eq("matched"),
                eq("source"), eq("spotify"), isNull(), any(Instant.class));
        verify(songs, never()).save(any());
    }

    @Test
    void successfulMatchesRemainCachedForThirtyDays() {
        song.setRecordingMatchStatus("matched");
        song.setRecordingCheckedAt(Instant.now().minus(Duration.ofDays(29)));

        service.refresh(song);

        verifyNoInteractions(catalog, songs);
    }

    @Test
    void missingMatchesRetryAfterOneDay() {
        song.setRecordingMatchStatus("not_found");
        song.setRecordingCheckedAt(Instant.now().minusSeconds(60));
        service.refresh(song);
        verifyNoInteractions(catalog, songs);
        song.setRecordingCheckedAt(Instant.now().minus(Duration.ofDays(2)));
        when(catalog.find("Artist", "Title", "Album", 120.0))
                .thenReturn(MusicBrainzClient.Result.unresolved("ambiguous"));

        service.refresh(song);

        verify(songs).updateRecordingLinks(eq(1L), isNull(), eq("ambiguous"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void temporaryFailurePreservesAcceptedLinksAndUsesShortRetryInterval() {
        song.setRecordingMatchStatus("unavailable");
        song.setRecordingCheckedAt(Instant.now().minusSeconds(60));
        song.setIsrc("GBABC1200001");
        song.setRecordingMatchSource("source");
        song.setSpotifyUrl("spotify");
        service.refresh(song);
        verifyNoInteractions(catalog, songs);
        song.setRecordingCheckedAt(Instant.now().minusSeconds(301));
        when(catalog.find("Artist", "Title", "Album", 120.0))
                .thenReturn(MusicBrainzClient.Result.unresolved("unavailable"));

        service.refresh(song);

        verify(songs).updateRecordingLinks(eq(1L), eq("GBABC1200001"), eq("unavailable"),
                eq("source"), eq("spotify"), isNull(), any());
    }

    @Test
    void retriesWhenPreviouslyMissingDurationBecomesAvailable() {
        song.setRecordingMatchStatus("insufficient_metadata");
        song.setRecordingCheckedAt(Instant.now());
        when(catalog.find("Artist", "Title", "Album", 120.0))
                .thenReturn(MusicBrainzClient.Result.unresolved("not_found"));

        service.refresh(song);

        verify(catalog).find("Artist", "Title", "Album", 120.0);
    }
}
