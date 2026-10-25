package com.opsflow.recovery;

import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backup-agent")
@RequiredArgsConstructor
public class BackupAgentCallbackController {
    private final BackupAgentService service;

    @PostMapping("/callbacks")
    public ResponseEntity<ApiResponse<BackupAgentService.CallbackResult>> callback(
            @RequestHeader(value = "X-OpsFlow-Agent-Key", required = false) String agentKey,
            @RequestHeader(value = "X-OpsFlow-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-OpsFlow-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-OpsFlow-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        BackupAgentService.CallbackResult result = service.receive(agentKey, timestamp, nonce, signature, rawBody);
        return ResponseEntity.ok(ApiResponse.ok(result,
                result.idempotent() ? "重复事件已幂等返回" : "备份回调已验签并登记"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> callbackRejected(BusinessException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, null, exception.getMessage()));
    }
}
