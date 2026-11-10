package com.radioparadise.tracker.service;

import com.radioparadise.tracker.model.RadioParadiseChannel;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public record RecordsQuery(
        int page,
        int size,
        String sort,
        Sort.Direction direction,
        String search,
        String channel,
        LocalDate from,
        LocalDate to,
        ZoneId timeZone) {
    private static final Set<String> TRACK_SORTS =
            Set.of(
                    "id",
                    "title",
                    "artist",
                    "album",
                    "year",
                    "channel",
                    "firstPlayed",
                    "lastPlayed",
                    "playCount");
    private static final Set<String> PLAY_SORTS =
            Set.of("id", "title", "artist", "album", "year", "channel", "playedAt");

    public static RecordsQuery parseLibrary(Map<String, String> parameters) {
        RecordsQuery query = parse(parameters, false);
        if (!query.channel().isEmpty()) {
            throw new IllegalArgumentException("The song library combines all channels");
        }
        if (query.sort().equals("channel")) {
            throw new IllegalArgumentException("Unsupported sort field: channel");
        }
        return query;
    }

    public static RecordsQuery parse(Map<String, String> parameters, boolean history) {
        int page = parseInteger(parameters, "page", 0);
        int size = parseInteger(parameters, "size", 25);
        if (page < 0 || page > 1_000_000) {
            throw new IllegalArgumentException("page must be between 0 and 1000000");
        }
        if (!Set.of(25, 50, 100).contains(size)) {
            throw new IllegalArgumentException("size must be 25, 50, or 100");
        }
        String sort = parseSort(parameters, history);
        Sort.Direction direction = parseDirection(parameters);
        String search = parameters.getOrDefault("q", "").trim();
        if (search.length() > 200) {
            throw new IllegalArgumentException("q must be at most 200 characters");
        }
        String channel = parameters.getOrDefault("channel", "");
        if (!channel.isEmpty()) {
            RadioParadiseChannel.fromId(channel);
        }
        LocalDate from = history ? parseDate(parameters.get("from")) : null;
        LocalDate to = history ? parseDate(parameters.get("to")) : null;
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        ZoneId timeZone = parseTimeZone(parameters);
        return new RecordsQuery(page, size, sort, direction, search, channel, from, to, timeZone);
    }

    private static String parseSort(Map<String, String> parameters, boolean history) {
        String sort = parameters.getOrDefault("sort", history ? "playedAt" : "lastPlayed");
        Set<String> supportedSorts = history ? PLAY_SORTS : TRACK_SORTS;
        if (!supportedSorts.contains(sort)) {
            throw new IllegalArgumentException("Unsupported sort field: " + sort);
        }
        return sort;
    }

    private static Sort.Direction parseDirection(Map<String, String> parameters) {
        String direction = parameters.getOrDefault("direction", "desc");
        if (!Set.of("asc", "desc").contains(direction)) {
            throw new IllegalArgumentException("direction must be asc or desc");
        }
        return Sort.Direction.fromString(direction);
    }

    private static ZoneId parseTimeZone(Map<String, String> parameters) {
        try {
            // Preserve the original API default; the browser explicitly sends its own zone.
            return ZoneId.of(parameters.getOrDefault("timeZone", "Europe/Istanbul"));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "timeZone must be a valid timezone such as UTC or America/New_York");
        }
    }

    public PageRequest pageable(boolean history) {
        String property = sort;
        if (history) {
            property =
                    switch (sort) {
                        case "playedAt" -> "observedAt";
                        case "title", "artist", "album", "year" -> "song." + sort;
                        default -> sort;
                    };
        }
        Sort ordering = Sort.by(direction, property);
        if (!property.equals("id")) {
            ordering = ordering.and(Sort.by(direction, "id"));
        }
        return PageRequest.of(page, size, ordering);
    }

    private static int parseInteger(Map<String, String> parameters, String key, int fallback) {
        try {
            return parameters.containsKey(key) ? Integer.parseInt(parameters.get(key)) : fallback;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1900 || date.getYear() > 9998) {
                throw new IllegalArgumentException("Date year must be between 1900 and 9998");
            }
            return date;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must be valid YYYY-MM-DD values");
        }
    }
}
