package com.srelab.applicationservice;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ApplicationController {

    @GetMapping("/applications/{id}")
    public ResponseEntity<Map<String, Object>> getApplication(@PathVariable String id) {
        return ResponseEntity.ok(Map.of(
                "id", id,
                "status", "CREATED",
                "customerSegment", "STANDARD",
                "createdAt", Instant.now().toString(),
                "service", "application-service"
        ));
    }

    @PostMapping("/applications")
    public ResponseEntity<Map<String, Object>> createApplication() {
        String id = UUID.randomUUID().toString();

        return ResponseEntity.ok(Map.of(
                "id", id,
                "status", "CREATED",
                "createdAt", Instant.now().toString(),
                "service", "application-service"
        ));
    }

    @GetMapping("/failure/500")
    public ResponseEntity<Map<String, Object>> failWith500() {
        throw new IllegalStateException("Intentional failure for SRE diagnostics");
    }

    @GetMapping("/failure/slow")
    public ResponseEntity<Map<String, Object>> slow(@RequestParam(defaultValue = "3000") long delayMs)
            throws InterruptedException {

        Thread.sleep(delayMs);

        return ResponseEntity.ok(Map.of(
                "status", "SLOW_RESPONSE",
                "delayMs", delayMs,
                "service", "application-service"
        ));
 }
}

