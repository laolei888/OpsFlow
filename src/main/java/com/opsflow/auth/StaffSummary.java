package com.opsflow.auth;

import java.util.List;

public record StaffSummary(Long id, String username, String displayName, List<String> roles) { }