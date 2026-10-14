package com.opsflow.security;

import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongPassphraseWithoutArbitraryCompositionRules() {
        assertDoesNotThrow(() -> policy.validate("correct horse battery staple", "operator"));
    }

    @Test
    void rejectsCommonPassword() {
        assertThrows(BusinessException.class, () -> policy.validate("password123", "operator"));
    }

    @Test
    void rejectsPasswordContainingUsername() {
        assertThrows(BusinessException.class, () -> policy.validate("MyOperatorPassphrase", "operator"));
    }

    @Test
    void rejectsShortPassword() {
        assertThrows(BusinessException.class, () -> policy.validate("short", "operator"));
    }
}
