package com.opsflow.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.cmdb.CmdbAssetService;
import com.opsflow.common.BusinessException;
import com.opsflow.oncall.OnCallSchedule;
import com.opsflow.oncall.OnCallScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertConfigService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AlertSourceMapper sourceMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertSilenceMapper silenceMapper;
    private final OnCallScheduleMapper scheduleMapper;
    private final CmdbAssetService cmdbAssetService;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public List<AlertSource> listSources(Long tenantId) {
        return sourceMapper.selectList(new LambdaQueryWrapper<AlertSource>()
                .eq(AlertSource::getTenantId, tenantId)
                .orderByAsc(AlertSource::getName)
                .orderByAsc(AlertSource::getId));
    }

    @Transactional
    public SourceCredential createSource(SourceCommand command, Long actorId, Long tenantId) {
        validateSourceCommand(command, null, tenantId);
        String secret = newSecret();
        LocalDateTime now = LocalDateTime.now();
        AlertSource source = new AlertSource();
        source.setTenantId(tenantId);
        source.setName(normalize(command.name()));
        source.setDescription(normalize(command.description()));
        source.setSourceKey("src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        source.setApiKeyHash(passwordEncoder.encode(secret));
        source.setDefaultAssetId(command.defaultAssetId());
        source.setEnabled(command.enabled() == null || command.enabled());
        source.setCreatedBy(actorId);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        try {
            sourceMapper.insert(source);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("告警源名称已存在，请更换后重试");
        }
        auditLogService.record(tenantId, actorId, "ALERT_SOURCE", source.getId(), "ALERT_SOURCE_CREATED",
                null, source, "创建监控告警源");
        return new SourceCredential(source, secret);
    }

    @Transactional
    public AlertSource updateSource(Long id, SourceCommand command, Long actorId, Long tenantId) {
        AlertSource source = findSource(id, tenantId);
        validateSourceCommand(command, id, tenantId);
        AlertSource before = copy(source);
        source.setName(normalize(command.name()));
        source.setDescription(normalize(command.description()));
        source.setDefaultAssetId(command.defaultAssetId());
        if (command.enabled() != null) source.setEnabled(command.enabled());
        source.setUpdatedAt(LocalDateTime.now());
        try {
            sourceMapper.updateById(source);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("告警源名称已存在，请更换后重试");
        }
        auditLogService.record(tenantId, actorId, "ALERT_SOURCE", id, "ALERT_SOURCE_UPDATED",
                before, source, "更新监控告警源");
        return source;
    }

    @Transactional
    public SourceCredential rotateSourceKey(Long id, Long actorId, Long tenantId) {
        AlertSource source = findSource(id, tenantId);
        String secret = newSecret();
        source.setApiKeyHash(passwordEncoder.encode(secret));
        source.setUpdatedAt(LocalDateTime.now());
        sourceMapper.updateById(source);
        auditLogService.record(tenantId, actorId, "ALERT_SOURCE", id, "ALERT_SOURCE_KEY_ROTATED",
                null, source, "轮换告警接入密钥；旧密钥立即失效");
        return new SourceCredential(source, secret);
    }

    public AlertSource authenticate(String sourceKey, String secret) {
        String normalizedKey = normalize(sourceKey);
        AlertSource source = normalizedKey == null ? null : sourceMapper.selectOne(new LambdaQueryWrapper<AlertSource>()
                .eq(AlertSource::getSourceKey, normalizedKey)
                .eq(AlertSource::getEnabled, true));
        if (source == null || secret == null || !passwordEncoder.matches(secret, source.getApiKeyHash())) {
            throw new BusinessException("告警接入凭证无效或已停用");
        }
        return source;
    }

    @Transactional
    public void markReceived(AlertSource source, LocalDateTime receivedAt) {
        source.setLastReceivedAt(receivedAt);
        source.setUpdatedAt(receivedAt);
        sourceMapper.updateById(source);
    }

    public List<AlertRule> listRules(Long tenantId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getTenantId, tenantId)
                .orderByAsc(AlertRule::getSortOrder)
                .orderByAsc(AlertRule::getId));
    }

    @Transactional
    public AlertRule createRule(RuleCommand command, Long actorId, Long tenantId) {
        validateRuleCommand(command, null, tenantId);
        LocalDateTime now = LocalDateTime.now();
        AlertRule rule = new AlertRule();
        rule.setTenantId(tenantId);
        applyRuleCommand(rule, command);
        rule.setCreatedBy(actorId);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        try {
            ruleMapper.insert(rule);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("告警规则名称已存在，请更换后重试");
        }
        auditLogService.record(tenantId, actorId, "ALERT_RULE", rule.getId(), "ALERT_RULE_CREATED",
                null, rule, "创建监控告警自动化规则");
        return rule;
    }

    @Transactional
    public AlertRule updateRule(Long id, RuleCommand command, Long actorId, Long tenantId) {
        AlertRule rule = findRule(id, tenantId);
        validateRuleCommand(command, id, tenantId);
        AlertRule before = copy(rule);
        applyRuleCommand(rule, command);
        rule.setUpdatedAt(LocalDateTime.now());
        try {
            ruleMapper.updateById(rule);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("告警规则名称已存在，请更换后重试");
        }
        auditLogService.record(tenantId, actorId, "ALERT_RULE", id, "ALERT_RULE_UPDATED",
                before, rule, "更新监控告警自动化规则");
        return rule;
    }

    public AlertRule matchRule(Long tenantId, Long sourceId, AlertSeverity severity) {
        return listRules(tenantId).stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .filter(rule -> rule.getSourceId() == null || rule.getSourceId().equals(sourceId))
                .filter(rule -> severity != null && severity.atLeast(rule.getMinimumSeverity()))
                .findFirst()
                .orElse(null);
    }

    public List<AlertSilence> listSilences(Long tenantId) {
        return silenceMapper.selectList(new LambdaQueryWrapper<AlertSilence>()
                .eq(AlertSilence::getTenantId, tenantId)
                .orderByDesc(AlertSilence::getEndsAt)
                .orderByDesc(AlertSilence::getId));
    }

    @Transactional
    public AlertSilence createSilence(SilenceCommand command, Long actorId, Long tenantId) {
        validateSilenceCommand(command, null, tenantId);
        LocalDateTime now = LocalDateTime.now();
        AlertSilence silence = new AlertSilence();
        silence.setTenantId(tenantId);
        applySilenceCommand(silence, command);
        silence.setCreatedBy(actorId);
        silence.setCreatedAt(now);
        silence.setUpdatedAt(now);
        try {
            silenceMapper.insert(silence);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("静默窗口名称已存在，请更换后重试");
        }
        auditLogService.record(tenantId, actorId, "ALERT_SILENCE", silence.getId(), "ALERT_SILENCE_CREATED",
                null, silence, "创建监控告警静默窗口");
        return silence;
    }

    @Transactional
    public AlertSilence updateSilence(Long id, SilenceCommand command, Long actorId, Long tenantId) {
        AlertSilence silence = findSilence(id, tenantId);
        validateSilenceCommand(command, id, tenantId);
        AlertSilence before = copy(silence);
        applySilenceCommand(silence, command);
        silence.setUpdatedAt(LocalDateTime.now());
        try {
            silenceMapper.updateById(silence);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("静默窗口名称已存在，请更换后重试");
        }
        auditLogService.record(tenantId, actorId, "ALERT_SILENCE", id, "ALERT_SILENCE_UPDATED",
                before, silence, "更新监控告警静默窗口");
        return silence;
    }

    @Transactional
    public void deleteSilence(Long id, Long actorId, Long tenantId) {
        AlertSilence silence = findSilence(id, tenantId);
        silenceMapper.deleteById(id);
        auditLogService.record(tenantId, actorId, "ALERT_SILENCE", id, "ALERT_SILENCE_DELETED",
                silence, null, "删除监控告警静默窗口");
    }

    public AlertSilence activeSilence(Long tenantId, Long sourceId, Long assetId,
                                      String fingerprint, LocalDateTime at) {
        return silenceMapper.selectList(new LambdaQueryWrapper<AlertSilence>()
                        .eq(AlertSilence::getTenantId, tenantId)
                        .eq(AlertSilence::getEnabled, true)
                        .le(AlertSilence::getStartsAt, at)
                        .gt(AlertSilence::getEndsAt, at)
                        .orderByAsc(AlertSilence::getEndsAt)
                        .orderByAsc(AlertSilence::getId))
                .stream()
                .filter(item -> item.getSourceId() == null || item.getSourceId().equals(sourceId))
                .filter(item -> item.getAssetId() == null || item.getAssetId().equals(assetId))
                .filter(item -> item.getFingerprintPrefix() == null
                        || fingerprint.startsWith(item.getFingerprintPrefix()))
                .findFirst()
                .orElse(null);
    }

    public AlertSource findSource(Long id, Long tenantId) {
        AlertSource source = sourceMapper.selectOne(new LambdaQueryWrapper<AlertSource>()
                .eq(AlertSource::getId, id)
                .eq(AlertSource::getTenantId, tenantId));
        if (source == null) throw new BusinessException("告警源不存在");
        return source;
    }

    private AlertRule findRule(Long id, Long tenantId) {
        AlertRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getId, id)
                .eq(AlertRule::getTenantId, tenantId));
        if (rule == null) throw new BusinessException("告警规则不存在");
        return rule;
    }

    private AlertSilence findSilence(Long id, Long tenantId) {
        AlertSilence silence = silenceMapper.selectOne(new LambdaQueryWrapper<AlertSilence>()
                .eq(AlertSilence::getId, id)
                .eq(AlertSilence::getTenantId, tenantId));
        if (silence == null) throw new BusinessException("静默窗口不存在");
        return silence;
    }

    private void validateSourceCommand(SourceCommand command, Long ignoreId, Long tenantId) {
        if (command == null || normalize(command.name()) == null || normalize(command.name()).length() > 100) {
            throw new BusinessException("告警源名称不能为空且不能超过 100 个字符");
        }
        validateLength(command.description(), 1000, "告警源说明");
        if (command.defaultAssetId() != null) cmdbAssetService.get(command.defaultAssetId(), tenantId);
        LambdaQueryWrapper<AlertSource> duplicate = new LambdaQueryWrapper<AlertSource>()
                .eq(AlertSource::getTenantId, tenantId)
                .eq(AlertSource::getName, normalize(command.name()));
        if (ignoreId != null) duplicate.ne(AlertSource::getId, ignoreId);
        if (sourceMapper.selectCount(duplicate) > 0) throw new BusinessException("当前租户已存在同名告警源");
    }

    private void validateRuleCommand(RuleCommand command, Long ignoreId, Long tenantId) {
        if (command == null || normalize(command.name()) == null || normalize(command.name()).length() > 100) {
            throw new BusinessException("告警规则名称不能为空且不能超过 100 个字符");
        }
        validateLength(command.description(), 1000, "告警规则说明");
        if (command.action() == null) throw new BusinessException("请选择告警规则动作");
        if (command.sourceId() != null) findSource(command.sourceId(), tenantId);
        if (command.scheduleId() != null) {
            OnCallSchedule schedule = scheduleMapper.selectOne(new LambdaQueryWrapper<OnCallSchedule>()
                    .eq(OnCallSchedule::getId, command.scheduleId())
                    .eq(OnCallSchedule::getTenantId, tenantId));
            if (schedule == null) throw new BusinessException("规则关联的值班表不存在");
        }
        LambdaQueryWrapper<AlertRule> duplicate = new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getTenantId, tenantId)
                .eq(AlertRule::getName, normalize(command.name()));
        if (ignoreId != null) duplicate.ne(AlertRule::getId, ignoreId);
        if (ruleMapper.selectCount(duplicate) > 0) throw new BusinessException("当前租户已存在同名告警规则");
    }

    private void validateSilenceCommand(SilenceCommand command, Long ignoreId, Long tenantId) {
        if (command == null || normalize(command.name()) == null || normalize(command.name()).length() > 100) {
            throw new BusinessException("静默窗口名称不能为空且不能超过 100 个字符");
        }
        if (command.startsAt() == null || command.endsAt() == null || !command.endsAt().isAfter(command.startsAt())) {
            throw new BusinessException("静默窗口结束时间必须晚于开始时间");
        }
        String prefix = normalize(command.fingerprintPrefix());
        if (command.sourceId() == null && command.assetId() == null && prefix == null) {
            throw new BusinessException("静默窗口至少需要限定告警源、资产或指纹前缀之一");
        }
        if (prefix != null && prefix.length() > 128) throw new BusinessException("指纹前缀不能超过 128 个字符");
        String reason = normalize(command.reason());
        if (reason == null || reason.length() > 1000) throw new BusinessException("静默原因不能为空且不能超过 1000 个字符");
        if (command.sourceId() != null) findSource(command.sourceId(), tenantId);
        if (command.assetId() != null) cmdbAssetService.get(command.assetId(), tenantId);
        LambdaQueryWrapper<AlertSilence> duplicate = new LambdaQueryWrapper<AlertSilence>()
                .eq(AlertSilence::getTenantId, tenantId)
                .eq(AlertSilence::getName, normalize(command.name()));
        if (ignoreId != null) duplicate.ne(AlertSilence::getId, ignoreId);
        if (silenceMapper.selectCount(duplicate) > 0) throw new BusinessException("当前租户已存在同名静默窗口");
    }

    private void applyRuleCommand(AlertRule rule, RuleCommand command) {
        rule.setName(normalize(command.name()));
        rule.setDescription(normalize(command.description()));
        rule.setSourceId(command.sourceId());
        rule.setMinimumSeverity(command.minimumSeverity() == null ? AlertSeverity.HIGH : command.minimumSeverity());
        rule.setAction(command.action());
        rule.setScheduleId(command.scheduleId());
        rule.setSortOrder(command.sortOrder() == null ? 100 : Math.max(0, Math.min(command.sortOrder(), 9999)));
        rule.setEnabled(command.enabled() == null || command.enabled());
    }

    private void applySilenceCommand(AlertSilence silence, SilenceCommand command) {
        silence.setName(normalize(command.name()));
        silence.setSourceId(command.sourceId());
        silence.setAssetId(command.assetId());
        silence.setFingerprintPrefix(normalize(command.fingerprintPrefix()));
        silence.setStartsAt(command.startsAt());
        silence.setEndsAt(command.endsAt());
        silence.setReason(normalize(command.reason()));
        silence.setEnabled(command.enabled() == null || command.enabled());
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "of_alert_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private void validateLength(String value, int max, String field) {
        if (value != null && value.trim().length() > max) throw new BusinessException(field + "不能超过 " + max + " 个字符");
    }

    private AlertSource copy(AlertSource source) {
        AlertSource result = new AlertSource();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setName(source.getName());
        result.setDescription(source.getDescription());
        result.setSourceKey(source.getSourceKey());
        result.setApiKeyHash(source.getApiKeyHash());
        result.setDefaultAssetId(source.getDefaultAssetId());
        result.setEnabled(source.getEnabled());
        result.setCreatedBy(source.getCreatedBy());
        result.setLastReceivedAt(source.getLastReceivedAt());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private AlertRule copy(AlertRule source) {
        AlertRule result = new AlertRule();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setName(source.getName());
        result.setDescription(source.getDescription());
        result.setSourceId(source.getSourceId());
        result.setMinimumSeverity(source.getMinimumSeverity());
        result.setAction(source.getAction());
        result.setScheduleId(source.getScheduleId());
        result.setSortOrder(source.getSortOrder());
        result.setEnabled(source.getEnabled());
        result.setCreatedBy(source.getCreatedBy());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private AlertSilence copy(AlertSilence source) {
        AlertSilence result = new AlertSilence();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setName(source.getName());
        result.setSourceId(source.getSourceId());
        result.setAssetId(source.getAssetId());
        result.setFingerprintPrefix(source.getFingerprintPrefix());
        result.setStartsAt(source.getStartsAt());
        result.setEndsAt(source.getEndsAt());
        result.setReason(source.getReason());
        result.setEnabled(source.getEnabled());
        result.setCreatedBy(source.getCreatedBy());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    public record SourceCommand(String name, String description, Long defaultAssetId, Boolean enabled) { }
    public record SourceCredential(AlertSource source, String ingestKey) { }
    public record RuleCommand(String name, String description, Long sourceId, AlertSeverity minimumSeverity,
                              AlertRuleAction action, Long scheduleId, Integer sortOrder, Boolean enabled) { }
    public record SilenceCommand(String name, Long sourceId, Long assetId, String fingerprintPrefix,
                                 LocalDateTime startsAt, LocalDateTime endsAt, String reason, Boolean enabled) { }
}
