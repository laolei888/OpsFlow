package com.opsflow.alert;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/alert-center")
public class AlertCenterController {
    private final AlertService alertService;
    private final AlertConfigService configService;

    public AlertCenterController(AlertService alertService, AlertConfigService configService) {
        this.alertService = alertService;
        this.configService = configService;
    }

    @GetMapping("/alerts")
    public ApiResponse<List<MonitoringAlert>> alerts(@RequestParam(required = false) AlertStatus status,
                                                      @RequestParam(required = false) AlertSeverity severity,
                                                      @RequestParam(required = false) Long sourceId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "150") Integer limit,
                                                      @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(alertService.list(principal.tenantId(), status, severity, sourceId, keyword, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<AlertService.AlertSummary> summary(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(alertService.summary(principal.tenantId()));
    }

    @GetMapping("/alerts/{id}")
    public ApiResponse<AlertService.AlertDetail> detail(@PathVariable Long id,
                                                         @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(alertService.detail(id, principal.tenantId()));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ApiResponse<MonitoringAlert> acknowledge(@PathVariable Long id,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(alertService.acknowledge(id, principal.id(), principal.tenantId()), "告警已确认");
    }

    @PostMapping("/alerts/{id}/suppress")
    public ApiResponse<MonitoringAlert> suppress(@PathVariable Long id, @Valid @RequestBody NoteRequest request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(alertService.suppress(id, request.note(), principal.id(), principal.tenantId()),
                "告警已抑制");
    }

    @PostMapping("/alerts/{id}/resolve")
    public ApiResponse<MonitoringAlert> resolve(@PathVariable Long id, @RequestBody(required = false) NoteRequest request,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(alertService.resolve(id, request == null ? null : request.note(), principal.id(),
                principal.tenantId()), "告警已解决");
    }

    @PostMapping("/alerts/{id}/reopen")
    public ApiResponse<MonitoringAlert> reopen(@PathVariable Long id, @RequestBody(required = false) NoteRequest request,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(alertService.reopen(id, request == null ? null : request.note(), principal.id(),
                principal.tenantId()), "告警已重新打开");
    }

    @GetMapping("/sources")
    public ApiResponse<List<AlertSource>> sources(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(configService.listSources(principal.tenantId()));
    }

    @PostMapping("/sources")
    public ApiResponse<AlertConfigService.SourceCredential> createSource(@Valid @RequestBody SourceRequest request,
                                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(configService.createSource(request.command(), principal.id(), principal.tenantId()),
                "告警源已创建，请立即保存一次性接入密钥");
    }

    @PutMapping("/sources/{id}")
    public ApiResponse<AlertSource> updateSource(@PathVariable Long id, @Valid @RequestBody SourceRequest request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(configService.updateSource(id, request.command(), principal.id(), principal.tenantId()),
                "告警源已更新");
    }

    @PostMapping("/sources/{id}/rotate-key")
    public ApiResponse<AlertConfigService.SourceCredential> rotateSourceKey(@PathVariable Long id,
                                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(configService.rotateSourceKey(id, principal.id(), principal.tenantId()),
                "接入密钥已轮换，请立即保存；旧密钥已失效");
    }

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> rules(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(configService.listRules(principal.tenantId()));
    }

    @PostMapping("/rules")
    public ApiResponse<AlertRule> createRule(@Valid @RequestBody RuleRequest request,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(configService.createRule(request.command(), principal.id(), principal.tenantId()),
                "告警自动化规则已创建");
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @Valid @RequestBody RuleRequest request,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(configService.updateRule(id, request.command(), principal.id(), principal.tenantId()),
                "告警自动化规则已更新");
    }

    @GetMapping("/silences")
    public ApiResponse<List<AlertSilence>> silences(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(configService.listSilences(principal.tenantId()));
    }

    @PostMapping("/silences")
    public ApiResponse<AlertSilence> createSilence(@Valid @RequestBody SilenceRequest request,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(configService.createSilence(request.command(), principal.id(), principal.tenantId()),
                "告警静默窗口已创建");
    }

    @PutMapping("/silences/{id}")
    public ApiResponse<AlertSilence> updateSilence(@PathVariable Long id, @Valid @RequestBody SilenceRequest request,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(configService.updateSilence(id, request.command(), principal.id(), principal.tenantId()),
                "告警静默窗口已更新");
    }

    @DeleteMapping("/silences/{id}")
    public ApiResponse<Void> deleteSilence(@PathVariable Long id,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        configService.deleteSilence(id, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "告警静默窗口已删除");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以访问监控告警中心");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置监控告警中心");
        }
    }

    public record NoteRequest(@Size(max = 1000) String note) { }

    public record SourceRequest(@NotBlank @Size(max = 100) String name,
                                @Size(max = 1000) String description,
                                Long defaultAssetId, Boolean enabled) {
        AlertConfigService.SourceCommand command() {
            return new AlertConfigService.SourceCommand(name, description, defaultAssetId, enabled);
        }
    }

    public record RuleRequest(@NotBlank @Size(max = 100) String name,
                              @Size(max = 1000) String description,
                              Long sourceId, AlertSeverity minimumSeverity,
                              @NotNull AlertRuleAction action, Long scheduleId,
                              @Min(0) @Max(9999) Integer sortOrder, Boolean enabled) {
        AlertConfigService.RuleCommand command() {
            return new AlertConfigService.RuleCommand(name, description, sourceId, minimumSeverity, action,
                    scheduleId, sortOrder, enabled);
        }
    }

    public record SilenceRequest(@NotBlank @Size(max = 100) String name,
                                 Long sourceId, Long assetId,
                                 @Size(max = 128) String fingerprintPrefix,
                                 @NotNull LocalDateTime startsAt,
                                 @NotNull LocalDateTime endsAt,
                                 @NotBlank @Size(max = 1000) String reason,
                                 Boolean enabled) {
        AlertConfigService.SilenceCommand command() {
            return new AlertConfigService.SilenceCommand(name, sourceId, assetId, fingerprintPrefix,
                    startsAt, endsAt, reason, enabled);
        }
    }
}
