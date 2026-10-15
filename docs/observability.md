# OpsFlow 可观测性设计

## 目标

本阶段把 OpsFlow 从“能查看健康状态”提升到可以回答以下生产排障问题：

1. 服务是否可用，错误率和 p95 延迟是否恶化？
2. 哪一个标准化 API 路由出现了慢请求？
3. 用户提供的追踪编号对应哪一条 Trace、哪些 Span 和哪些应用日志？
4. JVM 内存、数据库连接池和业务 HTTP 指标在故障前后如何变化？
5. RabbitMQ 事务消息是否积压、重试或进入死信？

## 数据链路

```text
浏览器/API 调用
    │
    ├─ HTTP 响应：X-Request-Id + X-Trace-Id + X-Span-Id
    │
    └─ OpsFlow Spring Boot
         ├─ Micrometer metrics ── /actuator/prometheus ── Prometheus
         ├─ message outbox ────── RabbitMQ confirm/consume ── notification + DLQ
         ├─ OpenTelemetry spans ── OTLP/HTTP ───────────── Tempo
         └─ correlated logs ────── shared log volume ───── Alloy ── Loki
                                                               │
                                      Grafana ◀─────────────────┘
```

Spring MVC 的标准 HTTP Observation 负责创建服务端 Span 和
`http.server.requests` 指标；自定义 `HttpObservabilityFilter` 只补充低基数慢请求计数、
访问日志和响应追踪头，不重复实现一套追踪协议。路由标签使用
`/api/v1/tickets/{id}` 这类匹配模板，不使用原始 URL，避免工单 ID 造成指标基数爆炸。

## 启动

完整观测栈使用独立 Compose 叠加文件，因此普通开发仍可只启动应用、MySQL、Redis 和 RabbitMQ。

```powershell
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
node quality/observability-smoke.mjs
```

入口：

| 组件 | 地址 | 用途 |
| --- | --- | --- |
| OpsFlow | http://localhost:8081 | 业务平台 |
| Grafana | http://localhost:3000 | 统一观测入口，默认 `admin / admin` |
| Prometheus | http://localhost:9090 | 指标查询和告警规则 |
| Tempo | http://localhost:3200 | TraceQL 与 Trace ID 查询 |
| Loki | http://localhost:3100 | LogQL API |
| Alloy | http://localhost:12345 | 日志采集组件状态 |
| RabbitMQ | http://localhost:15672 | 队列、消费者、投递率和死信检查 |

Grafana 会自动创建 `OpsFlow Service Overview` 仪表盘和三个数据源，不需要手工导入。
默认对本地流量进行 100% 采样，生产环境应根据吞吐量调整：

```powershell
$env:OPSFLOW_TRACING_SAMPLING_PROBABILITY = "0.1"
$env:OPSFLOW_SLOW_REQUEST_THRESHOLD = "800ms"
$env:GRAFANA_ADMIN_PASSWORD = "使用独立强密码"
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

## 故障定位流程

1. 从用户错误提示或响应头复制 `X-Trace-Id`。
2. 在 Grafana Explore 中选择 Tempo，按 Trace ID 查询完整调用耗时。
3. 在 Trace 详情中打开关联日志，Loki 会按相同 Trace ID 和时间窗口定位日志。
4. 回到服务仪表盘比较该时段请求速率、5xx 比例、p95、慢请求和 JVM 堆内存。
5. 如果是持续性问题，在 Prometheus 的 Alerts 页面确认规则状态和持续时间。

常用 PromQL：

```promql
# 每秒请求数
sum(rate(http_server_requests_seconds_count{job="opsflow"}[5m]))

# 全服务 p95 延迟
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{job="opsflow"}[5m])))

# 最近五分钟慢请求数
sum by (method, uri) (increase(opsflow_http_slow_requests_total{job="opsflow"}[5m]))

# RabbitMQ 发布前的数据库 Outbox 积压
opsflow_messaging_outbox_pending{job="opsflow"}

# Broker 主队列与死信队列积压
sum(rabbitmq_queue_messages_ready{job="rabbitmq",queue="opsflow.notifications.v1"})
sum(rabbitmq_queue_messages_ready{job="rabbitmq",queue="opsflow.notifications.dead.v1"})

# 消息消费和幂等去重速率
sum(rate(opsflow_messaging_consume_succeeded_total{job="opsflow"}[5m]))
sum(rate(opsflow_messaging_consume_deduplicated_total{job="opsflow"}[5m]))
```

常用 TraceQL：

```traceql
{ resource.service.name = "opsflow" && duration > 500ms }
```

常用 LogQL：

```logql
{service_name="opsflow"} |= "traceId="
{service_name="opsflow"} |= "Slow HTTP request"
```

## 安全与容量边界

- 当前 Compose 配置用于本地演示和简历项目，不应直接暴露到公网。
- 共享或生产环境必须保护 Actuator、Prometheus、Tempo、Loki 和 Grafana 网络入口。
- Trace 和日志不得写入口令、JWT、附件正文或个人敏感信息。
- 本地 Tempo 仅保留 24 小时 Trace，Prometheus 保留 7 天指标；生产环境应使用对象存储、
  独立权限和容量规划。
- 采样率降低后仍会保留指标，但只有被采样的请求能在 Tempo 中查询到完整 Trace。

参考：

- [Spring Boot Tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [Spring Boot Prometheus endpoint](https://docs.spring.io/spring-boot/api/rest/actuator/prometheus.html)
- [Grafana Tempo data source](https://grafana.com/docs/grafana/latest/datasources/tempo/)
- [Grafana Alloy file log collection](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/)
