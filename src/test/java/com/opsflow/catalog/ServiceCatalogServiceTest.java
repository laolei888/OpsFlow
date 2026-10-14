package com.opsflow.catalog;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.notification.NotificationService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceCatalogServiceTest {
    @Mock
    private ServiceCatalogItemMapper itemMapper;
    @Mock
    private ServiceRequestMapper requestMapper;
    @Mock
    private TicketService ticketService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private AuthService authService;

    @InjectMocks
    private ServiceCatalogService service;

    @Test
    void createsPendingRequestAndNotifiesTenantAdmins() {
        ServiceCatalogItem item = item(true);
        when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(item);
        when(requestMapper.insert(any(ServiceRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ServiceRequest.class).setId(31L);
            return 1;
        });
        when(authService.staffMembers(3L)).thenReturn(List.of(
                new StaffSummary(8L, "agent", "客服", List.of("AGENT")),
                new StaffSummary(9L, "admin", "管理员", List.of("ADMIN"))));

        ServiceRequest result = service.createRequest(5L, "开通研发环境", "需要访问项目资源",
                null, 7L, 3L);

        assertEquals(ServiceRequestStatus.PENDING_APPROVAL, result.getStatus());
        assertNull(result.getTicketId());
        verify(ticketService, never()).create(any(Ticket.class), any(), any());
        verify(notificationService).notifyIfAbsent(eq(3L), eq(9L), eq("SERVICE_APPROVAL_REQUIRED"),
                contains("待审批"), contains("开通研发环境"), contains("SERVICE_APPROVAL_REQUIRED:31"));
    }

    @Test
    void autoApprovesRequestAndCreatesTicketWhenApprovalNotRequired() {
        ServiceCatalogItem item = item(false);
        when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(item);
        when(requestMapper.insert(any(ServiceRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ServiceRequest.class).setId(32L);
            return 1;
        });
        Ticket ticket = new Ticket();
        ticket.setId(44L);
        ticket.setTicketNo("OPF-TEST0001");
        when(ticketService.create(any(Ticket.class), eq(7L), eq(3L))).thenReturn(ticket);

        ServiceRequest result = service.createRequest(5L, "安装开发工具", "安装 IntelliJ IDEA",
                "request-key-1", 7L, 3L);

        assertEquals(ServiceRequestStatus.APPROVED, result.getStatus());
        assertEquals(44L, result.getTicketId());
        verify(requestMapper).updateById(result);
        verify(notificationService).notifyIfAbsent(eq(3L), eq(7L), eq("SERVICE_REQUEST_RESULT"),
                contains("已受理"), contains("OPF-TEST0001"), eq("SERVICE_REQUEST_AUTO_APPROVED:32"));
    }

    @Test
    void approvalLocksRequestAndCreatesLinkedTicket() {
        ServiceRequest request = pendingRequest();
        when(requestMapper.selectForUpdate(31L, 3L)).thenReturn(request);
        Ticket ticket = new Ticket();
        ticket.setId(45L);
        ticket.setTicketNo("OPF-TEST0002");
        when(ticketService.create(any(Ticket.class), eq(7L), eq(3L))).thenReturn(ticket);

        ServiceRequest result = service.approve(31L, "预算已确认", 9L, 3L);

        assertEquals(ServiceRequestStatus.APPROVED, result.getStatus());
        assertEquals(45L, result.getTicketId());
        assertEquals(9L, result.getDecidedBy());
        verify(auditLogService).record(eq(3L), eq(9L), eq("SERVICE_REQUEST"), eq(31L),
                eq("SERVICE_REQUEST_APPROVED"), any(), eq(result), contains("OPF-TEST0002"));
    }

    @Test
    void rejectionRequiresReason() {
        assertThrows(RuntimeException.class, () -> service.reject(31L, "  ", 9L, 3L));
        verify(requestMapper, never()).selectForUpdate(any(), any());
    }

    private ServiceCatalogItem item(boolean approvalRequired) {
        ServiceCatalogItem item = new ServiceCatalogItem();
        item.setId(5L);
        item.setTenantId(3L);
        item.setName("研发环境访问");
        item.setDescription("申请访问研发环境");
        item.setCategory("访问权限");
        item.setDefaultPriority(TicketPriority.HIGH);
        item.setApprovalRequired(approvalRequired);
        item.setEnabled(true);
        return item;
    }

    private ServiceRequest pendingRequest() {
        ServiceRequest request = new ServiceRequest();
        request.setId(31L);
        request.setRequestNo("SR-TEST0001");
        request.setTenantId(3L);
        request.setRequesterId(7L);
        request.setItemName("研发环境访问");
        request.setCategory("访问权限");
        request.setPriority(TicketPriority.HIGH);
        request.setSummary("开通研发环境");
        request.setDetail("需要访问项目资源");
        request.setStatus(ServiceRequestStatus.PENDING_APPROVAL);
        return request;
    }
}
