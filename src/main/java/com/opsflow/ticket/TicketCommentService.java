package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TicketCommentService {
    private final TicketCommentMapper commentMapper;
    private final TicketService ticketService;
    private final AuditLogService auditLogService;

    public List<TicketComment> list(Long ticketId, Long tenantId) {
        return list(ticketId, tenantId, true);
    }

    public List<TicketComment> list(Long ticketId, Long tenantId, boolean includeInternal) {
        return list(ticketId, tenantId, includeInternal, null);
    }

    public List<TicketComment> list(Long ticketId, Long tenantId, boolean includeInternal, Long requesterId) {
        ticketService.getVisible(ticketId, tenantId, requesterId);
        LambdaQueryWrapper<TicketComment> query = new LambdaQueryWrapper<TicketComment>()
                .eq(TicketComment::getTenantId, tenantId)
                .eq(TicketComment::getTicketId, ticketId)
                .orderByAsc(TicketComment::getCreatedAt);
        if (!includeInternal) query.eq(TicketComment::getVisibility, TicketCommentVisibility.PUBLIC);
        return commentMapper.selectList(query);
    }

    @Transactional
    public TicketComment create(Long ticketId, String content, Long authorId, Long tenantId) {
        return create(ticketId, content, TicketCommentVisibility.PUBLIC, true, authorId, tenantId);
    }

    @Transactional
    public TicketComment create(Long ticketId, String content, TicketCommentVisibility visibility,
                                boolean canCreateInternal, Long authorId, Long tenantId) {
        return create(ticketId, content, visibility, canCreateInternal, authorId, null, tenantId);
    }

    @Transactional
    public TicketComment create(Long ticketId, String content, TicketCommentVisibility visibility,
                                boolean canCreateInternal, Long authorId, Long requesterId, Long tenantId) {
        ticketService.getVisible(ticketId, tenantId, requesterId);
        TicketCommentVisibility resolvedVisibility = visibility == null
                ? TicketCommentVisibility.PUBLIC : visibility;
        if (resolvedVisibility == TicketCommentVisibility.INTERNAL && !canCreateInternal) {
            throw new BusinessException("当前角色不能创建内部备注");
        }

        TicketComment comment = new TicketComment();
        comment.setTenantId(tenantId);
        comment.setTicketId(ticketId);
        comment.setAuthorId(authorId);
        comment.setContent(content.trim());
        comment.setVisibility(resolvedVisibility);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(comment.getCreatedAt());
        commentMapper.insert(comment);
        auditLogService.record(tenantId, authorId, "TICKET", ticketId, "COMMENT_ADDED",
                null, Map.of("commentId", comment.getId(), "visibility", resolvedVisibility.name(),
                        "content", comment.getContent()), "新增工单" + (resolvedVisibility == TicketCommentVisibility.INTERNAL ? "内部备注" : "评论"));
        return comment;
    }
}