package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketIdempotencyCleanupJobTest {
    @Mock
    private TicketIdempotencyKeyMapper idempotencyKeyMapper;

    @InjectMocks
    private TicketIdempotencyCleanupJob cleanupJob;

    @Test
    void deletesKeysOlderThanCutoff() {
        when(idempotencyKeyMapper.delete(any(Wrapper.class))).thenReturn(4);

        assertEquals(4, cleanupJob.cleanupExpired(LocalDateTime.of(2026, 9, 20, 10, 0)));
    }
}
