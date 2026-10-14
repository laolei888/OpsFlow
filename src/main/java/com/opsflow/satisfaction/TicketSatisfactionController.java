package com.opsflow.satisfaction;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/satisfaction")
@RequiredArgsConstructor
public class TicketSatisfactionController {
    private final TicketSatisfactionService satisfactionService;

    @GetMapping("/my-surveys")
    public ApiResponse<List<TicketSatisfactionService.SurveyItem>> mySurveys(
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(satisfactionService.mySurveys(principal.id(), principal.tenantId(), limit));
    }

    @PostMapping("/tickets/{ticketId}")
    public ApiResponse<TicketSatisfactionService.SurveyItem> submit(
            @PathVariable Long ticketId,
            @Valid @RequestBody SubmitSatisfactionRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(satisfactionService.submit(ticketId, request.rating(), request.tags(),
                request.comment(), principal.id(), principal.tenantId()), "感谢你的服务评价");
    }

    @GetMapping("/analytics")
    public ApiResponse<TicketSatisfactionService.AnalyticsSummary> analytics(
            @RequestParam(defaultValue = "30") Integer days,
            @RequestParam(defaultValue = "30") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以查看服务质量分析");
        }
        return ApiResponse.ok(satisfactionService.analytics(principal.tenantId(), days, limit));
    }

    public record SubmitSatisfactionRequest(
            @Min(1) @Max(5) Integer rating,
            @Size(max = 5) List<String> tags,
            @Size(max = 2000) String comment) { }
}
