package com.opsflow.supportgroup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupportGroupService {
    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION);
    private static final Set<TicketPriority> CRITICAL_PRIORITIES = Set.of(
            TicketPriority.HIGH, TicketPriority.URGENT);

    private final SupportGroupMapper groupMapper;
    private final SupportGroupMemberMapper memberMapper;
    private final TicketMapper ticketMapper;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public List<GroupSummary> list(Long tenantId, Boolean enabled, String keyword) {
        LambdaQueryWrapper<SupportGroup> query = new LambdaQueryWrapper<SupportGroup>()
                .eq(SupportGroup::getTenantId, tenantId)
                .orderByDesc(SupportGroup::getEnabled)
                .orderByAsc(SupportGroup::getName);
        if (enabled != null) query.eq(SupportGroup::getEnabled, enabled);
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(nested -> nested.like(SupportGroup::getName, value)
                    .or().like(SupportGroup::getGroupCode, value)
                    .or().like(SupportGroup::getDescription, value));
        }
        List<SupportGroup> groups = groupMapper.selectList(query);
        if (groups.isEmpty()) return List.of();

        List<Long> groupIds = groups.stream().map(SupportGroup::getId).toList();
        List<SupportGroupMember> members = memberMapper.selectList(new LambdaQueryWrapper<SupportGroupMember>()
                .eq(SupportGroupMember::getTenantId, tenantId)
                .in(SupportGroupMember::getGroupId, groupIds)
                .eq(SupportGroupMember::getActive, true));
        List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .in(Ticket::getAssignmentGroupId, groupIds)
                .in(Ticket::getStatus, ACTIVE_STATUSES));

        Map<Long, Long> memberCounts = members.stream().collect(Collectors.groupingBy(
                SupportGroupMember::getGroupId, Collectors.counting()));
        Map<Long, List<Ticket>> ticketsByGroup = tickets.stream().collect(Collectors.groupingBy(
                Ticket::getAssignmentGroupId));
        LocalDateTime now = LocalDateTime.now();
        return groups.stream()
                .map(group -> summary(group, memberCounts.getOrDefault(group.getId(), 0L),
                        ticketsByGroup.getOrDefault(group.getId(), List.of()), now))
                .toList();
    }

    public GroupDetail detail(Long id, Long tenantId) {
        SupportGroup group = find(id, tenantId);
        List<SupportGroupMember> memberships = members(id, tenantId);
        List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .eq(Ticket::getAssignmentGroupId, id)
                .in(Ticket::getStatus, ACTIVE_STATUSES)
                .orderByDesc(Ticket::getPriority)
                .orderByAsc(Ticket::getDueAt)
                .orderByDesc(Ticket::getUpdatedAt)
                .last("LIMIT 200"));

        Map<Long, StaffSummary> staff = authService.staffMembers(tenantId).stream()
                .collect(Collectors.toMap(StaffSummary::id, Function.identity(), (left, right) -> left));
        Map<Long, Long> workloads = tickets.stream()
                .filter(ticket -> ticket.getAssigneeId() != null)
                .collect(Collectors.groupingBy(Ticket::getAssigneeId, Collectors.counting()));
        List<MemberView> memberViews = memberships.stream()
                .map(membership -> new MemberView(membership, staff.get(membership.getUserId()),
                        workloads.getOrDefault(membership.getUserId(), 0L)))
                .toList();
        GroupSummary groupSummary = summary(group, memberships.size(), tickets, LocalDateTime.now());
        return new GroupDetail(groupSummary, memberViews, tickets);
    }

    public void requireActiveMember(Long groupId, Long userId, Long tenantId) {
        find(groupId, tenantId);
        if (!isActiveMember(groupId, userId, tenantId)) {
            throw new BusinessException("该工单属于支持组队列，只能由组内成员领取或被指派");
        }
    }

    @Transactional
    public SupportGroup create(GroupCommand command, Long actorId, Long tenantId) {
        String code = normalizeCode(command.groupCode());
        String name = required(command.name(), "支持组名称", 100);
        validateManager(command.managerId(), tenantId);
        ensureUnique(null, tenantId, code, name);

        LocalDateTime now = LocalDateTime.now();
        SupportGroup group = new SupportGroup();
        group.setTenantId(tenantId);
        group.setGroupCode(code);
        group.setName(name);
        group.setDescription(text(command.description(), "支持组说明", 1000));
        group.setManagerId(command.managerId());
        group.setEnabled(true);
        group.setCreatedBy(actorId);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        try {
            groupMapper.insert(group);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("支持组编码或名称已存在");
        }
        if (command.managerId() != null) {
            ensureMembership(group, command.managerId(), SupportGroupMemberRole.LEAD, actorId);
        }
        auditLogService.record(tenantId, actorId, "SUPPORT_GROUP", group.getId(),
                "SUPPORT_GROUP_CREATED", null, group, "创建支持组 " + group.getName());
        return group;
    }

    @Transactional
    public SupportGroup update(Long id, GroupCommand command, Long actorId, Long tenantId) {
        SupportGroup group = lock(id, tenantId);
        SupportGroup before = copy(group);
        String code = normalizeCode(command.groupCode());
        String name = required(command.name(), "支持组名称", 100);
        validateManager(command.managerId(), tenantId);
        ensureUnique(id, tenantId, code, name);

        group.setGroupCode(code);
        group.setName(name);
        group.setDescription(text(command.description(), "支持组说明", 1000));
        group.setManagerId(command.managerId());
        group.setUpdatedAt(LocalDateTime.now());
        try {
            groupMapper.updateById(group);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("支持组编码或名称已存在");
        }
        if (command.managerId() != null) {
            ensureMembership(group, command.managerId(), SupportGroupMemberRole.LEAD, actorId);
        }
        auditLogService.record(tenantId, actorId, "SUPPORT_GROUP", id,
                "SUPPORT_GROUP_UPDATED", before, group, "更新支持组资料");
        return group;
    }

    @Transactional
    public SupportGroup toggle(Long id, boolean enabled, Long actorId, Long tenantId) {
        SupportGroup group = lock(id, tenantId);
        if (Boolean.valueOf(enabled).equals(group.getEnabled())) return group;
        SupportGroup before = copy(group);
        group.setEnabled(enabled);
        group.setUpdatedAt(LocalDateTime.now());
        groupMapper.updateById(group);
        auditLogService.record(tenantId, actorId, "SUPPORT_GROUP", id,
                "SUPPORT_GROUP_TOGGLED", before, group, enabled ? "启用支持组" : "停用支持组");
        return group;
    }

    @Transactional
    public SupportGroupMember addMember(Long groupId, MemberCommand command, Long actorId, Long tenantId) {
        SupportGroup group = lock(groupId, tenantId);
        if (!authService.isStaffMember(command.userId(), tenantId)) {
            throw new BusinessException("支持组成员必须是当前租户的客服或管理员");
        }
        SupportGroupMemberRole role = command.memberRole() == null
                ? SupportGroupMemberRole.MEMBER : command.memberRole();
        SupportGroupMember existing = membership(groupId, command.userId(), tenantId);
        if (existing != null) {
            SupportGroupMember before = copy(existing);
            existing.setMemberRole(role);
            existing.setActive(true);
            memberMapper.updateById(existing);
            auditLogService.record(tenantId, actorId, "SUPPORT_GROUP", groupId,
                    "SUPPORT_GROUP_MEMBER_UPDATED", before, existing, "更新支持组成员角色");
            return existing;
        }

        SupportGroupMember member = newMembership(group, command.userId(), role, actorId);
        memberMapper.insert(member);
        auditLogService.record(tenantId, actorId, "SUPPORT_GROUP", groupId,
                "SUPPORT_GROUP_MEMBER_ADDED", null, member, "添加支持组成员");
        return member;
    }

    @Transactional
    public void removeMember(Long groupId, Long userId, Long actorId, Long tenantId) {
        SupportGroup group = lock(groupId, tenantId);
        if (userId.equals(group.getManagerId())) {
            throw new BusinessException("请先更换支持组负责人，再移除该成员");
        }
        SupportGroupMember member = membership(groupId, userId, tenantId);
        if (member == null) throw new BusinessException("支持组成员不存在");
        memberMapper.deleteById(member.getId());
        auditLogService.record(tenantId, actorId, "SUPPORT_GROUP", groupId,
                "SUPPORT_GROUP_MEMBER_REMOVED", member, null, "移除支持组成员");
    }

    @Transactional
    public Ticket routeTicket(Long groupId, Long ticketId, Long actorId, Long tenantId) {
        SupportGroup group = lock(groupId, tenantId);
        if (!Boolean.TRUE.equals(group.getEnabled())) throw new BusinessException("停用的支持组不能接收新工单");
        Ticket ticket = ticketMapper.selectForUpdate(ticketId, tenantId);
        if (ticket == null) throw new BusinessException("工单不存在");
        if (!ACTIVE_STATUSES.contains(ticket.getStatus())) throw new BusinessException("已结束的工单不能重新路由");

        Map<String, Object> before = ticketAssignment(ticket);
        if (ticket.getAssigneeId() != null && !isActiveMember(groupId, ticket.getAssigneeId(), tenantId)) {
            ticket.setAssigneeId(null);
        }
        ticket.setAssignmentGroupId(groupId);
        LocalDateTime now = LocalDateTime.now();
        ticket.setUpdatedAt(now);
        if (ticketMapper.updateById(ticket) != 1) throw new BusinessException("工单已被其他人更新，请刷新后重试");

        List<SupportGroupMember> recipients = members(groupId, tenantId);
        for (SupportGroupMember member : recipients) {
            if (member.getUserId().equals(actorId)) continue;
            notificationService.notifyIfAbsent(tenantId, member.getUserId(), "TICKET_GROUP_ROUTED",
                    "支持组收到新工单", "工单 " + ticket.getTicketNo() + " 已进入「" + group.getName() + "」队列。",
                    "TICKET_GROUP_ROUTED:" + ticketId + ":" + groupId + ":" + now);
        }
        auditLogService.record(tenantId, actorId, "TICKET", ticketId,
                "TICKET_GROUP_ROUTED", before, ticketAssignment(ticket),
                "工单路由至支持组 " + group.getName());
        return ticket;
    }

    @Transactional
    public Ticket unrouteTicket(Long groupId, Long ticketId, Long actorId, Long tenantId) {
        SupportGroup group = lock(groupId, tenantId);
        Ticket ticket = ticketMapper.selectForUpdate(ticketId, tenantId);
        if (ticket == null) throw new BusinessException("工单不存在");
        if (!groupId.equals(ticket.getAssignmentGroupId())) throw new BusinessException("工单不在该支持组队列中");
        Map<String, Object> before = ticketAssignment(ticket);
        ticket.setAssignmentGroupId(null);
        ticket.setUpdatedAt(LocalDateTime.now());
        if (ticketMapper.updateById(ticket) != 1) throw new BusinessException("工单已被其他人更新，请刷新后重试");
        auditLogService.record(tenantId, actorId, "TICKET", ticketId,
                "TICKET_GROUP_UNROUTED", before, ticketAssignment(ticket),
                "工单移出支持组 " + group.getName());
        return ticket;
    }

    private GroupSummary summary(SupportGroup group, long memberCount, List<Ticket> tickets, LocalDateTime now) {
        long overdue = tickets.stream().filter(ticket -> ticket.getDueAt() != null && ticket.getDueAt().isBefore(now)).count();
        long unassigned = tickets.stream().filter(ticket -> ticket.getAssigneeId() == null).count();
        long critical = tickets.stream().filter(ticket -> CRITICAL_PRIORITIES.contains(ticket.getPriority())).count();
        return new GroupSummary(group, memberCount, tickets.size(), overdue, unassigned, critical);
    }

    private List<SupportGroupMember> members(Long groupId, Long tenantId) {
        return memberMapper.selectList(new LambdaQueryWrapper<SupportGroupMember>()
                .eq(SupportGroupMember::getTenantId, tenantId)
                .eq(SupportGroupMember::getGroupId, groupId)
                .eq(SupportGroupMember::getActive, true)
                .orderByDesc(SupportGroupMember::getMemberRole)
                .orderByAsc(SupportGroupMember::getJoinedAt));
    }

    private SupportGroupMember membership(Long groupId, Long userId, Long tenantId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<SupportGroupMember>()
                .eq(SupportGroupMember::getTenantId, tenantId)
                .eq(SupportGroupMember::getGroupId, groupId)
                .eq(SupportGroupMember::getUserId, userId));
    }

    private boolean isActiveMember(Long groupId, Long userId, Long tenantId) {
        return memberMapper.selectCount(new LambdaQueryWrapper<SupportGroupMember>()
                .eq(SupportGroupMember::getTenantId, tenantId)
                .eq(SupportGroupMember::getGroupId, groupId)
                .eq(SupportGroupMember::getUserId, userId)
                .eq(SupportGroupMember::getActive, true)) > 0;
    }

    private void ensureMembership(SupportGroup group, Long userId, SupportGroupMemberRole role, Long actorId) {
        SupportGroupMember existing = membership(group.getId(), userId, group.getTenantId());
        if (existing == null) {
            memberMapper.insert(newMembership(group, userId, role, actorId));
        } else if (existing.getMemberRole() != role || !Boolean.TRUE.equals(existing.getActive())) {
            existing.setMemberRole(role);
            existing.setActive(true);
            memberMapper.updateById(existing);
        }
    }

    private SupportGroupMember newMembership(SupportGroup group, Long userId,
                                               SupportGroupMemberRole role, Long actorId) {
        SupportGroupMember member = new SupportGroupMember();
        member.setTenantId(group.getTenantId());
        member.setGroupId(group.getId());
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setActive(true);
        member.setJoinedAt(LocalDateTime.now());
        member.setAddedBy(actorId);
        return member;
    }

    private SupportGroup find(Long id, Long tenantId) {
        SupportGroup group = groupMapper.selectOne(new LambdaQueryWrapper<SupportGroup>()
                .eq(SupportGroup::getId, id)
                .eq(SupportGroup::getTenantId, tenantId));
        if (group == null) throw new BusinessException("支持组不存在");
        return group;
    }

    private SupportGroup lock(Long id, Long tenantId) {
        SupportGroup group = groupMapper.selectForUpdate(id, tenantId);
        if (group == null) throw new BusinessException("支持组不存在");
        return group;
    }

    private void validateManager(Long managerId, Long tenantId) {
        if (managerId != null && !authService.isStaffMember(managerId, tenantId)) {
            throw new BusinessException("支持组负责人必须是当前租户的客服或管理员");
        }
    }

    private void ensureUnique(Long id, Long tenantId, String code, String name) {
        LambdaQueryWrapper<SupportGroup> codeQuery = new LambdaQueryWrapper<SupportGroup>()
                .eq(SupportGroup::getTenantId, tenantId)
                .eq(SupportGroup::getGroupCode, code);
        LambdaQueryWrapper<SupportGroup> nameQuery = new LambdaQueryWrapper<SupportGroup>()
                .eq(SupportGroup::getTenantId, tenantId)
                .eq(SupportGroup::getName, name);
        if (id != null) {
            codeQuery.ne(SupportGroup::getId, id);
            nameQuery.ne(SupportGroup::getId, id);
        }
        if (groupMapper.selectCount(codeQuery) > 0) throw new BusinessException("支持组编码已存在");
        if (groupMapper.selectCount(nameQuery) > 0) throw new BusinessException("支持组名称已存在");
    }

    private String normalizeCode(String value) {
        String code = required(value, "支持组编码", 32).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_-]{1,31}")) {
            throw new BusinessException("支持组编码须以字母开头，仅包含大写字母、数字、下划线或短横线");
        }
        return code;
    }

    private String required(String value, String field, int max) {
        String normalized = text(value, field, max);
        if (normalized == null) throw new BusinessException(field + "不能为空");
        return normalized;
    }

    private String text(String value, String field, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max) throw new BusinessException(field + "不能超过 " + max + " 个字符");
        return normalized;
    }

    private Map<String, Object> ticketAssignment(Ticket ticket) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ticketId", ticket.getId());
        value.put("assignmentGroupId", ticket.getAssignmentGroupId());
        value.put("assigneeId", ticket.getAssigneeId());
        return value;
    }

    private SupportGroup copy(SupportGroup source) {
        SupportGroup result = new SupportGroup();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setGroupCode(source.getGroupCode());
        result.setName(source.getName());
        result.setDescription(source.getDescription());
        result.setManagerId(source.getManagerId());
        result.setEnabled(source.getEnabled());
        result.setCreatedBy(source.getCreatedBy());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private SupportGroupMember copy(SupportGroupMember source) {
        SupportGroupMember result = new SupportGroupMember();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setGroupId(source.getGroupId());
        result.setUserId(source.getUserId());
        result.setMemberRole(source.getMemberRole());
        result.setActive(source.getActive());
        result.setJoinedAt(source.getJoinedAt());
        result.setAddedBy(source.getAddedBy());
        return result;
    }

    public record GroupCommand(String groupCode, String name, String description, Long managerId) { }

    public record MemberCommand(Long userId, SupportGroupMemberRole memberRole) { }

    public record GroupSummary(SupportGroup group, long memberCount, long activeTicketCount,
                               long overdueTicketCount, long unassignedTicketCount,
                               long criticalTicketCount) { }

    public record MemberView(SupportGroupMember membership, StaffSummary staff, long activeAssignments) { }

    public record GroupDetail(GroupSummary summary, List<MemberView> members, List<Ticket> tickets) { }
}
