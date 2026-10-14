package com.opsflow.alert;

import com.opsflow.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/alert-ingest")
public class AlertIngestController {
    private final AlertService alertService;

    public AlertIngestController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PostMapping("/{sourceKey}")
    public ApiResponse<AlertService.IngestResult> ingest(@PathVariable String sourceKey,
                                                          @RequestHeader("X-OpsFlow-Alert-Key") String ingestKey,
                                                          @Valid @RequestBody IngestRequest request) {
        return ApiResponse.ok(alertService.ingest(sourceKey, ingestKey, request.command()), "告警已接收");
    }

    public record IngestRequest(@NotBlank @Size(max = 128) String fingerprint,
                                @Size(max = 128) String externalAlertId,
                                @NotBlank @Size(max = 160) String title,
                                @Size(max = 10000) String description,
                                @NotNull AlertSeverity severity,
                                AlertSignalState state,
                                Long assetId,
                                Map<String, String> labels) {
        AlertService.IngestCommand command() {
            return new AlertService.IngestCommand(fingerprint, externalAlertId, title, description,
                    severity, state, assetId, labels);
        }
    }
}
