package com.srelab.applicationservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record ApplicationSettings(
        String environment,
        String owner,
        String defaultCustomerSegment,
        boolean slowEndpointEnabled,
        long defaultSlowDelayMs
) {
}
