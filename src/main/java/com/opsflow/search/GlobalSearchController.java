package com.opsflow.search;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class GlobalSearchController {
    private final GlobalSearchService searchService;

    public GlobalSearchController(GlobalSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ApiResponse<GlobalSearchView> search(@RequestParam String q,
                                                @RequestParam(defaultValue = "30") Integer limit,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) throw new BusinessException("请先登录后再搜索");
        return ApiResponse.ok(searchService.search(principal.tenantId(), principal.id(),
                principal.hasAnyRole("AGENT", "ADMIN"), q, limit));
    }
}
