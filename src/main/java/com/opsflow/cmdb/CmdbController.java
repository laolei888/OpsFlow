package com.opsflow.cmdb;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cmdb")
@RequiredArgsConstructor
public class CmdbController {
    private final CmdbAssetService cmdbAssetService;
    private final com.opsflow.ticket.TicketService ticketService;

    @GetMapping("/assets")
    public ApiResponse<List<CmdbAsset>> listAssets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) AssetType assetType,
            @RequestParam(required = false) AssetStatus status,
            @RequestParam(required = false) AssetCriticality criticality,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(cmdbAssetService.list(principal.tenantId(), keyword, assetType, status, criticality,
                principal.hasAnyRole("AGENT", "ADMIN"), limit));
    }

    @GetMapping("/assets/{id}")
    public ApiResponse<CmdbAsset> getAsset(@PathVariable Long id,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(cmdbAssetService.get(id, principal.tenantId()));
    }

    @PostMapping("/assets")
    public ApiResponse<CmdbAsset> createAsset(@Valid @RequestBody AssetRequest request,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(cmdbAssetService.create(request.command(), principal.tenantId(), principal.id()),
                "资产已登记");
    }

    @PutMapping("/assets/{id}")
    public ApiResponse<CmdbAsset> updateAsset(@PathVariable Long id,
                                              @Valid @RequestBody AssetRequest request,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(cmdbAssetService.update(id, request.command(), principal.tenantId(), principal.id()),
                "资产信息已更新");
    }

    @GetMapping("/assets/{id}/relations")
    public ApiResponse<List<CmdbAssetService.AssetRelationView>> listRelations(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(cmdbAssetService.relations(id, principal.tenantId()));
    }

    @PostMapping("/assets/{id}/relations")
    public ApiResponse<CmdbAssetService.AssetRelationView> createRelation(
            @PathVariable Long id, @Valid @RequestBody RelationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(cmdbAssetService.createRelation(id, request.command(), principal.tenantId(), principal.id()),
                "资产关系已建立");
    }

    @DeleteMapping("/relations/{relationId}")
    public ApiResponse<Void> deleteRelation(@PathVariable Long relationId,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        cmdbAssetService.deleteRelation(relationId, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "资产关系已解除");
    }

    @GetMapping("/assets/{id}/impact")
    public ApiResponse<CmdbAssetService.ImpactView> impact(@PathVariable Long id,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(cmdbAssetService.impact(id, principal.tenantId()));
    }

    @GetMapping("/tickets/{ticketId}/assets")
    public ApiResponse<List<CmdbAssetService.TicketAssetView>> ticketAssets(
            @PathVariable Long ticketId, @AuthenticationPrincipal AuthPrincipal principal) {
        Long requesterId = principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
        ticketService.getVisible(ticketId, principal.tenantId(), requesterId);
        return ApiResponse.ok(cmdbAssetService.ticketAssets(ticketId, principal.tenantId()));
    }

    @PostMapping("/tickets/{ticketId}/assets")
    public ApiResponse<CmdbAssetService.TicketAssetView> linkTicketAsset(
            @PathVariable Long ticketId, @Valid @RequestBody TicketAssetRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(cmdbAssetService.linkTicketAsset(ticketId, request.command(), principal.tenantId(), principal.id()),
                "配置项已关联到工单");
    }

    @DeleteMapping("/tickets/{ticketId}/assets/{assetId}")
    public ApiResponse<Void> unlinkTicketAsset(@PathVariable Long ticketId, @PathVariable Long assetId,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        cmdbAssetService.unlinkTicketAsset(ticketId, assetId, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "工单资产关联已解除");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理配置项");
        }
    }

    public record AssetRequest(
            @NotBlank @Size(max = 40) String assetNo,
            @NotBlank @Size(max = 120) String name,
            @NotNull AssetType assetType,
            @NotNull AssetEnvironment environment,
            @NotNull AssetStatus status,
            @NotNull AssetCriticality criticality,
            Long ownerId,
            @Size(max = 120) String businessSystem,
            @Size(max = 120) String location,
            @Size(max = 1000) String description,
            @Size(max = 4000) String metadataJson) {
        CmdbAssetService.AssetCommand command() {
            return new CmdbAssetService.AssetCommand(assetNo, name, assetType, environment, status, criticality,
                    ownerId, businessSystem, location, description, metadataJson);
        }
    }

    public record RelationRequest(@NotNull Long targetAssetId, @NotNull AssetRelationType relationType,
                                  @Size(max = 500) String description) {
        CmdbAssetService.RelationCommand command() {
            return new CmdbAssetService.RelationCommand(targetAssetId, relationType, description);
        }
    }

    public record TicketAssetRequest(@NotNull Long assetId, @Size(max = 32) String linkType,
                                     @Size(max = 500) String note) {
        CmdbAssetService.TicketAssetCommand command() {
            return new CmdbAssetService.TicketAssetCommand(assetId, linkType, note);
        }
    }
}
