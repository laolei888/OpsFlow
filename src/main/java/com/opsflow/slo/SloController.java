package com.opsflow.slo;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/reliability")
public class SloController {
    private final SloService sloService;

    public SloController(SloService sloService) {
        this.sloService = sloService;
    }

    @GetMapping
    public ApiResponse<SloService.ReliabilityCenterView> center(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(sloService.center(principal.tenantId()));
    }

    @GetMapping("/definitions/{id}")
    public ApiResponse<SloService.DefinitionDetail> detail(@PathVariable Long id,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(sloService.detail(id, principal.tenantId()));
    }

    @PostMapping("/definitions")
    public ApiResponse<SloDefinition> createDefinition(@Valid @RequestBody DefinitionRequest request,
                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(sloService.createDefinition(request.command(), principal.tenantId(), principal.id()),
                "SLO 已创建");
    }

    @PutMapping("/definitions/{id}")
    public ApiResponse<SloDefinition> updateDefinition(@PathVariable Long id, @Valid @RequestBody DefinitionRequest request,
                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(sloService.updateDefinition(id, request.command(), principal.tenantId(), principal.id()),
                "SLO 已更新");
    }

    @PostMapping("/definitions/{id}/measurements")
    public ApiResponse<SloService.MeasurementResult> recordMeasurement(@PathVariable Long id,
                                                                         @Valid @RequestBody MeasurementRequest request,
                                                                         @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(sloService.recordMeasurement(id, request.command(), principal.tenantId(), principal.id()),
                "SLI 样本已记录，错误预算已重新计算");
    }

    @PostMapping("/breaches/{id}/acknowledge")
    public ApiResponse<SloBreachEvent> acknowledge(@PathVariable Long id,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(sloService.acknowledge(id, principal.tenantId(), principal.id()), "预算违规已确认");
    }

    @PostMapping("/breaches/{id}/resolve")
    public ApiResponse<SloBreachEvent> resolve(@PathVariable Long id,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(sloService.resolve(id, principal.tenantId(), principal.id()), "预算违规已关闭");
    }

    @PostMapping("/breaches/{id}/link-ticket/{ticketId}")
    public ApiResponse<SloBreachEvent> linkTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(sloService.linkTicket(id, ticketId, principal.tenantId(), principal.id()), "已关联处置工单");
    }

    @PostMapping("/breaches/{id}/link-incident/{incidentId}")
    public ApiResponse<SloBreachEvent> linkIncident(@PathVariable Long id, @PathVariable Long incidentId,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(sloService.linkIncident(id, incidentId, principal.tenantId(), principal.id()), "已关联重大事件");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以访问可靠性中心");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置 SLO");
        }
    }

    public record DefinitionRequest(@NotBlank @Size(max = 120) String name,
                                    @Size(max = 1000) String description,
                                    Long statusComponentId,
                                    @NotNull @DecimalMin("90.000") @DecimalMax("100.000") BigDecimal targetPercent,
                                    @NotNull @Min(1) @Max(90) Integer windowDays,
                                    @NotNull @DecimalMin("0.000") @DecimalMax("100.000") BigDecimal warningBudgetPercent,
                                    @NotNull @DecimalMin("0.000") @DecimalMax("100.000") BigDecimal criticalBudgetPercent,
                                    Boolean enabled) {
        SloService.DefinitionCommand command() {
            return new SloService.DefinitionCommand(name, description, statusComponentId, targetPercent, windowDays,
                    warningBudgetPercent, criticalBudgetPercent, enabled);
        }
    }

    public record MeasurementRequest(@NotNull @Min(1) Long totalRequests,
                                     @NotNull @Min(0) Long goodRequests,
                                     Long sourceAlertId, LocalDateTime occurredAt,
                                     @Size(max = 1000) String note) {
        SloService.MeasurementCommand command() {
            return new SloService.MeasurementCommand(totalRequests, goodRequests, sourceAlertId, occurredAt, note);
        }
    }
}
