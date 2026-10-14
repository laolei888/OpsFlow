package com.opsflow.ticket;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TicketImportService {
    static final long MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_ROWS = 500;

    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("标题", "title"),
            Map.entry("工单标题", "title"),
            Map.entry("title", "title"),
            Map.entry("描述", "description"),
            Map.entry("问题描述", "description"),
            Map.entry("description", "description"),
            Map.entry("分类", "category"),
            Map.entry("category", "category"),
            Map.entry("优先级", "priority"),
            Map.entry("priority", "priority")
    );

    private final TicketService ticketService;
    private final AuditLogService auditLogService;

    public ImportResult importCsv(MultipartFile file, Long requesterId, Long tenantId) {
        validateFile(file);
        String content = readContent(file);
        List<List<String>> rows = parseCsv(content);
        if (rows.size() < 2) {
            throw new BusinessException("CSV 至少需要一行表头和一行数据");
        }

        Map<String, Integer> headers = resolveHeaders(rows.get(0));
        List<List<String>> dataRows = rows.subList(1, rows.size());
        if (dataRows.size() > MAX_ROWS) {
            throw new BusinessException("单次最多导入 " + MAX_ROWS + " 条工单");
        }

        int success = 0;
        int failed = 0;
        List<ImportError> errors = new ArrayList<>();
        for (int i = 0; i < dataRows.size(); i++) {
            int rowNumber = i + 2;
            List<String> row = dataRows.get(i);
            try {
                Ticket ticket = toTicket(row, headers);
                ticketService.create(ticket, requesterId, tenantId, idempotencyKey(tenantId, requesterId, ticket));
                success++;
            } catch (BusinessException ex) {
                failed++;
                if (errors.size() < 50) {
                    errors.add(new ImportError(rowNumber, ex.getMessage()));
                }
            } catch (RuntimeException ex) {
                failed++;
                if (errors.size() < 50) {
                    errors.add(new ImportError(rowNumber, "系统处理失败，请检查该行数据"));
                }
            }
        }

        auditLogService.record(tenantId, requesterId, "TICKET_IMPORT", null, "IMPORT_GENERATED",
                null, Map.of("totalRows", dataRows.size(), "successRows", success, "failedRows", failed),
                "批量导入工单：成功 " + success + " 条，失败 " + failed + " 条");
        return new ImportResult(dataRows.size(), success, failed, List.copyOf(errors));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择 CSV 文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException("CSV 文件不能超过 2MB");
        }
    }

    private String readContent(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8)
                    .replaceFirst("^\\uFEFF", "");
        } catch (IOException ex) {
            throw new BusinessException("读取 CSV 文件失败");
        }
    }

    private Map<String, Integer> resolveHeaders(List<String> headerRow) {
        Map<String, Integer> headers = new LinkedHashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            String alias = HEADER_ALIASES.get(normalize(headerRow.get(i)));
            if (alias != null) {
                headers.putIfAbsent(alias, i);
            }
        }
        if (!headers.containsKey("title")) {
            throw new BusinessException("CSV 缺少“标题”列");
        }
        if (!headers.containsKey("description")) {
            throw new BusinessException("CSV 缺少“描述”列");
        }
        return headers;
    }

    private Ticket toTicket(List<String> row, Map<String, Integer> headers) {
        String title = value(row, headers, "title");
        String description = value(row, headers, "description");
        String category = value(row, headers, "category");
        String priorityValue = value(row, headers, "priority");

        if (title.isBlank()) throw new BusinessException("标题不能为空");
        if (title.length() > 160) throw new BusinessException("标题不能超过 160 个字符");
        if (description.isBlank()) throw new BusinessException("描述不能为空");
        if (category.length() > 64) throw new BusinessException("分类不能超过 64 个字符");

        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setCategory(category.isBlank() ? null : category);
        ticket.setPriority(parsePriority(priorityValue));
        return ticket;
    }

    private TicketPriority parsePriority(String value) {
        if (value == null || value.isBlank()) return TicketPriority.MEDIUM;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "LOW", "低" -> TicketPriority.LOW;
            case "MEDIUM", "中" -> TicketPriority.MEDIUM;
            case "HIGH", "高" -> TicketPriority.HIGH;
            case "URGENT", "紧急" -> TicketPriority.URGENT;
            default -> throw new BusinessException("优先级只能填写 LOW/MEDIUM/HIGH/URGENT 或 低/中/高/紧急");
        };
    }

    private String value(List<String> row, Map<String, Integer> headers, String key) {
        Integer index = headers.get(key);
        if (index == null || index >= row.size()) return "";
        return row.get(index) == null ? "" : row.get(index).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String idempotencyKey(Long tenantId, Long requesterId, Ticket ticket) {
        String source = tenantId + "|" + requesterId + "|" + ticket.getTitle() + "|"
                + ticket.getDescription() + "|" + ticket.getCategory() + "|" + ticket.getPriority();
        return "import-" + sha256(source);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    static List<List<String>> parseCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (quoted) {
                if (current == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(current);
                }
                continue;
            }

            if (current == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (current == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (current == '\n') {
                row.add(cell.toString());
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
            } else if (current == '\r') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }

        if (quoted) throw new BusinessException("CSV 存在未闭合的引号");
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows.stream().filter(items -> items.stream().anyMatch(item -> item != null && !item.isBlank())).toList();
    }

    public record ImportResult(int totalRows, int successRows, int failedRows, List<ImportError> errors) {
    }

    public record ImportError(int rowNumber, String message) {
    }
}