package com.opsflow.ticket;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketImportServiceTest {
    @Mock
    private TicketService ticketService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TicketImportService ticketImportService;

    @Test
    void importsQuotedCsvRowsAndMapsChinesePriority() {
        String csv = "\uFEFF标题,描述,分类,优先级\n"
                + "\"VPN, 无法连接\",\"第一行\n第二行\",网络,高\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        when(ticketService.create(any(Ticket.class), eq(7L), eq(3L), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TicketImportService.ImportResult result = ticketImportService.importCsv(file, 7L, 3L);

        assertEquals(1, result.totalRows());
        assertEquals(1, result.successRows());
        assertEquals(0, result.failedRows());
        assertTrue(result.errors().isEmpty());

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketService).create(captor.capture(), eq(7L), eq(3L), anyString());
        assertEquals("VPN, 无法连接", captor.getValue().getTitle());
        assertEquals("第一行\n第二行", captor.getValue().getDescription());
        assertEquals(TicketPriority.HIGH, captor.getValue().getPriority());
        verify(auditLogService).record(eq(3L), eq(7L), eq("TICKET_IMPORT"), eq(null),
                eq("IMPORT_GENERATED"), eq(null), any(), anyString());
    }

    @Test
    void keepsProcessingRowsAndReturnsValidationErrors() {
        String csv = "title,description,category,priority\n"
                + ",没有标题,网络,HIGH\n"
                + "合法工单,描述,账号,INVALID\n"
                + "可以导入,描述,网络,URGENT\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        when(ticketService.create(any(Ticket.class), eq(7L), eq(3L), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TicketImportService.ImportResult result = ticketImportService.importCsv(file, 7L, 3L);

        assertEquals(3, result.totalRows());
        assertEquals(1, result.successRows());
        assertEquals(2, result.failedRows());
        assertEquals(2, result.errors().size());
        assertEquals(2, result.errors().get(0).rowNumber());
        assertEquals(3, result.errors().get(1).rowNumber());
        verify(ticketService, times(1)).create(any(Ticket.class), eq(7L), eq(3L), anyString());
    }

    @Test
    void rejectsCsvWithoutRequiredHeaders() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv",
                "分类,优先级\n网络,HIGH\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class,
                () -> ticketImportService.importCsv(file, 7L, 3L));
    }

    @Test
    void parserRejectsUnclosedQuotedField() {
        assertThrows(BusinessException.class,
                () -> TicketImportService.parseCsv("标题,描述\n\"未结束,内容"));
    }

    @Test
    void rejectsWrongExtensionAndBinaryCsvPayload() {
        MockMultipartFile wrongExtension = new MockMultipartFile(
                "file", "tickets.txt", "text/plain", "title,description\na,b".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile binary = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", new byte[]{'t', 'i', 't', 'l', 'e', 0, 'x'});

        assertThrows(BusinessException.class,
                () -> ticketImportService.importCsv(wrongExtension, 7L, 3L));
        assertThrows(BusinessException.class,
                () -> ticketImportService.importCsv(binary, 7L, 3L));
    }
}
