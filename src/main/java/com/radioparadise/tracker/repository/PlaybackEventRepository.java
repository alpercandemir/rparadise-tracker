package com.radioparadise.tracker.repository;

import com.radioparadise.tracker.model.PlaybackEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface PlaybackEventRepository extends JpaRepository<PlaybackEvent, Long>, JpaSpecificationExecutor<PlaybackEvent> {
    @Override
    @EntityGraph(attributePaths = "song")
    Page<PlaybackEvent> findAll(Specification<PlaybackEvent> specification, Pageable pageable);

    @EntityGraph(attributePaths = "song")
    Optional<PlaybackEvent> findFirstByChannelOrderByObservedAtDescIdDesc(String channel);

    Optional<PlaybackEvent> findFirstByOrderByObservedAtAscIdAsc();
}
