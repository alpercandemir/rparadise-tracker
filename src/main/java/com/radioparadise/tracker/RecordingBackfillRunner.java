package com.radioparadise.tracker;

import com.radioparadise.tracker.service.RecordingBackfillService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("recording-backfill")
public class RecordingBackfillRunner implements ApplicationRunner {
    private final RecordingBackfillService backfill;
    private final long afterId;
    private final int limit;
    private final boolean retryErrorsOnly;

    public RecordingBackfillRunner(RecordingBackfillService backfill,
                                   @Value("${recording.backfill.after-id:0}") long afterId,
                                   @Value("${recording.backfill.limit:10000}") int limit,
                                   @Value("${recording.backfill.retry-errors-only:false}") boolean retryErrorsOnly) {
        this.backfill = backfill;
        this.afterId = afterId;
        this.limit = limit;
        this.retryErrorsOnly = retryErrorsOnly;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (retryErrorsOnly) {
            backfill.retryFailures(limit);
            return;
        }
        backfill.run(afterId, limit);
    }
}
