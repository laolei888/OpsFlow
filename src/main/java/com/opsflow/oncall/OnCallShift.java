package com.opsflow.oncall;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@TableName("oncall_shifts")
public class OnCallShift {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long scheduleId;
    private Long userId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    @TableField("is_primary")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean primaryFlag;
    private String handoffNote;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Keeps the public API field named {@code primary}, while avoiding MySQL's
     * reserved PRIMARY keyword in MyBatis-Plus generated select aliases.
     */
    @JsonProperty("primary")
    public Boolean getPrimary() {
        return primaryFlag;
    }

    @JsonProperty("primary")
    public void setPrimary(Boolean primary) {
        this.primaryFlag = primary;
    }

    @JsonIgnore
    public Boolean getPrimaryFlag() {
        return primaryFlag;
    }

    public void setPrimaryFlag(Boolean primaryFlag) {
        this.primaryFlag = primaryFlag;
    }
}
