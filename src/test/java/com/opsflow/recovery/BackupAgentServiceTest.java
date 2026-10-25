package com.opsflow.recovery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackupAgentServiceTest {
    @Mock private BackupAgentMapper agentMapper;
    @Mock private BackupAgentCallbackMapper callbackMapper;
    @Mock private DisasterRecoveryService disasterRecoveryService;
    @Mock private AuditLogService auditLogService;

    private BackupAgentProperties properties;
    private BackupAgentSecretCipher cipher;

    @BeforeEach
    void setUp() {
        properties = new BackupAgentProperties();
        properties.setEncryptionKey("unit-test-backup-agent-encryption-key-123456789");
        properties.setSignatureTolerance(Duration.ofMinutes(5));
        cipher = new BackupAgentSecretCipher(properties);
    }

    @Test
    void encryptsAgentSecretAtRestAndDecryptsForVerification() {
        String first = cipher.encrypt("signing-secret");
        String second = cipher.encrypt("signing-secret");

        assertThat(first).isNotEqualTo("signing-secret").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("signing-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("signing-secret");
    }

    @Test
    void acceptsSignedCallbackAndRegistersBackupOnce() throws Exception {
        String secret = "callback-secret-value";
        BackupAgent agent = agent(secret);
        when(agentMapper.selectOne(any(Wrapper.class))).thenReturn(agent);
        when(callbackMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(callbackMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.setId(81L);
        snapshot.setTenantId(9L);
        when(disasterRecoveryService.registerBackup(any(), eq(9L), eq(7L))).thenReturn(snapshot);
        String body = payload("evt-backup-0001");
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "nonce-backup-0001";
        String signature = sign(secret, timestamp, nonce, body);

        BackupAgentService.CallbackResult result = service().receive(
                agent.getAgentKey(), String.valueOf(timestamp), nonce, signature, body);

        assertThat(result.accepted()).isTrue();
        assertThat(result.idempotent()).isFalse();
        assertThat(result.snapshot().getId()).isEqualTo(81L);
        verify(callbackMapper).insert(any(BackupAgentCallback.class));
        verify(agentMapper).updateById(agent);
    }

    @Test
    void returnsExistingSnapshotForSameSignedEvent() throws Exception {
        String secret = "callback-secret-value";
        BackupAgent agent = agent(secret);
        when(agentMapper.selectOne(any(Wrapper.class))).thenReturn(agent);
        String body = payload("evt-backup-0002");
        BackupAgentCallback callback = new BackupAgentCallback();
        callback.setAgentId(31L);
        callback.setEventId("evt-backup-0002");
        callback.setPayloadSha256(sha256(body));
        callback.setBackupSnapshotId(82L);
        when(callbackMapper.selectOne(any(Wrapper.class))).thenReturn(callback);
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.setId(82L);
        when(disasterRecoveryService.findSnapshot(82L, 9L)).thenReturn(snapshot);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "nonce-backup-0002";

        BackupAgentService.CallbackResult result = service().receive(agent.getAgentKey(),
                String.valueOf(timestamp), nonce, sign(secret, timestamp, nonce, body), body);

        assertThat(result.idempotent()).isTrue();
        assertThat(result.accepted()).isFalse();
        verify(disasterRecoveryService, never()).registerBackup(any(), any(), any());
        verify(callbackMapper, never()).insert(any(BackupAgentCallback.class));
    }

    @Test
    void rejectsExpiredTimestampBeforeCredentialLookup() {
        String body = payload("evt-backup-0003");
        long timestamp = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();

        BusinessException exception = assertThrows(BusinessException.class, () -> service().receive(
                "bka_agent", String.valueOf(timestamp), "nonce-backup-0003", "a".repeat(64), body));

        assertThat(exception.getMessage()).contains("时间戳");
        verify(agentMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void rejectsExtremeTimestampWithoutOverflowingFreshnessCheck() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service().receive(
                "bka_agent", String.valueOf(Long.MIN_VALUE), "nonce-backup-9999", "a".repeat(64),
                payload("evt-backup-9999")));

        assertThat(exception.getMessage()).contains("时间戳");
        verify(agentMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void rejectsInvalidSignatureWithoutRegisteringBackup() {
        BackupAgent agent = agent("correct-secret");
        when(agentMapper.selectOne(any(Wrapper.class))).thenReturn(agent);

        BusinessException exception = assertThrows(BusinessException.class, () -> service().receive(
                agent.getAgentKey(), String.valueOf(Instant.now().getEpochSecond()),
                "nonce-backup-0004", "b".repeat(64), payload("evt-backup-0004")));

        assertThat(exception.getMessage()).contains("签名无效");
        verify(disasterRecoveryService, never()).registerBackup(any(), any(), any());
    }

    private BackupAgentService service() {
        return new BackupAgentService(agentMapper, callbackMapper, disasterRecoveryService, cipher,
                properties, auditLogService, JsonMapper.builder().findAndAddModules().build());
    }

    private BackupAgent agent(String secret) {
        BackupAgent agent = new BackupAgent();
        agent.setId(31L);
        agent.setTenantId(9L);
        agent.setAgentKey("bka_testagent0000000000000001");
        agent.setSecretCiphertext(cipher.encrypt(secret));
        agent.setEnabled(true);
        agent.setCreatedBy(7L);
        return agent;
    }

    private String payload(String eventId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return "{\"eventId\":\"" + eventId + "\",\"scope\":\"DATABASE\","
                + "\"storageProvider\":\"S3\",\"storageLocation\":\"s3://backup/db.sql\","
                + "\"region\":\"cn-test-1\",\"recoveryPointAt\":\"" + now.minusMinutes(3) + "\","
                + "\"capturedAt\":\"" + now.minusMinutes(2) + "\",\"completedAt\":\"" + now.minusMinutes(1) + "\","
                + "\"sizeBytes\":1024,\"checksumSha256\":\"" + "a".repeat(64) + "\","
                + "\"encrypted\":true,\"crossRegionCopy\":false,\"evidenceNote\":\"agent job passed\"}";
    }

    private String sign(String secret, long timestamp, String nonce, String body) throws Exception {
        String canonical = "POST\n" + BackupAgentService.CALLBACK_PATH + "\n" + timestamp + "\n"
                + nonce + "\n" + sha256(body);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
