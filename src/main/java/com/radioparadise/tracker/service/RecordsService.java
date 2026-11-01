package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.PlaybackRecord;
import com.radioparadise.tracker.dto.RecordsPage;
import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import com.radioparadise.tracker.repository.SongRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordsService {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Istanbul");
    private final SongRepository songs;
    private final PlaybackEventRepository plays;

    public RecordsPage<TrackRecord> tracks(RecordsQuery query) {
        Specification<Song> filter = (root, criteria, cb) -> {
            List<Predicate> predicates = filters(query, root, root.get("channel"), cb);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return RecordsPage.from(songs.findAll(filter, query.pageable(false)).map(TrackRecord::from), null);
    }

    public RecordsPage<PlaybackRecord> plays(RecordsQuery query) {
        Specification<PlaybackEvent> filter = (root, criteria, cb) -> {
            List<Predicate> predicates = filters(query, root.get("song"), root.get("channel"), cb);
            if (query.from() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("observedAt"),
                    query.from().atStartOfDay(DISPLAY_ZONE).toInstant()));
            if (query.to() != null) predicates.add(cb.lessThan(root.get("observedAt"),
                    query.to().plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var page = plays.findAll(filter, query.pageable(true)).map(PlaybackRecord::from);
        var started = plays.findFirstByOrderByObservedAtAscIdAsc().map(PlaybackEvent::getObservedAt).orElse(null);
        return RecordsPage.from(page, started);
    }

    private List<Predicate> filters(RecordsQuery query, Path<?> song, Path<String> channel, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        if (!query.channel().isEmpty()) predicates.add(cb.equal(channel, query.channel()));
        if (!query.search().isEmpty()) {
            String literal = query.search().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + literal + "%";
            predicates.add(cb.or(cb.like(cb.lower(song.get("title")), pattern, '\\'),
                    cb.like(cb.lower(song.get("artist")), pattern, '\\'),
                    cb.like(cb.lower(song.get("album")), pattern, '\\')));
        }
        return predicates;
    }
}
