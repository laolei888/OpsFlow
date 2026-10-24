package com.opsflow.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.auth.AuthResponse;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.User;
import com.opsflow.common.BusinessException;
import com.opsflow.security.OpsFlowSecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class OidcLoginExchangeService {
    private final OidcLoginExchangeMapper mapper;
    private final AuthService authService;
    private final OpsFlowSecurityProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        OidcLoginExchange exchange = new OidcLoginExchange();
        exchange.setCodeHash(hash(code));
        exchange.setTenantId(user.getTenantId());
        exchange.setUserId(user.getId());
        exchange.setExpiresAt(now.plus(properties.getOidc().getExchangeTtl()));
        exchange.setCreatedAt(now);
        mapper.insert(exchange);
        return code;
    }

    @Transactional
    public AuthResponse consume(String code) {
        if (code == null || code.isBlank() || code.length() > 200) {
            throw new BusinessException("单点登录交换码无效");
        }
        OidcLoginExchange exchange = mapper.selectForUpdate(hash(code.trim()));
        LocalDateTime now = LocalDateTime.now();
        if (exchange == null || exchange.getConsumedAt() != null || !exchange.getExpiresAt().isAfter(now)) {
            throw new BusinessException("单点登录交换码无效或已过期，请重新登录");
        }
        exchange.setConsumedAt(now);
        mapper.updateById(exchange);
        return authService.issueResponse(exchange.getUserId());
    }

    @Scheduled(fixedDelayString = "${opsflow.security.oidc.cleanup-interval-ms:3600000}",
            initialDelayString = "${opsflow.security.oidc.cleanup-initial-delay-ms:60000}")
    public void cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getOidc().getExchangeRetention());
        mapper.delete(new LambdaQueryWrapper<OidcLoginExchange>()
                .lt(OidcLoginExchange::getExpiresAt, cutoff));
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
