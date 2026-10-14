package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TicketSavedViewService {
    private static final String PRIVATE = "PRIVATE";
    private static final String TEAM = "TEAM";

    private final TicketSavedViewMapper viewMapper;
    private final AuditLogService auditLogService;

    public List<TicketSavedViewView> list(Long tenantId, Long ownerId, boolean staff) {
        LambdaQueryWrapper<TicketSavedView> query = new LambdaQueryWrapper<TicketSavedView>()
                .eq(TicketSavedView::getTenantId, tenantId)
                .orderByAsc(TicketSavedView::getVisibility)
                .orderByDesc(TicketSavedView::getUpdatedAt)
                .orderByAsc(TicketSavedView::getName);
        if (staff) {
            query.and(wrapper -> wrapper.eq(TicketSavedView::getOwnerId, ownerId)
                    .or().eq(TicketSavedView::getVisibility, TEAM));
        } else {
            query.eq(TicketSavedView::getOwnerId, ownerId);
        }
        return viewMapper.selectList(query).stream().map(this::toView).toList();
    }

    @Transactional
    public TicketSavedViewView create(SavedViewCommand command, Long tenantId, Long ownerId, boolean staff) {
        TicketSavedView view = new TicketSavedView();
        applyCommand(view, command, tenantId, staff);
        if (ownedByName(tenantId, ownerId, view.getName()) != null) {
            throw new BusinessException("你已经保存过同名视图");
        }
        view.setOwnerId(ownerId);
        LocalDateTime now = LocalDateTime.now();
        view.setCreatedAt(now);
        view.setUpdatedAt(now);
        try {
            viewMapper.insert(view);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("视图名称已存在，请换一个名称");
        }
        TicketSavedViewView result = toView(view);
        auditLogService.record(tenantId, ownerId, "TICKET_SAVED_VIEW", view.getId(),
                "SAVED_VIEW_CREATED", null, result, "创建工单保存视图");
        return result;
    }

    @Transactional
    public TicketSavedViewView update(Long id, SavedViewCommand command,
                                      Long tenantId, Long ownerId, boolean staff) {
        TicketSavedView view = findOwned(id, tenantId, ownerId);
        TicketSavedViewView before = toView(view);
        applyCommand(view, command, tenantId, staff);
        TicketSavedView sameName = ownedByName(tenantId, ownerId, view.getName());
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new BusinessException("你已经保存过同名视图");
        }
        view.setUpdatedAt(LocalDateTime.now());
        viewMapper.updateById(view);
        TicketSavedViewView result = toView(view);
        auditLogService.record(tenantId, ownerId, "TICKET_SAVED_VIEW", id,
                "SAVED_VIEW_UPDATED", before, result, "更新工单保存视图");
        return result;
    }

    @Transactional
    public void delete(Long id, Long tenantId, Long ownerId, boolean admin) {
        TicketSavedView view = findManageable(id, tenantId, ownerId, admin);
        viewMapper.deleteById(id);
        auditLogService.record(tenantId, ownerId, "TICKET_SAVED_VIEW", id,
                "SAVED_VIEW_DELETED", toView(view), null, "删除工单保存视图");
    }

    private TicketSavedView findOwned(Long id, Long tenantId, Long ownerId) {
        TicketSavedView view = viewMapper.selectOne(new LambdaQueryWrapper<TicketSavedView>()
                .eq(TicketSavedView::getId, id)
                .eq(TicketSavedView::getTenantId, tenantId)
                .eq(TicketSavedView::getOwnerId, ownerId));
        if (view == null) throw new BusinessException("保存视图不存在或无权操作");
        return view;
    }

    private TicketSavedView findManageable(Long id, Long tenantId, Long ownerId, boolean admin) {
        LambdaQueryWrapper<TicketSavedView> query = new LambdaQueryWrapper<TicketSavedView>()
                .eq(TicketSavedView::getId, id)
                .eq(TicketSavedView::getTenantId, tenantId);
        if (!admin) query.eq(TicketSavedView::getOwnerId, ownerId);
        TicketSavedView view = viewMapper.selectOne(query);
        if (view == null) throw new BusinessException("保存视图不存在或无权操作");
        return view;
    }
    private TicketSavedView ownedByName(Long tenantId, Long ownerId, String name) {
        return viewMapper.selectOne(new LambdaQueryWrapper<TicketSavedView>()
                .eq(TicketSavedView::getTenantId, tenantId)
                .eq(TicketSavedView::getOwnerId, ownerId)
                .eq(TicketSavedView::getName, name));
    }

    private void applyCommand(TicketSavedView view, SavedViewCommand command,
                              Long tenantId, boolean staff) {
        String name = trim(command.name());
        if (name == null || name.isBlank() || name.length() > 60) {
            throw new BusinessException("视图名称不能为空且不能超过 60 个字符");
        }
        String visibility = command.visibility() == null
                ? PRIVATE : command.visibility().trim().toUpperCase(Locale.ROOT);
        if (!PRIVATE.equals(visibility) && !TEAM.equals(visibility)) {
            throw new BusinessException("视图可见范围只能是 PRIVATE 或 TEAM");
        }
        if (TEAM.equals(visibility) && !staff) {
            throw new BusinessException("只有客服或管理员可以创建团队共享视图");
        }

        String keyword = trim(command.keyword());
        if (keyword != null && keyword.length() > 120) {
            throw new BusinessException("关键词不能超过 120 个字符");
        }
        view.setTenantId(tenantId);
        view.setName(name);
        view.setVisibility(visibility);
        view.setStatus(command.status());
        view.setPriority(command.priority());
        view.setSort(command.sort() == null ? TicketSort.CREATED_DESC : command.sort());
        view.setKeyword(keyword);
        view.setOverdue(command.overdue());
        view.setMine(command.mine());
    }

    private TicketSavedViewView toView(TicketSavedView view) {
        return new TicketSavedViewView(view.getId(), view.getTenantId(), view.getOwnerId(),
                view.getName(), view.getVisibility(), view.getStatus(), view.getPriority(), view.getSort(),
                view.getKeyword(), Boolean.TRUE.equals(view.getOverdue()), Boolean.TRUE.equals(view.getMine()),
                view.getCreatedAt(), view.getUpdatedAt());
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

public record SavedViewCommand(String name, String visibility, TicketStatus status,
                                   TicketPriority priority, String keyword,
                                   boolean overdue, boolean mine, TicketSort sort) {
        public SavedViewCommand(String name, String visibility, TicketStatus status,
                                TicketPriority priority, String keyword,
                                boolean overdue, boolean mine) {
            this(name, visibility, status, priority, keyword, overdue, mine, TicketSort.CREATED_DESC);
        }
    }

    public record TicketSavedViewView(Long id, Long tenantId, Long ownerId, String name,
                                      String visibility, TicketStatus status, TicketPriority priority,
                                      TicketSort sort, String keyword, boolean overdue, boolean mine,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}