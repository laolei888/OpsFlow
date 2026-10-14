package com.opsflow.sla;

import java.time.LocalDateTime;

public record SlaPolicyView(Long tenantId, int urgentHours, int highHours,
                            int mediumHours, int lowHours, Long updatedBy,
                            LocalDateTime updatedAt) { }
