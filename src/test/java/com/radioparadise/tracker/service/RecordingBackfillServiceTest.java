package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.RecordingLinks;
import com.radioparadise.tracker.dto.SongDetails;
import com.radioparadise.tracker.repository.SongRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingBackfillServiceTest {
    private final SongRepository songs = mock(SongRepository.class);
    private final SongDetailsService details = mock(SongDetailsService.class);
    private final RecordingBackfillService backfill = new RecordingBackfillService(songs, details);

    @Test
    void scansStableIdPagesAndContinuesAfterProviderFailures() {
        when(songs.findHighestId()).thenReturn(3L);
        when(songs.findIdsWithoutIsrc(0, 3, PageRequest.of(0, 50))).thenReturn(List.of(1L, 2L));
        when(songs.findIdsWithoutIsrc(2, 3, PageRequest.of(0, 50))).thenReturn(List.of(3L));
        when(details.get(1)).thenReturn(result("matched", "GBABC1200001"));
        when(details.get(2)).thenReturn(result("unavailable", null));
        when(details.get(3)).thenReturn(result("matched", null));

        var summary = backfill.run(0, 100);

        assertThat(summary.processed()).isEqualTo(3);
        assertThat(summary.withIsrc()).isEqualTo(1);
        assertThat(summary.matchedWithoutIsrc()).isEqualTo(1);
        assertThat(summary.unavailable()).isEqualTo(1);
        assertThat(summary.unresolved()).isEqualTo(1);
        assertThat(summary.lastId()).isEqualTo(3);
        assertThat(summary.throughId()).isEqualTo(3);
        verify(details).get(3);
    }

    @Test
    void supportsResumingAfterAnIdAndStopsAtTheRequestedLimit() {
        when(songs.findHighestId()).thenReturn(20L);
        when(songs.findIdsWithoutIsrc(10, 20, PageRequest.of(0, 1))).thenReturn(List.of(12L));
        when(details.get(12)).thenReturn(result("not_found", null));

        var summary = backfill.run(10, 1);

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.lastId()).isEqualTo(12);
        verify(songs, never()).findIdsWithoutIsrc(12, 20, PageRequest.of(0, 1));
        assertThatThrownBy(() -> backfill.run(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backfill.run(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dailyRetrySelectsErrorsIncludingPreviouslyAcceptedIsrcs() {
        when(songs.findHighestId()).thenReturn(10L);
        when(songs.findIdsWithRecordingErrors(0, 10, PageRequest.of(0, 50))).thenReturn(List.of(4L));
        when(details.get(4)).thenReturn(result("matched", "GBABC1200001"));

        var summary = backfill.retryFailures(100);

        assertThat(summary.withIsrc()).isEqualTo(1);
        verify(songs, never()).findIdsWithoutIsrc(anyLong(), anyLong(), any());
    }

    @Test
    void databaseFailuresAreReportedAndDoNotPretendThePassCompleted() {
        when(songs.findHighestId()).thenReturn(1L);
        when(songs.findIdsWithoutIsrc(0, 1, PageRequest.of(0, 1))).thenReturn(List.of(1L));
        when(details.get(1)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> backfill.run(0, 1)).hasMessage("database unavailable");
    }

    private static SongDetails result(String status, String isrc) {
        return new SongDetails(null, null, null, 120.0, null, null, null, null,
                new RecordingLinks(isrc, status, null, null, null, null));
    }
}
