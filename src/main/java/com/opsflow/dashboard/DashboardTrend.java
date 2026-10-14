package com.opsflow.dashboard;

import java.time.LocalDate;

public record DashboardTrend(LocalDate date, long created, long resolved) { }
