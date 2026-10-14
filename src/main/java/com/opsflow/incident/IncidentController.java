package com.opsflow.incident;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {
    private final IncidentService incidentService;

    @GetMapping
    public ApiResponse<List<Incident>> list(@RequestParam(required = false) IncidentStatus status,
                                            @RequestParam(required = false) IncidentSeverity severity,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "100") Integer limit,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.list(principal.tenantId(), status, severity, keyword, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<IncidentService.IncidentDetail> detail(@PathVariable Long id,
                                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.detail(id, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<Incident> create(@Valid @RequestBody IncidentRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.create(request.command(), idempotencyKey, principal.id(), principal.tenantId()),
                "重大事件已宣布");
    }

    @PutMapping("/{id}")
    public ApiResponse<Incident> update(@PathVariable Long id, @Valid @RequestBody IncidentCommandRequest request,
                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.update(id, request.command(), principal.id(), principal.tenantId()),
                "重大事件指挥资料已更新");
    }

    @PostMapping("/{id}/transition")
    public ApiResponse<Incident> transition(@PathVariable Long id, @RequestParam IncidentStatus target,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.transition(id, target, principal.id(), principal.tenantId()),
                "重大事件状态已更新");
    }

    @PostMapping("/{id}/updates")
    public ApiResponse<IncidentUpdate> addUpdate(@PathVariable Long id, @Valid @RequestBody UpdateRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.addUpdate(id, request.command(), principal.id(), principal.tenantId()),
                "事件动态已发布");
    }

    @PostMapping("/{id}/tasks")
    public ApiResponse<IncidentTask> createTask(@PathVariable Long id, @Valid @RequestBody TaskRequest request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.createTask(id, request.command(), principal.id(), principal.tenantId()),
                "事件协同任务已添加");
    }

    @PutMapping("/tasks/{taskId}")
    public ApiResponse<IncidentTask> updateTask(@PathVariable Long taskId, @Valid @RequestBody TaskUpdateRequest request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.updateTask(taskId, request.command(), principal.id(), principal.tenantId()),
                "事件协同任务已更新");
    }

    @PostMapping("/{id}/tickets")
    public ApiResponse<IncidentService.IncidentTicketView> linkTicket(@PathVariable Long id,
                                                                        @Valid @RequestBody LinkRequest request,
                                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.linkTicket(id, request.command(), principal.id(), principal.tenantId()),
                "事件工单已关联");
    }

    @DeleteMapping("/{id}/tickets/{ticketId}")
    public ApiResponse<Void> unlinkTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        incidentService.unlinkTicket(id, ticketId, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "事件工单关联已解除");
    }

    @PostMapping("/{id}/assets")
    public ApiResponse<IncidentService.IncidentAssetView> linkAsset(@PathVariable Long id,
                                                                      @Valid @RequestBody LinkRequest request,
                                                                      @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(incidentService.linkAsset(id, request.command(), principal.id(), principal.tenantId()),
                "受影响配置项已关联");
    }

    @DeleteMapping("/{id}/assets/{assetId}")
    public ApiResponse<Void> unlinkAsset(@PathVariable Long id, @PathVariable Long assetId,
                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        incidentService.unlinkAsset(id, assetId, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "事件配置项关联已解除");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理重大事件");
        }
    }

    public record IncidentRequest(@NotBlank @Size(max = 160) String title,
                                  @NotBlank @Size(max = 10000) String summary,
                                  @NotNull IncidentSeverity severity,
                                  Long commanderId,
                                  @Size(max = 1000) String businessImpact,
                                  @Size(max = 1000) String customerImpact,
                                  @Size(max = 200) String communicationChannel,
                                  @Size(max = 30) List<@Size(max = 160) String> taskTitles) {
        IncidentService.IncidentCommand command() {
            return new IncidentService.IncidentCommand(title, summary, severity, commanderId, businessImpact,
                    customerImpact, communicationChannel, taskTitles);
        }
    }

    public record IncidentCommandRequest(IncidentSeverity severity, Long commanderId,
                                         @Size(max = 1000) String businessImpact,
                                         @Size(max = 1000) String customerImpact,
                                         @Size(max = 200) String communicationChannel,
                                         @Size(max = 10000) String postmortem) {
        IncidentService.IncidentUpdateCommand command() {
            return new IncidentService.IncidentUpdateCommand(severity, commanderId, businessImpact, customerImpact,
                    communicationChannel, postmortem);
        }
    }

    public record UpdateRequest(IncidentUpdateType updateType,
                                @NotBlank @Size(max = 10000) String content) {
        IncidentService.UpdateCommand command() { return new IncidentService.UpdateCommand(updateType, content); }
    }

    public record TaskRequest(@NotBlank @Size(max = 160) String title, Long assigneeId,
                              @Min(0) @Max(9999) Integer sortOrder) {
        IncidentService.TaskCommand command() { return new IncidentService.TaskCommand(title, assigneeId, sortOrder); }
    }

    public record TaskUpdateRequest(@Size(max = 160) String title, Long assigneeId, IncidentTaskStatus status) {
        IncidentService.TaskUpdateCommand command() {
            return new IncidentService.TaskUpdateCommand(title, assigneeId, status);
        }
    }

    public record LinkRequest(@NotNull Long targetId, @Size(max = 32) String linkType,
                              @Size(max = 500) String note) {
        IncidentService.LinkCommand command() { return new IncidentService.LinkCommand(targetId, linkType, note); }
    }
}
