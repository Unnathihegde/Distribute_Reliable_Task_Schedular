package com.scheduler.scheduler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration for enabling scheduled execution and binding custom properties.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfig {
}
