package com.opsflow.recovery;

import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BackupAgentCallbackControllerTest {
    @Mock private BackupAgentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BackupAgentCallbackController(service)).build();
    }

    @Test
    void signedCallbackSuccessUsesHttp200() throws Exception {
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.setId(81L);
        when(service.receive(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new BackupAgentService.CallbackResult(true, false, "evt-10000001", snapshot));

        mvc.perform(post(BackupAgentService.CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-OpsFlow-Agent-Key", "bka_test")
                        .header("X-OpsFlow-Timestamp", "1792935000")
                        .header("X-OpsFlow-Nonce", "nonce-10000001")
                        .header("X-OpsFlow-Signature", "a".repeat(64))
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId").value("evt-10000001"));
    }

    @Test
    void rejectedCallbackUsesNon2xxHttpStatus() throws Exception {
        when(service.receive(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException("回调签名无效"));

        mvc.perform(post(BackupAgentService.CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-OpsFlow-Agent-Key", "bka_test")
                        .header("X-OpsFlow-Timestamp", "1792935000")
                        .header("X-OpsFlow-Nonce", "nonce-10000002")
                        .header("X-OpsFlow-Signature", "b".repeat(64))
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("回调签名无效"));
    }
}
