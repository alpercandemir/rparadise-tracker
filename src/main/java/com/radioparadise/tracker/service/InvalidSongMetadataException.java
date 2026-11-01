package com.radioparadise.tracker.service;

/** An upstream response cannot represent a persistable song. */
public class InvalidSongMetadataException extends IllegalArgumentException {
    public InvalidSongMetadataException(String field) {
        super(field + " must not be null or blank");
    }
}
