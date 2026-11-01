package com.radioparadise.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RadioParadiseTrackerApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(RadioParadiseTrackerApplication.class, args);
        if (context.getEnvironment().acceptsProfiles(Profiles.of("recording-backfill"))) {
            SpringApplication.exit(context);
        }
    }
}
