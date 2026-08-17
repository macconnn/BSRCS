package com.baseball.score;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BaseballScoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(BaseballScoreApplication.class, args);
    }
}
