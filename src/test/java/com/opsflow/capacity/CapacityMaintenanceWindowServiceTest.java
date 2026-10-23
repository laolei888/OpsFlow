package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapacityMaintenanceWindowServiceTest {
    @Mock private CapacityMaintenanceWindowMapper windowMapper;
    @Mock private AuditLogService auditLogService;

    @Test
    void createsAuditedMaintenanceWindow() {
        CapacityMaintenanceWindowService service = new CapacityMaintenanceWindowService(windowMapper, auditLogService);
        when(windowMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        LocalDateTime start = LocalDateTime.now().plusHours(1);
        CapacityMaintenanceWindowService.WindowCommand command = new CapacityMaintenanceWindowService.WindowCommand(
                " 数据库扩容 ", start, start.plusHours(2), " 计划内容量调整 ", true);

        CapacityMaintenanceWindow created = service.create(command, 3L, 7L);

        ArgumentCaptor<CapacityMaintenanceWindow> captor = ArgumentCaptor.forClass(CapacityMaintenanceWindow.class);
        verify(windowMapper).insert(captor.capture());
        assertThat(created.getName()).isEqualTo("数据库扩容");
        assertThat(created.getReason()).isEqualTo("计划内容量调整");
        assertThat(created.getCreatedBy()).isEqualTo(7L);
    }

    @Test
    void rejectsOverlappingEnabledWindow() {
        CapacityMaintenanceWindowService service = new CapacityMaintenanceWindowService(windowMapper, auditLogService);
        when(windowMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        LocalDateTime start = LocalDateTime.now().plusHours(1);
        CapacityMaintenanceWindowService.WindowCommand command = new CapacityMaintenanceWindowService.WindowCommand(
                "数据库扩容", start, start.plusHours(2), "计划内容量调整", true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(command, 3L, 7L));

        assertThat(exception.getMessage()).contains("已存在启用中的");
        verify(windowMapper, never()).insert(any(CapacityMaintenanceWindow.class));
    }
}
