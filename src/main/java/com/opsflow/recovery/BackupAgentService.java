package com.opsflow.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BackupAgentService {
    public static final String CALLBACK_PATH = "/api/v1/backup-agent/callbacks";
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final Pattern HEX_SIGNATURE = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BackupAgentMapper agentMapper;
    private final BackupAgentCallbackMapper callbackMapper;
    private final DisasterRecoveryService disasterRecoveryService;
    private final BackupAgentSecretCipher secretCipher;
    private final BackupAgentProperties properties;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public List<BackupAgent> list(Long tenantId) {
        return agentMapper.selectList(new LambdaQueryWrapper<BackupAgent>()
                .eq(BackupAgent::getTenantId, tenantId)
                .orderByDesc(BackupAgent::getCreatedAt)
                .orderByDesc(BackupAgent::getId));
    }

    public List<BackupAgentCallback> callbacks(Long agentId, Long tenantId) {
        requireAgent(agentId, tenantId);
        return callbackMapper.selectList(new LambdaQueryWrapper<BackupAgentCallback>()
                .eq(BackupAgentCallback::getTenantId, tenantId)
                .eq(BackupAgentCallback::getAgentId, agentId)
                .orderByDesc(BackupAgentCallback::getReceivedAt)
                .orderByDesc(BackupAgentCallback::getId)
                .last("LIMIT 100"));
    }

    @Transactional
    public AgentCredential create(AgentCommand command, Long tenantId, Long actorId) {
        validateCommand(command);
        String secret = newSecret();
        LocalDateTime now = LocalDateTime.now();
        BackupAgent agent = new BackupAgent();
        agent.setTenantId(tenantId);
        agent.setAgentKey("bka_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        agent.setName(normalize(command.name()));
        agent.setDescription(normalizeNullable(command.description()));
        agent.setSecretCiphertext(secretCipher.encrypt(secret));
        agent.setEnabled(command.enabled() == null || command.enabled());
        agent.setCreatedBy(actorId);
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        try {
            agentMapper.insert(agent);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("备份 Agent 名称已存在");
        }
        auditLogService.record(tenantId, actorId, "BACKUP_AGENT", agent.getId(),
                "BACKUP_AGENT_CREATED", null, publicView(agent), "创建外部备份 Agent；密钥仅本次返回");
        return credential(agent, secret);
    }

    @Transactional
    public AgentCredential rotate(Long id, Long tenantId, Long actorId) {
        BackupAgent agent = requireAgent(id, tenantId);
        String secret = newSecret();
        agent.setSecretCiphertext(secretCipher.encrypt(secret));
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(agent);
        auditLogService.record(tenantId, actorId, "BACKUP_AGENT", id,
                "BACKUP_AGENT_SECRET_ROTATED", null, publicView(agent), "轮换备份 Agent 签名密钥；旧密钥立即失效");
        return credential(agent, secret);
    }

    @Transactional
    public BackupAgent setEnabled(Long id, boolean enabled, Long tenantId, Long actorId) {
        BackupAgent agent = requireAgent(id, tenantId);
        boolean before = Boolean.TRUE.equals(agent.getEnabled());
        agent.setEnabled(enabled);
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(agent);
        auditLogService.record(tenantId, actorId, "BACKUP_AGENT", id,
                enabled ? "BACKUP_AGENT_ENABLED" : "BACKUP_AGENT_DISABLED",
                Map.of("enabled", before), Map.of("enabled", enabled),
                enabled ? "启用外部备份 Agent" : "停用外部备份 Agent");
        return agent;
    }

    @Transactional
    public CallbackResult receive(String agentKey, String timestampValue, String nonce,
                                  String signature, String rawBody) {
        if (rawBody == null || rawBody.isBlank() || rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new BusinessException("回调请求体为空或超过 32 KiB");
        }
        long timestamp = parseTimestamp(timestampValue);
        validateFreshness(timestamp);
        if (nonce == null || !SAFE_TOKEN.matcher(nonce).matches()) {
            throw new BusinessException("回调 nonce 格式无效");
        }
        if (signature == null || !HEX_SIGNATURE.matcher(signature).matches()) {
            throw new BusinessException("回调签名格式无效");
        }

        BackupAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<BackupAgent>()
                .eq(BackupAgent::getAgentKey, normalize(agentKey))
                .eq(BackupAgent::getEnabled, true)
                .last("LIMIT 1"));
        if (agent == null) throw new BusinessException("备份 Agent 凭证无效或已停用");

        String payloadHash = sha256(rawBody);
        String canonical = "POST\n" + CALLBACK_PATH + "\n" + timestamp + "\n" + nonce + "\n" + payloadHash;
        String expected = hmac(secretCipher.decrypt(agent.getSecretCiphertext()), canonical);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException("备份 Agent 回调签名无效");
        }

        CallbackPayload payload = parse(rawBody);
        validatePayload(payload);
        String eventId = payload.eventId().trim();
        BackupAgentCallback existing = callbackMapper.selectOne(new LambdaQueryWrapper<BackupAgentCallback>()
                .eq(BackupAgentCallback::getAgentId, agent.getId())
                .eq(BackupAgentCallback::getEventId, eventId)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.getPayloadSha256().getBytes(StandardCharsets.US_ASCII),
                    payloadHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new BusinessException("相同 eventId 的回调内容不一致");
            }
            BackupSnapshot snapshot = disasterRecoveryService.findSnapshot(existing.getBackupSnapshotId(), agent.getTenantId());
            return new CallbackResult(false, true, eventId, snapshot);
        }
        Long reusedNonce = callbackMapper.selectCount(new LambdaQueryWrapper<BackupAgentCallback>()
                .eq(BackupAgentCallback::getAgentId, agent.getId())
                .eq(BackupAgentCallback::getNonce, nonce));
        if (reusedNonce != null && reusedNonce > 0) throw new BusinessException("回调 nonce 已使用，疑似重放请求");

        BackupSnapshot snapshot = disasterRecoveryService.registerBackup(payload.command(), agent.getTenantId(), agent.getCreatedBy());
        LocalDateTime now = LocalDateTime.now();
        BackupAgentCallback callback = new BackupAgentCallback();
        callback.setTenantId(agent.getTenantId());
        callback.setAgentId(agent.getId());
        callback.setEventId(eventId);
        callback.setNonce(nonce);
        callback.setPayloadSha256(payloadHash);
        callback.setBackupSnapshotId(snapshot.getId());
        callback.setReceivedAt(now);
        try {
            callbackMapper.insert(callback);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("回调 eventId 或 nonce 已使用");
        }
        agent.setLastSeenAt(now);
        agent.setLastEventId(eventId);
        agent.setUpdatedAt(now);
        agentMapper.updateById(agent);
        auditLogService.record(agent.getTenantId(), agent.getCreatedBy(), "BACKUP_AGENT_CALLBACK", callback.getId(),
                "BACKUP_AGENT_CALLBACK_ACCEPTED", null,
                Map.of("agentId", agent.getId(), "eventId", eventId, "backupSnapshotId", snapshot.getId()),
                "签名、时间窗、防重放与幂等校验通过");
        return new CallbackResult(true, false, eventId, snapshot);
    }

    private CallbackPayload parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, CallbackPayload.class);
        } catch (JacksonException ex) {
            throw new BusinessException("回调 JSON 格式无效");
        }
    }

    private void validatePayload(CallbackPayload payload) {
        if (payload == null || payload.eventId() == null || !SAFE_TOKEN.matcher(payload.eventId().trim()).matches()) {
            throw new BusinessException("eventId 格式无效");
        }
        if (payload.scope() == null || payload.storageProvider() == null || payload.storageLocation() == null
                || payload.region() == null || payload.recoveryPointAt() == null || payload.capturedAt() == null
                || payload.completedAt() == null || payload.sizeBytes() == null || payload.checksumSha256() == null
                || payload.encrypted() == null || payload.crossRegionCopy() == null) {
            throw new BusinessException("回调备份字段不完整");
        }
    }

    private long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ex) {
            throw new BusinessException("回调时间戳无效");
        }
    }

    private void validateFreshness(long timestamp) {
        Duration tolerance = properties.getSignatureTolerance() == null
                ? Duration.ofMinutes(5) : properties.getSignatureTolerance();
        long now = Instant.now().getEpochSecond();
        long allowed = Math.max(30, tolerance.getSeconds());
        if (timestamp < now - allowed || timestamp > now + allowed) {
            throw new BusinessException("回调时间戳已过期或超前");
        }
    }

    private BackupAgent requireAgent(Long id, Long tenantId) {
        BackupAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<BackupAgent>()
                .eq(BackupAgent::getId, id)
                .eq(BackupAgent::getTenantId, tenantId));
        if (agent == null) throw new BusinessException("备份 Agent 不存在或无权访问");
        return agent;
    }

    private void validateCommand(AgentCommand command) {
        String name = normalize(command == null ? null : command.name());
        if (name.length() < 2 || name.length() > 120) throw new BusinessException("Agent 名称长度必须在 2-120 个字符之间");
        if (command.description() != null && command.description().trim().length() > 500) {
            throw new BusinessException("Agent 描述不能超过 500 个字符");
        }
    }

    private AgentCredential credential(BackupAgent agent, String secret) {
        return new AgentCredential(publicView(agent), secret, CALLBACK_PATH,
                "POST\n" + CALLBACK_PATH + "\n{timestamp}\n{nonce}\n{sha256(rawBody)}");
    }

    private AgentView publicView(BackupAgent agent) {
        return new AgentView(agent.getId(), agent.getTenantId(), agent.getAgentKey(), agent.getName(),
                agent.getDescription(), Boolean.TRUE.equals(agent.getEnabled()), agent.getLastSeenAt(),
                agent.getLastEventId(), agent.getCreatedAt(), agent.getUpdatedAt());
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 不可用", ex);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("必填字段不能为空");
        return value.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record AgentCommand(String name, String description, Boolean enabled) { }
    public record AgentView(Long id, Long tenantId, String agentKey, String name, String description,
                            boolean enabled, LocalDateTime lastSeenAt, String lastEventId,
                            LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record AgentCredential(AgentView agent, String secret, String callbackPath, String canonicalTemplate) { }
    public record CallbackResult(boolean accepted, boolean idempotent, String eventId, BackupSnapshot snapshot) { }

    public record CallbackPayload(String eventId, BackupScope scope, String storageProvider,
                                  String storageLocation, String region, LocalDateTime recoveryPointAt,
                                  LocalDateTime capturedAt, LocalDateTime completedAt, Long sizeBytes,
                                  String checksumSha256, Boolean encrypted, LocalDateTime immutableUntil,
                                  Boolean crossRegionCopy, String evidenceNote) {
        DisasterRecoveryService.BackupCommand command() {
            return new DisasterRecoveryService.BackupCommand(scope, storageProvider, storageLocation, region,
                    recoveryPointAt, capturedAt, completedAt, sizeBytes, checksumSha256, encrypted,
                    immutableUntil, crossRegionCopy, evidenceNote);
        }
    }
}
