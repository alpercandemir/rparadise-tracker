package com.radioparadise.tracker.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RadioParadiseResponse {

    private Integer time;
    private String artist;
    private String title;
    private String album;
    private String year;
    private String cover;

    @JsonProperty("cover_med")
    private String coverMed;

    @JsonProperty("cover_small")
    private String coverSmall;
}
