package com.radioparadise.tracker.service;

import com.radioparadise.tracker.repository.SongRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class RecordingBackfillService {
    private enum Selection { MISSING_ISRC, FAILED_LOOKUPS }

    public record Summary(int processed, int withIsrc, int matchedWithoutIsrc,
                          int unresolved, int unavailable, long lastId, long throughId) {}

    private final SongRepository songs;
    private final SongDetailsService details;

    public RecordingBackfillService(SongRepository songs, SongDetailsService details) {
        this.songs = songs;
        this.details = details;
    }

    public Summary run(long afterId, int limit) {
        return scan(Selection.MISSING_ISRC, afterId, limit);
    }

    public Summary retryFailures(int limit) {
        return scan(Selection.FAILED_LOOKUPS, 0, limit);
    }

    private Summary scan(Selection selection, long afterId, int limit) {
        if (afterId < 0 || limit < 1) {
            throw new IllegalArgumentException("Backfill requires a nonnegative after-id and a positive limit");
        }
        long throughId = songs.findHighestId();
        var summary = new Summary(0, 0, 0, 0, 0, afterId, throughId);
        log.info("Recording backfill starting: selection={}, afterId={}, throughId={}, limit={}",
                selection, afterId, throughId, limit);
        while (summary.processed() < limit) {
            var page = PageRequest.of(0, Math.min(50, limit - summary.processed()));
            var ids = selection == Selection.MISSING_ISRC
                    ? songs.findIdsWithoutIsrc(summary.lastId(), throughId, page)
                    : songs.findIdsWithRecordingErrors(summary.lastId(), throughId, page);
            if (ids.isEmpty()) {
                break;
            }
            summary = enrichBatch(ids, summary);
        }
        log.info("Recording backfill finished: {}", summary);
        return summary;
    }

    private Summary enrichBatch(List<Long> ids, Summary previous) {
        int withIsrc = previous.withIsrc();
        int matchedWithoutIsrc = previous.matchedWithoutIsrc();
        int unavailable = previous.unavailable();
        for (long id : ids) {
            var recording = details.get(id).recordingLinks();
            if (recording.isrc() != null) {
                withIsrc++;
            } else if ("matched".equals(recording.status())) {
                matchedWithoutIsrc++;
            }
            if ("unavailable".equals(recording.status())) {
                unavailable++;
            }
            log.info("Recording backfill progress: id={}, status={}, hasIsrc={}",
                    id, recording.status(), recording.isrc() != null);
        }
        int processed = previous.processed() + ids.size();
        return new Summary(processed, withIsrc, matchedWithoutIsrc,
                processed - withIsrc - matchedWithoutIsrc, unavailable,
                ids.getLast(), previous.throughId());
    }
}
