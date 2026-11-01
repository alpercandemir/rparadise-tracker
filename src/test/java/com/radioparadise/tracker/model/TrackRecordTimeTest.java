package com.radioparadise.tracker.model;

import com.radioparadise.tracker.dto.TrackRecord;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrackRecordTimeTest {
    @Test
    void preservesAbsoluteTimesWithoutApplyingAnotherTimezoneConversion() {
        Song song = Song.builder().firstPlayed(Instant.parse("2026-10-31T21:30:00Z"))
                .lastPlayed(Instant.parse("2026-11-01T09:00:00Z")).build();
        TrackRecord record = TrackRecord.from(song);
        assertThat(record.firstPlayed().toString()).isEqualTo("2026-10-31T21:30:00Z");
        assertThat(record.lastPlayed().toString()).isEqualTo("2026-11-01T09:00:00Z");
        assertThat(song.getFirstPlayed()).isEqualTo(Instant.parse("2026-10-31T21:30:00Z"));
    }
}
