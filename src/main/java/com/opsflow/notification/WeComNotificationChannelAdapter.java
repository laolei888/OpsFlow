package com.opsflow.notification;

import com.opsflow.security.OutboundUrlPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class WeComNotificationChannelAdapter implements NotificationChannelAdapter {
    private static final int MAX_MARKDOWN_LENGTH = 3900;

    private final ObjectMapper objectMapper;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final HttpClient httpClient;

    @Autowired
    public WeComNotificationChannelAdapter(ObjectMapper objectMapper, OutboundUrlPolicy outboundUrlPolicy) {
        this(objectMapper, outboundUrlPolicy,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    WeComNotificationChannelAdapter(ObjectMapper objectMapper, OutboundUrlPolicy outboundUrlPolicy,
                                    HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.httpClient = httpClient;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WECOM;
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context) throws Exception {
        URI destination = validateDestination(context.delivery().getDestination());
        String payload = payload(context);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(destination)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("User-Agent", "OpsFlow-WeCom/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new NotificationDeliveryException("企业微信返回 HTTP " + status, status,
                    status >= 500 || status == 408 || status == 429);
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (JacksonException invalidJson) {
            throw new NotificationDeliveryException("企业微信响应格式无效", status, true);
        }
        int errorCode = body == null || body.get("errcode") == null ? -1 : body.get("errcode").asInt(-1);
        if (errorCode != 0) {
            String errorMessage = body == null || body.get("errmsg") == null ? "未知错误" : body.get("errmsg").asText();
            boolean retryable = errorCode == -1 || errorCode == 45009;
            throw new NotificationDeliveryException("企业微信错误 " + errorCode + "：" + errorMessage, status, retryable);
        }
        return new DeliveryResult(status);
    }

    String payload(DeliveryContext context) {
        String title = context.delivery().getRenderedTitle() == null
                ? context.notification().getTitle() : context.delivery().getRenderedTitle();
        String content = context.delivery().getRenderedContent() == null
                ? context.notification().getContent() : context.delivery().getRenderedContent();
        String markdown = "**" + safe(title) + "**\n>事件：" + safe(context.notification().getType())
                + "\n>时间：" + String.valueOf(context.notification().getCreatedAt()) + "\n\n" + safe(content);
        if (markdown.length() > MAX_MARKDOWN_LENGTH) markdown = markdown.substring(0, MAX_MARKDOWN_LENGTH) + "…";
        try {
            return objectMapper.writeValueAsString(Map.of("msgtype", "markdown",
                    "markdown", Map.of("content", markdown)));
        } catch (JacksonException ex) {
            throw new NotificationDeliveryException("企业微信负载序列化失败", null, false);
        }
    }

    private URI validateDestination(String value) {
        URI uri = outboundUrlPolicy.validate(value);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("qyapi.weixin.qq.com") || host.equalsIgnoreCase("webhook.weixin.qq.com"))
                || uri.getPath() == null || !uri.getPath().startsWith("/cgi-bin/webhook/send")
                || uri.getQuery() == null || !uri.getQuery().contains("key=")) {
            throw new NotificationDeliveryException("企业微信机器人地址不符合官方 Webhook 格式", null, false);
        }
        return uri;
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("<", "&lt;").replace(">", "&gt;");
    }
}
