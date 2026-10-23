package com.consense;

import com.consense.config.ConsenseProperties;
import com.consense.config.H2LocalFileAccess;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(ConsenseProperties.class)
public class ConSenseApplication {

    public static void main(String[] args) {
        if (Boolean.getBoolean("consense.h2.local-file-compat")) H2LocalFileAccess.register();
        SpringApplication.run(ConSenseApplication.class, args);
    }
}
