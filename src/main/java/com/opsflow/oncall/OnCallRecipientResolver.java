package com.opsflow.oncall;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class OnCallRecipientResolver {
    private final OnCallShiftMapper shiftMapper;

    public Set<Long> activePrimaryRecipients(Long tenantId, LocalDateTime at) {
        return activeRecipients(tenantId, null, at);
    }

    public Set<Long> activeRecipients(Long tenantId, Long scheduleId, LocalDateTime at) {
        LambdaQueryWrapper<OnCallShift> query = new LambdaQueryWrapper<OnCallShift>()
                .eq(OnCallShift::getTenantId, tenantId)
                .eq(OnCallShift::getPrimaryFlag, true)
                .le(OnCallShift::getStartAt, at)
                .gt(OnCallShift::getEndAt, at)
                .orderByAsc(OnCallShift::getStartAt)
                .orderByAsc(OnCallShift::getId);
        if (scheduleId != null) query.eq(OnCallShift::getScheduleId, scheduleId);
        Set<Long> recipients = new LinkedHashSet<>();
        shiftMapper.selectList(query).forEach(shift -> recipients.add(shift.getUserId()));
        return recipients;
    }
}
