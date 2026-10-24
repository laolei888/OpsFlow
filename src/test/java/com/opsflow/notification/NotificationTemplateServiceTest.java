package com.opsflow.notification;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {
    @Mock private NotificationTemplateMapper templateMapper;
    @Mock private NotificationTemplateVersionMapper versionMapper;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private NotificationTemplateService templateService;

    @Test
    void rendersPublishedEventTemplateAndReturnsVersionEvidence() {
        NotificationTemplate template = new NotificationTemplate();
        template.setId(11L);
        template.setTenantId(1L);
        template.setEventType("INCIDENT_DECLARED");
        template.setChannel(NotificationChannel.WECOM);
        template.setEnabled(true);
        template.setActiveVersionId(21L);
        NotificationTemplateVersion version = new NotificationTemplateVersion();
        version.setId(21L);
        version.setTenantId(1L);
        version.setTemplateId(11L);
        version.setVersionNo(3);
        version.setStatus(NotificationTemplateStatus.PUBLISHED);
        version.setTitleTemplate("[{{eventType}}] {{ title }}");
        version.setContentTemplate("{{content}} / 工单={{ticketId}} / 接收人={{recipientId}}");
        when(templateMapper.selectList(any())).thenReturn(List.of(template));
        when(versionMapper.selectOne(any())).thenReturn(version);

        Notification notification = notification();
        NotificationTemplateService.RenderedTemplate rendered = templateService.render(
                1L, NotificationChannel.WECOM, notification);

        assertThat(rendered.versionId()).isEqualTo(21L);
        assertThat(rendered.title()).isEqualTo("[INCIDENT_DECLARED] 订单服务故障");
        assertThat(rendered.content()).isEqualTo("错误率过高 / 工单=99 / 接收人=7");
    }

    @Test
    void fallsBackToOriginalNotificationWhenNoTemplateIsPublished() {
        when(templateMapper.selectList(any())).thenReturn(List.of());

        NotificationTemplateService.RenderedTemplate rendered = templateService.render(
                1L, NotificationChannel.EMAIL, notification());

        assertThat(rendered.versionId()).isNull();
        assertThat(rendered.title()).isEqualTo("订单服务故障");
        assertThat(rendered.content()).isEqualTo("错误率过高");
    }

    @Test
    void rejectsUnknownPlaceholderBeforeSavingTemplate() {
        NotificationTemplateService.CreateCommand command = new NotificationTemplateService.CreateCommand(
                "重大事件通知", null, "INCIDENT_DECLARED", NotificationChannel.EMAIL,
                "{{unknown}}", "{{content}}", "initial");

        assertThatThrownBy(() -> templateService.create(command, 1L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未知变量");
    }

    private Notification notification() {
        Notification notification = new Notification();
        notification.setId(31L);
        notification.setTenantId(1L);
        notification.setRecipientId(7L);
        notification.setTicketId(99L);
        notification.setType("INCIDENT_DECLARED");
        notification.setTitle("订单服务故障");
        notification.setContent("错误率过高");
        notification.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0));
        return notification;
    }
}
