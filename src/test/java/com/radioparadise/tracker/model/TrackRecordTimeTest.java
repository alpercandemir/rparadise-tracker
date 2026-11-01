package com.radioparadise.tracker.model;

import com.radioparadise.tracker.dto.TrackRecord;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TrackRecordTimeTest {
    @Test
    void convertsLegacyIstanbulWallTimesToUtcWithoutChangingTheStoredValues() {
        Song song = Song.builder().firstPlayed(LocalDateTime.of(2026, 11, 1, 0, 30))
                .lastPlayed(LocalDateTime.of(2026, 11, 1, 12, 0)).build();
        TrackRecord record = TrackRecord.from(song);
        assertThat(record.firstPlayed().toString()).isEqualTo("2026-10-31T21:30:00Z");
        assertThat(record.lastPlayed().toString()).isEqualTo("2026-11-01T09:00:00Z");
        assertThat(song.getFirstPlayed()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 30));
    }
}
