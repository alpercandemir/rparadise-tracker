package com.radioparadise.tracker.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RadioParadiseChannelTest {

    @Test
    void resolvesMellowFromRadioParadiseChannelOne() {
        assertThat(RadioParadiseChannel.fromId("1")).isEqualTo(RadioParadiseChannel.MELLOW);
        assertThat(RadioParadiseChannel.MELLOW.id()).isEqualTo("1");
    }

    @Test
    void resolvesRockFromRadioParadiseChannelTwo() {
        assertThat(RadioParadiseChannel.fromId("2")).isEqualTo(RadioParadiseChannel.ROCK);
        assertThat(RadioParadiseChannel.ROCK.id()).isEqualTo("2");
    }
}
