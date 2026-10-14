package com.opsflow.webhook;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WebhookEndpointMapper extends BaseMapper<WebhookEndpoint> { }
