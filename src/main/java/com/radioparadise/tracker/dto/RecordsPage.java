package com.radioparadise.tracker.dto;

import org.springframework.data.domain.Page;
import java.time.Instant;
import java.util.List;

public record RecordsPage<T>(List<T> items, int page, int size, long totalElements,
                             int totalPages, Instant historyStartedAt) {
    public static <T> RecordsPage<T> from(Page<T> page, Instant historyStartedAt) {
        return new RecordsPage<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), historyStartedAt);
    }
}
