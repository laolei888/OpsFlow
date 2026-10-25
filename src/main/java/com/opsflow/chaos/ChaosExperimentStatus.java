package com.opsflow.chaos;

public enum ChaosExperimentStatus {
    PLANNED,
    BASELINE,
    FAULT_INJECTED,
    RECOVERING,
    PASSED,
    FAILED,
    CANCELLED
}
