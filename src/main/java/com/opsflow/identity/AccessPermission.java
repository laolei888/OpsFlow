package com.opsflow.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("permissions")
public class AccessPermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String resourceName;
    private String actionName;
    private String description;
}
