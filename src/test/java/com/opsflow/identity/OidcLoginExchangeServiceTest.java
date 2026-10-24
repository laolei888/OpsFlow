package com.opsflow.identity;

import com.opsflow.auth.AuthResponse;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.User;
import com.opsflow.common.BusinessException;
import com.opsflow.security.OpsFlowSecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OidcLoginExchangeServiceTest {
    @Mock private OidcLoginExchangeMapper mapper;
    @Mock private AuthService authService;

    @Test
    void issuesOpaqueCodeAndStoresOnlyItsHash() {
        User user = new User();
        user.setId(9L);
        user.setTenantId(3L);
        OidcLoginExchangeService service = service();

        String code = service.issue(user);

        ArgumentCaptor<OidcLoginExchange> exchange = ArgumentCaptor.forClass(OidcLoginExchange.class);
        verify(mapper).insert(exchange.capture());
        assertThat(code).hasSizeGreaterThan(30);
        assertThat(exchange.getValue().getCodeHash()).hasSize(64).doesNotContain(code);
        assertThat(exchange.getValue().getExpiresAt()).isAfter(exchange.getValue().getCreatedAt());
    }

    @Test
    void consumesValidCodeOnlyOnceAndReturnsOpsFlowJwt() {
        OidcLoginExchange exchange = new OidcLoginExchange();
        exchange.setId(2L);
        exchange.setUserId(9L);
        exchange.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(mapper.selectForUpdate(anyString())).thenReturn(exchange);
        AuthResponse response = new AuthResponse("jwt", 7200, 9L, "agent", "Agent", 3L,
                List.of("AGENT"), com.opsflow.auth.AuthSource.OIDC, "agent@example.com");
        when(authService.issueResponse(9L)).thenReturn(response);

        AuthResponse result = service().consume("opaque-code");

        assertThat(result.token()).isEqualTo("jwt");
        assertThat(exchange.getConsumedAt()).isNotNull();
        verify(mapper).updateById(exchange);
    }

    @Test
    void rejectsExpiredCode() {
        OidcLoginExchange exchange = new OidcLoginExchange();
        exchange.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectForUpdate(anyString())).thenReturn(exchange);

        assertThrows(BusinessException.class, () -> service().consume("expired-code"));
    }

    private OidcLoginExchangeService service() {
        return new OidcLoginExchangeService(mapper, authService, new OpsFlowSecurityProperties());
    }
}
