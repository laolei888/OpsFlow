package com.opsflow.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.security.OutboundUrlPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class WebhookEndpointService {
    private static final Pattern EVENT_PATTERN = Pattern.compile("[A-Z][A-Z0-9_.:-]{0,79}");

    private final WebhookEndpointMapper endpointMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final AuditLogService auditLogService;
    private final OutboundUrlPolicy outboundUrlPolicy;

    public List<WebhookEndpointView> list(Long tenantId) {
        return endpointMapper.selectList(new LambdaQueryWrapper<WebhookEndpoint>()
                        .eq(WebhookEndpoint::getTenantId, tenantId)
                        .orderByDesc(WebhookEndpoint::getUpdatedAt)
                        .orderByDesc(WebhookEndpoint::getId))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public WebhookEndpointView create(WebhookCommand command, Long tenantId, Long actorId) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        applyCommand(endpoint, command, tenantId, false);
        LocalDateTime now = LocalDateTime.now();
        endpoint.setCreatedBy(actorId);
        endpoint.setCreatedAt(now);
        endpoint.setUpdatedAt(now);
        try {
            endpointMapper.insert(endpoint);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("当前租户已存在同名 Webhook");
        }
        WebhookEndpointView view = toView(endpoint);
        auditLogService.record(tenantId, actorId, "WEBHOOK_ENDPOINT", endpoint.getId(),
                "WEBHOOK_CREATED", null, view, "创建 Webhook 集成");
        return view;
    }

    @Transactional
    public WebhookEndpointView update(Long id, WebhookCommand command, Long tenantId, Long actorId) {
        WebhookEndpoint endpoint = find(id, tenantId);
        WebhookEndpointView before = toView(endpoint);
        applyCommand(endpoint, command, tenantId, true);
        endpoint.setUpdatedAt(LocalDateTime.now());
        try {
            endpointMapper.updateById(endpoint);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("当前租户已存在同名 Webhook");
        }
        WebhookEndpointView view = toView(endpoint);
        auditLogService.record(tenantId, actorId, "WEBHOOK_ENDPOINT", id,
                "WEBHOOK_UPDATED", before, view, "更新 Webhook 集成");
        return view;
    }

    @Transactional
    public WebhookEndpointView toggle(Long id, Boolean enabled, Long tenantId, Long actorId) {
        WebhookEndpoint endpoint = find(id, tenantId);
        WebhookEndpointView before = toView(endpoint);
        boolean target = enabled == null ? !Boolean.TRUE.equals(endpoint.getEnabled()) : enabled;
        endpoint.setEnabled(target);
        endpoint.setUpdatedAt(LocalDateTime.now());
        endpointMapper.updateById(endpoint);
        WebhookEndpointView view = toView(endpoint);
        auditLogService.record(tenantId, actorId, "WEBHOOK_ENDPOINT", id,
                "WEBHOOK_TOGGLED", before, view, target ? "启用 Webhook 集成" : "停用 Webhook 集成");
        return view;
    }

    @Transactional
    public void delete(Long id, Long tenantId, Long actorId) {
        WebhookEndpoint endpoint = find(id, tenantId);
        endpointMapper.delete(new LambdaQueryWrapper<WebhookEndpoint>()
                .eq(WebhookEndpoint::getId, id)
                .eq(WebhookEndpoint::getTenantId, tenantId));
        auditLogService.record(tenantId, actorId, "WEBHOOK_ENDPOINT", id,
                "WEBHOOK_DELETED", toView(endpoint), null, "删除 Webhook 集成");
    }

    public List<WebhookDeliveryView> listDeliveries(Long tenantId, Long endpointId,
                                                     WebhookDeliveryStatus status, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<WebhookDelivery> query = new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getTenantId, tenantId)
                .orderByDesc(WebhookDelivery::getCreatedAt)
                .orderByDesc(WebhookDelivery::getId)
                .last("LIMIT " + safeLimit);
        if (endpointId != null) query.eq(WebhookDelivery::getEndpointId, endpointId);
        if (status != null) query.eq(WebhookDelivery::getStatus, status);
        List<WebhookDelivery> deliveries = deliveryMapper.selectList(query);
        Map<Long, String> endpointNames = new LinkedHashMap<>();
        endpointMapper.selectList(new LambdaQueryWrapper<WebhookEndpoint>()
                        .eq(WebhookEndpoint::getTenantId, tenantId))
                .forEach(item -> endpointNames.put(item.getId(), item.getName()));
        return deliveries.stream()
                .map(item -> new WebhookDeliveryView(item, endpointNames.getOrDefault(item.getEndpointId(), "已删除集成")))
                .toList();
    }

    @Transactional
    public WebhookDeliveryView retry(Long deliveryId, Long tenantId, Long actorId) {
        WebhookDelivery delivery = deliveryMapper.selectForUpdate(deliveryId, tenantId);
        if (delivery == null) throw new BusinessException("Webhook 投递记录不存在");
        if (delivery.getStatus() == WebhookDeliveryStatus.DELIVERED) {
            throw new BusinessException("已送达的 Webhook 无需重试");
        }
        LocalDateTime now = LocalDateTime.now();
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(now);
        delivery.setResponseStatus(null);
        delivery.setLastError(null);
        delivery.setDeliveredAt(null);
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
        auditLogService.record(tenantId, actorId, "WEBHOOK_DELIVERY", deliveryId,
                "WEBHOOK_RETRY", null, Map.of("endpointId", delivery.getEndpointId(), "eventId", delivery.getEventId()),
                "手动重试 Webhook 投递");
        WebhookEndpoint endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<WebhookEndpoint>()
                .eq(WebhookEndpoint::getId, delivery.getEndpointId())
                .eq(WebhookEndpoint::getTenantId, tenantId));
        String endpointName = endpoint == null ? "已删除集成" : endpoint.getName();

        return new WebhookDeliveryView(delivery, endpointName);
    }

    private void applyCommand(WebhookEndpoint endpoint, WebhookCommand command, Long tenantId, boolean update) {
        if (command == null) throw new BusinessException("Webhook 配置不能为空");
        String name = trim(command.name());
        String url = trim(command.url());
        if (name == null || name.length() > 80) throw new BusinessException("Webhook 名称不能为空且不能超过 80 个字符");
        if (url == null || url.length() > 500) throw new BusinessException("Webhook URL 不能为空且不能超过 500 个字符");
        validateUrl(url);
        String eventTypes = normalizeEventTypes(command.eventTypes());
        String secret = trim(command.secret());
        if (secret == null && !update) secret = UUID.randomUUID().toString().replace("-", "");
        if (secret != null && secret.length() > 200) throw new BusinessException("Webhook 密钥不能超过 200 个字符");
        endpoint.setTenantId(tenantId);
        endpoint.setName(name);
        endpoint.setUrl(url);
        endpoint.setEventTypes(eventTypes);
        if (secret != null) endpoint.setSecret(secret);
        if (!update) endpoint.setEnabled(true);
    }

    private WebhookEndpoint find(Long id, Long tenantId) {
        WebhookEndpoint endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<WebhookEndpoint>()
                .eq(WebhookEndpoint::getId, id)
                .eq(WebhookEndpoint::getTenantId, tenantId));
        if (endpoint == null) throw new BusinessException("Webhook 集成不存在");
        return endpoint;
    }

    private WebhookEndpointView toView(WebhookEndpoint endpoint) {
        return new WebhookEndpointView(endpoint.getId(), endpoint.getName(), endpoint.getUrl(), endpoint.getEventTypes(),
                Boolean.TRUE.equals(endpoint.getEnabled()), maskSecret(endpoint.getSecret()), endpoint.getCreatedAt(),
                endpoint.getUpdatedAt());
    }

    private String normalizeEventTypes(String raw) {
        String value = trim(raw);
        if (value == null) throw new BusinessException("至少配置一个 Webhook 事件类型");
        if ("*".equals(value)) return value;
        String[] values = Arrays.stream(value.split(","))
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .distinct()
                .toArray(String[]::new);
        if (values.length == 0 || values.length > 40) throw new BusinessException("Webhook 事件最多配置 40 个");
        for (String item : values) {
            if (!EVENT_PATTERN.matcher(item).matches()) {
                throw new BusinessException("Webhook 事件类型格式无效：" + item);
            }
        }
        return String.join(",", values);
    }

    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if ((scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
                    || uri.getHost() == null) {
                throw new BusinessException("Webhook URL 必须是带域名的 HTTP 或 HTTPS 地址");
            }
            outboundUrlPolicy.validate(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Webhook URL 格式无效");
        }
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) return "未设置";
        int visible = Math.min(4, secret.length());
        return "••••••••" + secret.substring(secret.length() - visible);
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    public record WebhookCommand(String name, String url, String secret, String eventTypes) { }

    public record WebhookEndpointView(Long id, String name, String url, String eventTypes,
                                      boolean enabled, String secretPreview,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record WebhookDeliveryView(WebhookDelivery delivery, String endpointName) { }
}
