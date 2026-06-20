package com.srelab.applicationservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    private final ApplicationSettings settings;

    public ApplicationController(ApplicationSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(Map.of(
                "environment", settings.environment(),
                "owner", settings.owner(),
                "defaultCustomerSegment", settings.defaultCustomerSegment(),
                "slowEndpointEnabled", settings.slowEndpointEnabled(),
                "defaultSlowDelayMs", settings.defaultSlowDelayMs(),
                "service", "application-service",
                "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/applications/{id}")
    public ResponseEntity<Map<String, Object>> getApplication(@PathVariable String id) {
        log.debug("Loading application id={}", id);

        return ResponseEntity.ok(Map.of(
                "id", id,
                "status", "CREATED",
                "customerSegment", settings.defaultCustomerSegment(),
                "createdAt", Instant.now().toString(),
                "environment", settings.environment(),
                "service", "application-service"
        ));
    }

    @PostMapping("/applications")
    public ResponseEntity<Map<String, Object>> createApplication() {
        String id = UUID.randomUUID().toString();

        log.info("Created application id={} segment={}", id, settings.defaultCustomerSegment());

        return ResponseEntity.ok(Map.of(
                "id", id,
                "status", "CREATED",
                "customerSegment", settings.defaultCustomerSegment(),
                "createdAt", Instant.now().toString(),
                "environment", settings.environment(),
                "service", "application-service"
        ));
    }

    @GetMapping("/failure/500")
    public ResponseEntity<Map<String, Object>> failWith500() {
        log.warn("Intentional HTTP 500 endpoint was called");
        throw new IllegalStateException("Intentional failure for SRE diagnostics");
    }

    @GetMapping("/failure/slow")
    public ResponseEntity<Map<String, Object>> slow(@RequestParam(required = false) Long delayMs)
            throws InterruptedException {

        if (!settings.slowEndpointEnabled()) {
            return ResponseEntity.status(403).body(Map.of(
                    "status", "SLOW_ENDPOINT_DISABLED",
                    "service", "application-service"
            ));
        }

        long effectiveDelayMs = delayMs != null ? delayMs : settings.defaultSlowDelayMs();

        log.info("Slow endpoint called delayMs={}", effectiveDelayMs);

        Thread.sleep(effectiveDelayMs);

        return ResponseEntity.ok(Map.of(
                "status", "SLOW_RESPONSE",
                "delayMs", effectiveDelayMs,
                "environment", settings.environment(),
                "service", "application-service"
        ));
    }
}
