package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketCommentServiceTest {
    @Mock
    private TicketCommentMapper commentMapper;

    @Mock
    private TicketService ticketService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TicketCommentService commentService;

    @Test
    void savesPublicReplyByDefault() {
        when(commentMapper.insert(any(TicketComment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TicketComment.class).setId(10L);
            return 1;
        });

        TicketComment comment = commentService.create(1L, "  已受理  ",
                TicketCommentVisibility.PUBLIC, false, 7L, 3L);

        assertEquals("已受理", comment.getContent());
        assertEquals(TicketCommentVisibility.PUBLIC, comment.getVisibility());
        verify(commentMapper).insert(comment);
    }

    @Test
    void rejectsInternalNoteFromRequester() {
        assertThrows(BusinessException.class, () -> commentService.create(1L, "仅内部可见",
                TicketCommentVisibility.INTERNAL, false, 7L, 3L));

        verify(commentMapper, never()).insert(any(TicketComment.class));
    }
}