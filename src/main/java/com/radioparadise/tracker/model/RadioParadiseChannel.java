package com.radioparadise.tracker.model;

import java.util.Arrays;

public enum RadioParadiseChannel {
    MAIN("0", "Main Mix"),
    ROCK("1", "Rock Mix"),
    MELLOW("2", "Mellow Mix");

    private final String id;
    private final String displayName;

    RadioParadiseChannel(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static RadioParadiseChannel fromId(String id) {
        return Arrays.stream(values())
                .filter(channel -> channel.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported channel id: " + id));
    }
}
