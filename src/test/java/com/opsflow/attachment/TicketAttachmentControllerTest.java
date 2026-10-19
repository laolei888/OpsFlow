package com.opsflow.attachment;

import com.opsflow.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TicketAttachmentControllerTest {
    @Test
    void downloadGateUsesConflictStatusInsteadOfSuccessfulFileResponse() {
        TicketAttachmentController controller = new TicketAttachmentController(null);

        ResponseEntity<ApiResponse<Void>> response = controller.handleDownloadBlocked(
                new AttachmentDownloadBlockedException("附件检测到安全威胁，已被隔离"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().success());
        assertEquals("附件检测到安全威胁，已被隔离", response.getBody().message());
    }
}
