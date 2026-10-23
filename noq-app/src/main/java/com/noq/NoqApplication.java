package com.noq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Entry point of the main NoQ application.
 * <p>
 * {@code @SpringBootApplication} = {@code @Configuration} + {@code @EnableAutoConfiguration} + {@code @ComponentScan}.
 * Spring scans the com.noq package, creates a bean for every {@code @Controller}, {@code @Service},
 * {@code @Repository} and {@code @Component} it finds, and injects them into each other through constructors.
 * {@code @EnableCaching} switches on Spring's cache abstraction (backed by Caffeine, see application.properties).
 */
@SpringBootApplication
@EnableCaching
public class NoqApplication {
    public static void main(String[] args) {
        SpringApplication.run(NoqApplication.class, args);
    }
}
