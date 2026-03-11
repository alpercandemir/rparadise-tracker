package com.radioparadise.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RadioParadiseTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RadioParadiseTrackerApplication.class, args);
    }
}
