package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import com.radioparadise.tracker.repository.SongRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardTest {
    @Mock SongRepository songs;
    @Mock PlaybackEventRepository plays;
    SongService service;

    @BeforeEach
    void setUp() {
        service = new SongService(songs, plays);
    }

    @Test
    void capsLatestTracksAtFiveHundredAndUsesArchiveCountsForSummaries() {
        Song latest = Song.builder().id(5L).title("Latest track").channel("1").build();
        when(songs.findAllByOrderByLastPlayedDescIdDesc(PageRequest.of(0, 500)))
                .thenReturn(List.of(latest));
        var counts = List.of(
                count("1", "2026", 600), count("1", "1985", 50),
                count("0", "1989", 30), count("2", null, 20));
        when(songs.countTracksByChannelAndYear()).thenReturn(counts);

        var dashboard = service.getDashboard();
        latest.setTitle("Changed entity");

        assertThat(dashboard.latestTracks()).containsExactly(new TrackRecord(
                5L, "1", "Latest track", null, null, null, null, null, null, 1));
        assertThat(dashboard.totalTracks()).isEqualTo(700);
        assertThat(dashboard.tracksByChannel()).isEqualTo(Map.of("0", 30L, "1", 650L, "2", 20L));
        assertThat(dashboard.tracksByDecade()).isEqualTo(Map.of("80s", 80L, "2020s", 600L));
    }

    @Test
    void emptyArchiveReturnsEmptyListsAndZeroTotal() {
        var dashboard = service.getDashboard();

        assertThat(dashboard.latestTracks()).isEmpty();
        assertThat(dashboard.totalTracks()).isZero();
        assertThat(dashboard.tracksByChannel()).isEmpty();
        assertThat(dashboard.tracksByDecade()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("releaseYears")
    void decadeTotalsPreserveBrowserYearGrouping(String year, String decade) {
        var counts = List.of(count("0", year, 1));
        when(songs.countTracksByChannelAndYear()).thenReturn(counts);

        var dashboard = service.getDashboard();

        assertThat(dashboard.totalTracks()).isEqualTo(1);
        assertThat(dashboard.tracksByDecade())
                .isEqualTo(decade == null ? Map.of() : Map.of(decade, 1L));
    }

    private static Stream<Arguments> releaseYears() {
        return Stream.of(
                Arguments.of(null, null), Arguments.of("Unknown", null), Arguments.of("", null),
                Arguments.of("1979", "70s & Earlier"), Arguments.of("1980", "80s"),
                Arguments.of("1989", "80s"), Arguments.of("1990", "90s"),
                Arguments.of("2000", "2000s"), Arguments.of("2010", "2010s"),
                Arguments.of("2020", "2020s"), Arguments.of("2030", "2020s"),
                Arguments.of(" 1999 remastered", "90s"), Arguments.of("9".repeat(400), null));
    }

    private static SongRepository.TrackCount count(String channel, String year, long total) {
        var count = mock(SongRepository.TrackCount.class);
        when(count.getChannel()).thenReturn(channel);
        when(count.getYear()).thenReturn(year);
        when(count.getTrackCount()).thenReturn(total);
        return count;
    }
}
