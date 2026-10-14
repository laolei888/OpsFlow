package com.opsflow.satisfaction;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SatisfactionDimensionRow {
    private Long dimensionId;
    private Long responses;
    private BigDecimal averageRating;
    private Long positiveCount;
}
