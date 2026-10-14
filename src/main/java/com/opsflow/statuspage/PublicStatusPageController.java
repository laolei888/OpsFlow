package com.opsflow.statuspage;

import com.opsflow.common.ApiResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status-page")
public class PublicStatusPageController {
    private static final long PUBLIC_TENANT_ID = 1L;
    private final StatusPageService statusPageService;

    public PublicStatusPageController(StatusPageService statusPageService) {
        this.statusPageService = statusPageService;
    }

    @GetMapping
    public ApiResponse<StatusPageService.PublicStatusPage> snapshot() {
        return ApiResponse.ok(statusPageService.publicSnapshot(PUBLIC_TENANT_ID));
    }

    @GetMapping("/incidents/{publicNo}")
    public ApiResponse<StatusPageService.PublicIncident> incident(@PathVariable String publicNo) {
        return ApiResponse.ok(statusPageService.publicIncident(publicNo, PUBLIC_TENANT_ID));
    }
}
