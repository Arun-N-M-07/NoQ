package com.noq.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A second, independent Spring Boot application (a "microservice").
 * It runs on its own port (8081), has its own pom.xml and shares no code or database
 * with the main NoQ application. The two talk only over HTTP + JSON.
 */
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
