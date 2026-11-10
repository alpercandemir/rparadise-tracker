package com.radioparadise.tracker.dto;

import java.time.Instant;

/** One artist/title/album entry, with listening statistics across all channels. */
public record UnifiedSong(Long id, String title, String artist, String album, String year,
                          String coverUrl, Instant firstPlayed, Instant lastPlayed, long playCount) {}
