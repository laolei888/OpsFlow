package com.opsflow.statuspage;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/status-center")
public class StatusPageController {
    private final StatusPageService statusPageService;

    public StatusPageController(StatusPageService statusPageService) {
        this.statusPageService = statusPageService;
    }

    @GetMapping
    public ApiResponse<StatusPageService.StatusCenterView> center(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(statusPageService.center(principal.tenantId()));
    }

    @PostMapping("/components")
    public ApiResponse<StatusPageComponent> createComponent(@Valid @RequestBody ComponentRequest request,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(statusPageService.createComponent(request.command(), principal.tenantId(), principal.id()),
                "公开服务组件已创建");
    }

    @PutMapping("/components/{id}")
    public ApiResponse<StatusPageComponent> updateComponent(@PathVariable Long id, @Valid @RequestBody ComponentRequest request,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(statusPageService.updateComponent(id, request.command(), principal.tenantId(), principal.id()),
                "公开服务组件已更新");
    }

    @PostMapping("/incidents")
    public ApiResponse<StatusPageService.IncidentDetail> createIncident(@Valid @RequestBody IncidentRequest request,
                                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(statusPageService.createIncident(request.command(), principal.tenantId(), principal.id()),
                "服务状态公告草稿已创建，需管理员发布后才会对外可见");
    }

    @PostMapping("/incidents/from-incident/{sourceIncidentId}")
    public ApiResponse<StatusPageService.IncidentDetail> createFromIncident(@PathVariable Long sourceIncidentId,
                                                                              @Valid @RequestBody IncidentRequest request,
                                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(statusPageService.createIncident(request.command(sourceIncidentId), principal.tenantId(), principal.id()),
                "已创建与内部事故关联的公开公告草稿；内部内容不会自动公开");
    }

    @PostMapping("/incidents/{id}/updates")
    public ApiResponse<StatusPageService.IncidentDetail> postUpdate(@PathVariable Long id,
                                                                      @Valid @RequestBody UpdateRequest request,
                                                                      @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(statusPageService.updateIncident(id, request.command(), principal.tenantId(), principal.id()),
                request.status() == StatusPageIncidentStatus.RESOLVED ? "服务恢复公告已记录" : "公开进展已记录");
    }

    @PostMapping("/incidents/{id}/publish")
    public ApiResponse<StatusPageService.IncidentDetail> publish(@PathVariable Long id,
                                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(statusPageService.publish(id, principal.tenantId(), principal.id()), "服务状态公告已发布");
    }

    @PostMapping("/incidents/{id}/unpublish")
    public ApiResponse<StatusPageService.IncidentDetail> unpublish(@PathVariable Long id,
                                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(statusPageService.unpublish(id, principal.tenantId(), principal.id()), "服务状态公告已撤回");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理服务状态中心");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置组件或发布公开状态");
        }
    }

    public record ComponentRequest(@NotBlank @Size(max = 80) String slug,
                                   @NotBlank @Size(max = 120) String name,
                                   @Size(max = 1000) String description,
                                   Long assetId, StatusPageComponentStatus status,
                                   @Size(max = 300) String statusMessage,
                                   @Min(0) @Max(9999) Integer displayOrder, Boolean enabled) {
        StatusPageService.ComponentCommand command() {
            return new StatusPageService.ComponentCommand(slug, name, description, assetId, status,
                    statusMessage, displayOrder, enabled);
        }
    }

    public record IncidentRequest(@NotBlank @Size(max = 160) String title,
                                  @NotBlank @Size(max = 2000) String summary,
                                  @NotNull StatusPageIncidentImpact impact,
                                  @NotEmpty List<Long> componentIds,
                                  Long sourceIncidentId) {
        StatusPageService.IncidentCommand command() {
            return new StatusPageService.IncidentCommand(title, summary, impact, componentIds, sourceIncidentId);
        }
        StatusPageService.IncidentCommand command(Long sourceId) {
            return new StatusPageService.IncidentCommand(title, summary, impact, componentIds, sourceId);
        }
    }

    public record UpdateRequest(@NotNull StatusPageIncidentStatus status,
                                @NotBlank @Size(max = 1000) String message) {
        StatusPageService.UpdateCommand command() {
            return new StatusPageService.UpdateCommand(status, message);
        }
    }
}
