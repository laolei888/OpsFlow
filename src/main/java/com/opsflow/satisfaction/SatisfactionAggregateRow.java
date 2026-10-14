package com.opsflow.satisfaction;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SatisfactionAggregateRow {
    private Long totalResponses;
    private BigDecimal averageRating;
    private Long positiveCount;
    private Long lowRatingCount;
}
