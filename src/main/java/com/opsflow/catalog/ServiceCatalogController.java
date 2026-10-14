package com.opsflow.catalog;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.TicketPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/service-catalog")
@RequiredArgsConstructor
public class ServiceCatalogController {
    private final ServiceCatalogService catalogService;

    @GetMapping("/items")
    public ApiResponse<List<ServiceCatalogItem>> listItems(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(catalogService.listItems(principal.tenantId(), principal.hasAnyRole("ADMIN")));
    }

    @PostMapping("/items")
    public ApiResponse<ServiceCatalogItem> createItem(@Valid @RequestBody CatalogItemRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(catalogService.createItem(request.toCommand(), principal.id(), principal.tenantId()),
                "服务目录项已创建");
    }

    @PutMapping("/items/{id}")
    public ApiResponse<ServiceCatalogItem> updateItem(@PathVariable Long id,
                                                       @Valid @RequestBody CatalogItemRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(catalogService.updateItem(id, request.toCommand(), principal.id(), principal.tenantId()),
                "服务目录项已更新");
    }

    @PostMapping("/items/{id}/toggle")
    public ApiResponse<ServiceCatalogItem> toggleItem(@PathVariable Long id,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(catalogService.toggleItem(id, principal.id(), principal.tenantId()),
                "服务目录项状态已更新");
    }

    @GetMapping("/requests")
    public ApiResponse<List<ServiceRequest>> listRequests(
            @RequestParam(required = false) ServiceRequestStatus status,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long requesterId = principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
        return ApiResponse.ok(catalogService.listRequests(principal.tenantId(), requesterId, status));
    }

    @PostMapping("/requests")
    public ApiResponse<ServiceRequest> createRequest(
            @Valid @RequestBody CreateServiceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(catalogService.createRequest(request.catalogItemId(), request.summary(), request.detail(),
                idempotencyKey, principal.id(), principal.tenantId()), "服务申请已提交");
    }

    @PostMapping("/requests/{id}/approve")
    public ApiResponse<ServiceRequest> approve(@PathVariable Long id,
                                                @Valid @RequestBody DecisionRequest request,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(catalogService.approve(id, request.comment(), principal.id(), principal.tenantId()),
                "服务申请已通过");
    }

    @PostMapping("/requests/{id}/reject")
    public ApiResponse<ServiceRequest> reject(@PathVariable Long id,
                                               @Valid @RequestBody DecisionRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(catalogService.reject(id, request.comment(), principal.id(), principal.tenantId()),
                "服务申请已驳回");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置服务目录或处理审批");
        }
    }

    public record CatalogItemRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 2000) String description,
            @NotBlank @Size(max = 64) String category,
            @NotNull TicketPriority defaultPriority,
            @NotNull Boolean approvalRequired,
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(9999) Integer sortOrder) {
        ServiceCatalogService.CatalogItemCommand toCommand() {
            return new ServiceCatalogService.CatalogItemCommand(name, description, category,
                    defaultPriority, approvalRequired, enabled, sortOrder);
        }
    }

    public record CreateServiceRequest(
            @NotNull Long catalogItemId,
            @NotBlank @Size(max = 160) String summary,
            @NotBlank @Size(max = 10000) String detail) { }

    public record DecisionRequest(@Size(max = 500) String comment) { }
}
