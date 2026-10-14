package com.opsflow.automation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.auth.AuthService;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
import com.opsflow.sla.SlaPolicyService;
import com.opsflow.supportgroup.SupportGroup;
import com.opsflow.supportgroup.SupportGroupMapper;
import com.opsflow.supportgroup.SupportGroupMember;
import com.opsflow.supportgroup.SupportGroupMemberMapper;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AutomationRuleService {
    private final AutomationRuleMapper ruleMapper;
    private final TicketMapper ticketMapper;
    private final AuthService authService;
    private final AuditLogService auditLogService;
    private final SlaPolicyService slaPolicyService;
    private final SupportGroupMapper supportGroupMapper;
    private final SupportGroupMemberMapper supportGroupMemberMapper;
    private final NotificationService notificationService;

    public List<AutomationRuleView> list(Long tenantId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<AutomationRule>()
                        .eq(AutomationRule::getTenantId, tenantId)
                        .orderByAsc(AutomationRule::getSortOrder)
                        .orderByAsc(AutomationRule::getId))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public AutomationRuleView create(RuleCommand command, Long tenantId, Long actorId) {
        AutomationRule rule = new AutomationRule();
        applyCommand(rule, command, tenantId);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedBy(actorId);
        rule.setUpdatedBy(actorId);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        ruleMapper.insert(rule);
        AutomationRuleView view = toView(rule);
        auditLogService.record(tenantId, actorId, "AUTOMATION_RULE", rule.getId(),
                "AUTOMATION_RULE_CREATED", null, view, "创建工单自动化规则");
        return view;
    }

    @Transactional
    public AutomationRuleView update(Long id, RuleCommand command, Long tenantId, Long actorId) {
        AutomationRule rule = find(id, tenantId);
        AutomationRuleView before = toView(rule);
        applyCommand(rule, command, tenantId);
        rule.setUpdatedBy(actorId);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);
        AutomationRuleView view = toView(rule);
        auditLogService.record(tenantId, actorId, "AUTOMATION_RULE", id,
                "AUTOMATION_RULE_UPDATED", before, view, "更新工单自动化规则");
        return view;
    }

    @Transactional
    public AutomationRuleView toggle(Long id, boolean enabled, Long tenantId, Long actorId) {
        AutomationRule rule = find(id, tenantId);
        AutomationRuleView before = toView(rule);
        rule.setEnabled(enabled);
        rule.setUpdatedBy(actorId);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);
        AutomationRuleView view = toView(rule);
        auditLogService.record(tenantId, actorId, "AUTOMATION_RULE", id,
                "AUTOMATION_RULE_TOGGLED", before, view, enabled ? "启用工单自动化规则" : "停用工单自动化规则");
        return view;
    }

    @Transactional
    public void delete(Long id, Long tenantId, Long actorId) {
        AutomationRule rule = find(id, tenantId);
        ruleMapper.deleteById(id);
        auditLogService.record(tenantId, actorId, "AUTOMATION_RULE", id,
                "AUTOMATION_RULE_DELETED", toView(rule), null, "删除工单自动化规则");
    }

    /**
     * Apply the first matching rule after a ticket is persisted. First-match semantics keep
     * routing deterministic and make the sort order visible to administrators.
     */
    @Transactional
    public AppliedRule applyOnCreate(Ticket ticket, Long actorId) {
        List<AutomationRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<AutomationRule>()
                .eq(AutomationRule::getTenantId, ticket.getTenantId())
                .eq(AutomationRule::getEnabled, true)
                .orderByAsc(AutomationRule::getSortOrder)
                .orderByAsc(AutomationRule::getId));
        for (AutomationRule rule : rules) {
            if (!matches(rule, ticket)) continue;

            RoutingDecision routing = resolveRouting(rule, ticket);
            if (!routing.available()) continue;

            Map<String, Object> before = snapshot(ticket);
            boolean changed = false;
            if (rule.getTargetGroupId() != null) {
                if (!rule.getTargetGroupId().equals(ticket.getAssignmentGroupId())) {
                    ticket.setAssignmentGroupId(rule.getTargetGroupId());
                    changed = true;
                }
                if (!java.util.Objects.equals(routing.assigneeId(), ticket.getAssigneeId())) {
                    ticket.setAssigneeId(routing.assigneeId());
                    changed = true;
                }
            } else if (rule.getTargetAssigneeId() != null
                    && !rule.getTargetAssigneeId().equals(ticket.getAssigneeId())) {
                ticket.setAssigneeId(rule.getTargetAssigneeId());
                changed = true;
            }
            if (rule.getTargetPriority() != null && rule.getTargetPriority() != ticket.getPriority()) {
                ticket.setPriority(rule.getTargetPriority());
                ticket.setDueAt(slaPolicyService.dueAt(ticket.getTenantId(), ticket.getPriority(), ticket.getCreatedAt()));
                changed = true;
            }
            if (rule.getTargetStatus() != null && rule.getTargetStatus() != ticket.getStatus()) {
                ticket.setStatus(rule.getTargetStatus());
                changed = true;
            }
            if (!changed) return new AppliedRule(rule.getId(), rule.getName(), false);

            ticket.setUpdatedAt(LocalDateTime.now());
            if (ticketMapper.updateById(ticket) == 0) {
                throw new BusinessException("自动化规则更新工单失败，请刷新后重试");
            }
            auditLogService.record(ticket.getTenantId(), actorId, "TICKET", ticket.getId(),
                    "AUTOMATION_APPLIED", before, snapshot(ticket), "自动化规则「" + rule.getName() + "」已执行");
            notifyRouting(rule, routing, ticket, actorId);
            return new AppliedRule(rule.getId(), rule.getName(), true);
        }
        return null;
    }

    private boolean matches(AutomationRule rule, Ticket ticket) {
        if (rule.getMatchCategory() != null
                && !rule.getMatchCategory().equalsIgnoreCase(trim(ticket.getCategory()))) return false;
        if (rule.getMatchPriority() != null && rule.getMatchPriority() != ticket.getPriority()) return false;
        if (rule.getMatchKeyword() != null) {
            String text = ((ticket.getTitle() == null ? "" : ticket.getTitle()) + " "
                    + (ticket.getDescription() == null ? "" : ticket.getDescription())).toLowerCase(Locale.ROOT);
            if (!text.contains(rule.getMatchKeyword().toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private void applyCommand(AutomationRule rule, RuleCommand command, Long tenantId) {
        String name = trim(command.name());
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new BusinessException("规则名称不能为空且不能超过 80 个字符");
        }
        String category = trim(command.matchCategory());
        String keyword = trim(command.matchKeyword());
        if (category == null && command.matchPriority() == null && keyword == null) {
            throw new BusinessException("至少配置一个匹配条件");
        }
        if (command.targetAssigneeId() == null && command.targetGroupId() == null
                && command.targetPriority() == null && command.targetStatus() == null) {
            throw new BusinessException("至少配置一个执行动作");
        }
        if (command.targetStatus() != null
                && command.targetStatus() != TicketStatus.IN_PROGRESS
                && command.targetStatus() != TicketStatus.REJECTED) {
            throw new BusinessException("创建时自动变更状态只能选择处理中或已驳回");
        }
        if (command.targetAssigneeId() != null
                && !authService.isStaffMember(command.targetAssigneeId(), tenantId)) {
            throw new BusinessException("目标处理人不存在或不是当前租户的客服账号");
        }
        AutomationAssignmentStrategy strategy = command.assignmentStrategy();
        if (command.targetGroupId() != null) {
            SupportGroup targetGroup = supportGroupMapper.selectOne(new LambdaQueryWrapper<SupportGroup>()
                    .eq(SupportGroup::getId, command.targetGroupId())
                    .eq(SupportGroup::getTenantId, tenantId));
            if (targetGroup == null) throw new BusinessException("目标支持组不存在");
            if (!Boolean.TRUE.equals(targetGroup.getEnabled())) throw new BusinessException("目标支持组已停用");
            if (command.targetAssigneeId() != null) {
                throw new BusinessException("使用支持组路由时，请通过分派策略选择处理人");
            }
            strategy = strategy == null ? AutomationAssignmentStrategy.QUEUE_ONLY : strategy;
        } else if (strategy != null) {
            throw new BusinessException("只有选择目标支持组后才能配置团队分派策略");
        }
        rule.setName(name);
        rule.setTenantId(tenantId);
        rule.setEnabled(command.enabled() == null || command.enabled());
        rule.setSortOrder(command.sortOrder() == null ? 100 : Math.max(0, Math.min(command.sortOrder(), 9999)));
        rule.setMatchCategory(category);
        rule.setMatchPriority(command.matchPriority());
        rule.setMatchKeyword(keyword == null ? null : keyword.toLowerCase(Locale.ROOT));
        rule.setTargetAssigneeId(command.targetAssigneeId());
        rule.setTargetGroupId(command.targetGroupId());
        rule.setAssignmentStrategy(strategy);
        rule.setTargetPriority(command.targetPriority());
        rule.setTargetStatus(command.targetStatus());
    }

    private AutomationRule find(Long id, Long tenantId) {
        AutomationRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<AutomationRule>()
                .eq(AutomationRule::getId, id).eq(AutomationRule::getTenantId, tenantId));
        if (rule == null) throw new BusinessException("自动化规则不存在");
        return rule;
    }

    private Map<String, Object> snapshot(Ticket ticket) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", ticket.getStatus() == null ? null : ticket.getStatus().name());
        snapshot.put("priority", ticket.getPriority() == null ? null : ticket.getPriority().name());
        snapshot.put("assigneeId", ticket.getAssigneeId());
        snapshot.put("assignmentGroupId", ticket.getAssignmentGroupId());
        snapshot.put("dueAt", ticket.getDueAt() == null ? null : ticket.getDueAt().toString());
        return snapshot;
    }

    private AutomationRuleView toView(AutomationRule rule) {
        return new AutomationRuleView(rule.getId(), rule.getTenantId(), rule.getName(), rule.getEnabled(),
                rule.getSortOrder(), rule.getMatchCategory(), rule.getMatchPriority(), rule.getMatchKeyword(),
                rule.getTargetAssigneeId(), rule.getTargetGroupId(), rule.getAssignmentStrategy(),
                rule.getTargetPriority(), rule.getTargetStatus(), rule.getCreatedBy(),
                rule.getUpdatedBy(), rule.getCreatedAt(), rule.getUpdatedAt());
    }

    private RoutingDecision resolveRouting(AutomationRule rule, Ticket ticket) {
        if (rule.getTargetGroupId() == null) {
            return new RoutingDecision(true, rule.getTargetAssigneeId(), null, List.of());
        }
        SupportGroup group = supportGroupMapper.selectOne(new LambdaQueryWrapper<SupportGroup>()
                .eq(SupportGroup::getId, rule.getTargetGroupId())
                .eq(SupportGroup::getTenantId, ticket.getTenantId())
                .eq(SupportGroup::getEnabled, true));
        if (group == null) return new RoutingDecision(false, null, null, List.of());

        List<SupportGroupMember> members = supportGroupMemberMapper.selectActiveForUpdate(
                group.getId(), ticket.getTenantId());
        AutomationAssignmentStrategy strategy = rule.getAssignmentStrategy() == null
                ? AutomationAssignmentStrategy.QUEUE_ONLY : rule.getAssignmentStrategy();
        Long assigneeId = strategy == AutomationAssignmentStrategy.LEAST_LOADED
                ? leastLoadedMember(members, ticket.getTenantId()) : null;
        return new RoutingDecision(true, assigneeId, group, members);
    }

    private Long leastLoadedMember(List<SupportGroupMember> members, Long tenantId) {
        if (members.isEmpty()) return null;
        List<Long> userIds = members.stream().map(SupportGroupMember::getUserId).distinct().toList();
        List<TicketStatus> activeStatuses = List.of(
                TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION);
        List<Ticket> activeTickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .in(Ticket::getStatus, activeStatuses)
                .in(Ticket::getAssigneeId, userIds));
        Map<Long, Long> workloads = new HashMap<>();
        activeTickets.stream().filter(item -> item.getAssigneeId() != null)
                .forEach(item -> workloads.merge(item.getAssigneeId(), 1L, Long::sum));
        return members.stream()
                .min(Comparator.comparingLong((SupportGroupMember member) ->
                                workloads.getOrDefault(member.getUserId(), 0L))
                        .thenComparing(SupportGroupMember::getJoinedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SupportGroupMember::getUserId))
                .map(SupportGroupMember::getUserId)
                .orElse(null);
    }

    private void notifyRouting(AutomationRule rule, RoutingDecision routing, Ticket ticket, Long actorId) {
        if (routing.group() == null) return;
        List<Long> recipients = routing.assigneeId() == null
                ? routing.members().stream().map(SupportGroupMember::getUserId).distinct().toList()
                : List.of(routing.assigneeId());
        String title = routing.assigneeId() == null ? "新工单进入团队队列" : "新工单已自动分派";
        String content = "工单 " + ticket.getTicketNo() + " 已由规则「" + rule.getName()
                + "」路由至「" + routing.group().getName() + "」"
                + (routing.assigneeId() == null ? "，等待组内成员领取。" : "，请及时处理。");
        for (Long recipientId : recipients) {
            if (recipientId.equals(actorId)) continue;
            notificationService.notifyIfAbsent(ticket.getTenantId(), recipientId, "AUTOMATION_GROUP_ROUTED",
                    title, content, "AUTOMATION_GROUP_ROUTED:" + ticket.getId() + ":" + rule.getId());
        }
    }

    private String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    public record RuleCommand(String name, Boolean enabled, Integer sortOrder, String matchCategory,
                              TicketPriority matchPriority, String matchKeyword, Long targetAssigneeId,
                              Long targetGroupId, AutomationAssignmentStrategy assignmentStrategy,
                              TicketPriority targetPriority, TicketStatus targetStatus) { }

    public record AppliedRule(Long ruleId, String ruleName, boolean changed) { }

    private record RoutingDecision(boolean available, Long assigneeId, SupportGroup group,
                                   List<SupportGroupMember> members) { }
}
