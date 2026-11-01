package com.radioparadise.tracker.scheduler;

import com.radioparadise.tracker.client.RadioParadiseClient;
import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.service.SongService;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

class SongTrackerMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final RadioParadiseClient client = mock(RadioParadiseClient.class);
    private final SongService service = mock(SongService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(RadioParadiseClient.class, () -> client)
            .withBean(SongService.class, () -> service)
            .withBean(MeterRegistry.class, () -> registry)
            .withBean(SongTrackerScheduler.class)
            .withPropertyValues("radioparadise.api.channels=0,1,2");

    @Test
    void exposesNeverSuccessfulChannelsBeforeAnyPollSoStalledStartupCanAlert() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            for (String channel : new String[]{"0", "1", "2"}) {
                assertThat(lastSuccess(channel)).isZero();
                assertThat(polls(channel, "success")).isZero();
                assertThat(polls(channel, "failure")).isZero();
            }
        });
    }

    @Test
    void unchangedSongStillAdvancesSuccessTimestampAndRecordsFullPollDuration() {
        when(client.getCurrentSong("0")).thenAnswer(invocation -> {
            clock.add(Duration.ofMillis(250));
            return Optional.of(song());
        });
        context.withPropertyValues("radioparadise.api.channels=0").run(application -> {
            SongTrackerScheduler scheduler = application.getBean(SongTrackerScheduler.class);
            scheduler.trackCurrentSong();
            double firstSuccess = lastSuccess("0");
            // A duplicate must skip persistence: any accidental second save makes the poll fail.
            when(service.saveOrUpdateSong(any(), eq("0"))).thenThrow(new IllegalStateException("duplicate save"));
            clock.add(Duration.ofSeconds(30));
            scheduler.trackCurrentSong();

            assertThat(lastSuccess("0")).isEqualTo(firstSuccess + 30.25);
            assertThat(polls("0", "success")).isEqualTo(2);
            assertThat(polls("0", "failure")).isZero();
            var timer = registry.get("radioparadise.collector.poll.duration")
                    .tags("channel", "0", "outcome", "success").timer();
            assertThat(timer.count()).isEqualTo(2);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(500);
        });
    }

    @Test
    void emptyAndThrownClientFailuresCountAndDoNotPreventOtherChannelsFromSucceeding() {
        when(client.getCurrentSong("0")).thenReturn(Optional.empty());
        when(client.getCurrentSong("1")).thenThrow(new IllegalStateException("upstream unavailable"));
        when(client.getCurrentSong("2")).thenReturn(Optional.of(song()));
        context.run(application -> {
            application.getBean(SongTrackerScheduler.class).trackCurrentSong();

            assertThat(polls("0", "failure")).isEqualTo(1);
            assertThat(polls("1", "failure")).isEqualTo(1);
            assertThat(lastSuccess("0")).isZero();
            assertThat(lastSuccess("1")).isZero();
            assertThat(polls("2", "success")).isEqualTo(1);
            assertThat(lastSuccess("2")).isPositive();
            assertThat(registry.get("radioparadise.collector.poll.duration")
                    .tags("channel", "0", "outcome", "failure").timer().count()).isEqualTo(1);
        });
    }

    @Test
    void persistenceFailurePreservesLastSuccessAndRetriesTheSameSongOnNextPoll() {
        when(client.getCurrentSong("0")).thenReturn(Optional.of(song()));
        when(service.saveOrUpdateSong(any(), eq("0")))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(null);
        context.withPropertyValues("radioparadise.api.channels=0").run(application -> {
            SongTrackerScheduler scheduler = application.getBean(SongTrackerScheduler.class);
            scheduler.trackCurrentSong();
            assertThat(lastSuccess("0")).isZero();
            assertThat(polls("0", "failure")).isEqualTo(1);
            clock.add(Duration.ofSeconds(30));
            scheduler.trackCurrentSong();
            assertThat(polls("0", "success")).isEqualTo(1);
            assertThat(lastSuccess("0")).isPositive();
        });
    }

    @Test
    void incompleteMetadataWarnsWithoutPersistenceAndRecoversOnNextValidPoll() {
        SongRepository songs = mock(SongRepository.class);
        PlaybackEventRepository plays = mock(PlaybackEventRepository.class);
        SongService realService = new SongService(songs, plays);
        SongTrackerScheduler scheduler = new SongTrackerScheduler(client, realService, registry, "0,1,2");
        RadioParadiseResponse incomplete = song();
        incomplete.setArtist("");
        incomplete.setTitle(null);
        incomplete.setAlbum("The Universe Smiles Upon You ii");
        when(client.getCurrentSong("0")).thenReturn(Optional.of(song()));
        when(client.getCurrentSong("1")).thenReturn(Optional.of(song()));
        when(client.getCurrentSong("2")).thenReturn(Optional.of(incomplete), Optional.of(song()));
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Logger logger = (Logger) LoggerFactory.getLogger(SongTrackerScheduler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            scheduler.trackCurrentSong();
            assertThat(polls("2", "failure")).isEqualTo(1);
            assertThat(polls("2", "success")).isZero();
            assertThat(lastSuccess("2")).isZero();
            assertThat(polls("0", "success")).isEqualTo(1);
            assertThat(polls("1", "success")).isEqualTo(1);
            verify(plays, never()).findFirstByChannelOrderByObservedAtDescIdDesc("2");
            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("channel 2");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);

            clock.add(Duration.ofSeconds(30));
            scheduler.trackCurrentSong();
            assertThat(polls("2", "success")).isEqualTo(1);
            assertThat(lastSuccess("2")).isPositive();
            verify(plays, times(1)).findFirstByChannelOrderByObservedAtDescIdDesc("2");
            verify(songs, times(3)).save(any(Song.class));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void failedPersistenceDoesNotLogSuccessfulTracking() {
        SongRepository songs = mock(SongRepository.class);
        PlaybackEventRepository plays = mock(PlaybackEventRepository.class);
        SongService realService = new SongService(songs, plays);
        SongTrackerScheduler scheduler = new SongTrackerScheduler(client, realService, registry, "2");
        when(client.getCurrentSong("2")).thenReturn(Optional.of(song()));
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(plays.save(any())).thenThrow(new IllegalStateException("transaction failed"));

        Logger logger = (Logger) LoggerFactory.getLogger("com.radioparadise.tracker");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            scheduler.trackCurrentSong();

            assertThat(polls("2", "failure")).isEqualTo(1);
            assertThat(lastSuccess("2")).isZero();
            assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.INFO);
            assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.ERROR);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void missingTitleCannotMatchAPreviouslyTrackedLiteralNullTitle() {
        SongRepository songs = mock(SongRepository.class);
        PlaybackEventRepository plays = mock(PlaybackEventRepository.class);
        SongService realService = new SongService(songs, plays);
        SongTrackerScheduler scheduler = new SongTrackerScheduler(client, realService, registry, "2");
        RadioParadiseResponse valid = song();
        valid.setTitle("null");
        RadioParadiseResponse incomplete = song();
        incomplete.setTitle(null);
        when(client.getCurrentSong("2")).thenReturn(Optional.of(valid), Optional.of(incomplete));
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));

        scheduler.trackCurrentSong();
        double firstSuccess = lastSuccess("2");
        clock.add(Duration.ofSeconds(30));
        scheduler.trackCurrentSong();

        assertThat(polls("2", "failure")).isEqualTo(1);
        assertThat(polls("2", "success")).isEqualTo(1);
        assertThat(lastSuccess("2")).isEqualTo(firstSuccess);
        verify(songs, times(1)).save(any(Song.class));
    }

    private double lastSuccess(String channel) {
        var gauge = registry.find("radioparadise.collector.last.success.timestamp.seconds")
                .tag("channel", channel).gauge();
        assertThat(gauge).as("last successful poll gauge for channel %s", channel).isNotNull();
        return gauge.value();
    }

    private double polls(String channel, String outcome) {
        var counter = registry.find("radioparadise.collector.polls")
                .tags("channel", channel, "outcome", outcome).counter();
        assertThat(counter).as("poll counter for channel %s outcome %s", channel, outcome).isNotNull();
        return counter.count();
    }

    private RadioParadiseResponse song() {
        RadioParadiseResponse song = new RadioParadiseResponse();
        song.setArtist("Artist");
        song.setTitle("Title");
        song.setAlbum("Album");
        song.setYear("2026");
        song.setTime(100);
        song.setCover("https://example.com/cover.jpg");
        song.setCoverMed("https://example.com/medium.jpg");
        song.setCoverSmall("https://example.com/small.jpg");
        return song;
    }
}
