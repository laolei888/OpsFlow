package com.opsflow.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AccountBootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountBootstrapRunner.class);

    private final AuthService authService;

    @Value("${opsflow.bootstrap.username:}")
    private String username;
    @Value("${opsflow.bootstrap.password:}")
    private String password;
    @Value("${opsflow.bootstrap.display-name:OpsFlow 客服}")
    private String displayName;
    @Value("${opsflow.bootstrap.role:AGENT}")
    private String role;
    @Value("${opsflow.bootstrap.tenant-id:1}")
    private Long tenantId;

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(username) && !StringUtils.hasText(password)) return;
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalStateException("OPSFLOW_BOOTSTRAP_USERNAME 和 OPSFLOW_BOOTSTRAP_PASSWORD 必须同时配置");
        }
        boolean created = authService.ensureBootstrapAccount(username, password, displayName, role, tenantId);
        if (created) {
            log.info("OpsFlow bootstrap account created: username={}, role={}, tenantId={}", username, role, tenantId);
        } else {
            log.info("OpsFlow bootstrap account already exists: username={}", username);
        }
    }
}