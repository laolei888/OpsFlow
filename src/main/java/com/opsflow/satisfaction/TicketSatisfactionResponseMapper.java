package com.opsflow.satisfaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketSatisfactionResponseMapper extends BaseMapper<TicketSatisfactionResponse> {
    @Select("""
            SELECT COUNT(*) AS total_responses,
                   COALESCE(ROUND(AVG(rating), 2), 0) AS average_rating,
                   COALESCE(SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END), 0) AS positive_count,
                   COALESCE(SUM(CASE WHEN rating <= 2 THEN 1 ELSE 0 END), 0) AS low_rating_count
            FROM ticket_satisfaction_responses
            WHERE tenant_id = #{tenantId} AND submitted_at >= #{fromTime}
            """)
    SatisfactionAggregateRow summarize(@Param("tenantId") Long tenantId,
                                       @Param("fromTime") LocalDateTime fromTime);

    @Select("""
            SELECT rating AS score, COUNT(*) AS responses
            FROM ticket_satisfaction_responses
            WHERE tenant_id = #{tenantId} AND submitted_at >= #{fromTime}
            GROUP BY rating
            ORDER BY rating DESC
            """)
    List<SatisfactionDistributionRow> distribution(@Param("tenantId") Long tenantId,
                                                    @Param("fromTime") LocalDateTime fromTime);

    @Select("""
            SELECT assignment_group_id AS dimension_id,
                   COUNT(*) AS responses,
                   ROUND(AVG(rating), 2) AS average_rating,
                   SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END) AS positive_count
            FROM ticket_satisfaction_responses
            WHERE tenant_id = #{tenantId} AND submitted_at >= #{fromTime}
            GROUP BY assignment_group_id
            ORDER BY average_rating DESC, responses DESC
            """)
    List<SatisfactionDimensionRow> groupMetrics(@Param("tenantId") Long tenantId,
                                                @Param("fromTime") LocalDateTime fromTime);

    @Select("""
            SELECT assignee_id AS dimension_id,
                   COUNT(*) AS responses,
                   ROUND(AVG(rating), 2) AS average_rating,
                   SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END) AS positive_count
            FROM ticket_satisfaction_responses
            WHERE tenant_id = #{tenantId} AND submitted_at >= #{fromTime}
            GROUP BY assignee_id
            ORDER BY average_rating DESC, responses DESC
            """)
    List<SatisfactionDimensionRow> assigneeMetrics(@Param("tenantId") Long tenantId,
                                                   @Param("fromTime") LocalDateTime fromTime);

    @Select("""
            SELECT * FROM ticket_satisfaction_responses
            WHERE tenant_id = #{tenantId} AND submitted_at >= #{fromTime}
            ORDER BY submitted_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<TicketSatisfactionResponse> recent(@Param("tenantId") Long tenantId,
                                            @Param("fromTime") LocalDateTime fromTime,
                                            @Param("limit") int limit);
}
