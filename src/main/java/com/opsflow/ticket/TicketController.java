package com.opsflow.ticket;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsflow.auth.AuthPrincipal;
import com.opsflow.auth.AuthService;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import com.opsflow.supportgroup.SupportGroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {
    private final TicketService ticketService;
    private final AuthService authService;
    private final SupportGroupService supportGroupService;

    @GetMapping
    public ApiResponse<Page<Ticket>> page(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) TicketStatus status,
                                          @RequestParam(required = false) TicketPriority priority,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "false") boolean overdue,
                                          @RequestParam(defaultValue = "false") boolean mine,
                                          @RequestParam(defaultValue = "CREATED_DESC") TicketSort sort,
                                          @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(ticketService.page(page, size, status, priority, keyword, overdue,
                mine ? principal.id() : null, requesterScope(principal), principal.tenantId(), sort));
    }

    @GetMapping("/sla/summary")
    public ApiResponse<TicketService.SlaSummary> slaSummary(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(ticketService.slaSummary(requesterScope(principal), principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Ticket> get(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(ticketService.getVisible(id, principal.tenantId(), requesterScope(principal)));
    }

    @PostMapping
    public ApiResponse<Ticket> create(@Valid @RequestBody CreateTicketRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @AuthenticationPrincipal AuthPrincipal principal) {
        Ticket ticket = new Ticket();
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setCategory(request.category());
        ticket.setPriority(request.priority());
        return ApiResponse.ok(ticketService.create(ticket, principal.id(), principal.tenantId(), idempotencyKey), "工单创建成功");
    }

    @PutMapping("/{id}")
    public ApiResponse<Ticket> update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateTicketRequest request,
                                      @AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以编辑工单");
        }
        return ApiResponse.ok(ticketService.updateDetails(id, request.title(), request.description(),
                        request.category(), request.priority(), principal.id(), principal.tenantId()),
                "工单信息已更新");
    }
    @PostMapping("/{id}/transition")
    public ApiResponse<Ticket> transition(@PathVariable Long id, @RequestParam TicketStatus target,
                                          @AuthenticationPrincipal AuthPrincipal principal) {
        Ticket ticket = ticketService.getVisible(id, principal.tenantId(), requesterScope(principal));
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            boolean requesterConfirmation = ticket.getStatus() == TicketStatus.WAITING_CONFIRMATION
                    && target == TicketStatus.RESOLVED;
            boolean requesterClosure = ticket.getStatus() == TicketStatus.RESOLVED
                    && target == TicketStatus.CLOSED;
            if (!requesterConfirmation && !requesterClosure) {
                throw new BusinessException("申请人只能确认解决或关闭自己的工单");
            }
        }
        return ApiResponse.ok(ticketService.transition(id, target, principal.id(), principal.tenantId()), "工单状态更新成功");
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<Ticket> claim(@PathVariable Long id,
                                     @AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以领取工单");
        }
        Ticket ticket = ticketService.getVisible(id, principal.tenantId(), null);
        if (ticket.getAssignmentGroupId() != null) {
            supportGroupService.requireActiveMember(ticket.getAssignmentGroupId(), principal.id(), principal.tenantId());
        }
        return ApiResponse.ok(ticketService.claim(id, principal.id(), principal.tenantId()), "工单已领取");
    }

    @PostMapping("/{id}/release")
    public ApiResponse<Ticket> release(@PathVariable Long id,
                                       @AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以释放工单");
        }
        return ApiResponse.ok(ticketService.release(id, principal.id(), principal.hasAnyRole("ADMIN"),
                principal.tenantId()), "工单已释放");
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<Ticket> assign(@PathVariable Long id,
                                      @Valid @RequestBody AssignRequest request,
                                      @AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以指派工单");
        }
        if (!authService.isStaffMember(request.assigneeId(), principal.tenantId())) {
            throw new BusinessException("目标处理人不存在或不是有效的客服账号");
        }
        Ticket ticket = ticketService.getVisible(id, principal.tenantId(), null);
        if (ticket.getAssignmentGroupId() != null) {
            supportGroupService.requireActiveMember(ticket.getAssignmentGroupId(), request.assigneeId(), principal.tenantId());
        }
        return ApiResponse.ok(ticketService.assign(id, request.assigneeId(), principal.id(), principal.tenantId()),
                "工单已指派");
    }

    @PostMapping("/batch-update")
    public ApiResponse<TicketService.BatchUpdateResult> batchUpdate(
            @Valid @RequestBody BatchUpdateRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以批量处理工单");
        }
        if (request.targetStatus() == null && request.priority() == null) {
            throw new BusinessException("至少选择一个批量更新项");
        }
        return ApiResponse.ok(ticketService.batchUpdate(request.ticketIds(), request.targetStatus(),
                request.priority(), principal.id(), principal.tenantId()), "批量更新完成");
    }

    private Long requesterScope(AuthPrincipal principal) {
        return principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
    }

    public record UpdateTicketRequest(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 10000) String description,
            @Size(max = 64) String category,
            @NotNull TicketPriority priority) { }
    public record CreateTicketRequest(@NotBlank String title, @NotBlank String description,
                                      String category, TicketPriority priority) { }

    public record BatchUpdateRequest(@NotEmpty @Size(max = 100) List<Long> ticketIds,
                                     TicketStatus targetStatus, TicketPriority priority) { }

    public record AssignRequest(@NotNull Long assigneeId) { }
}
