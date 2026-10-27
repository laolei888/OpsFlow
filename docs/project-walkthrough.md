# OpsFlow 项目讲解与面试材料

## 一句话定位

OpsFlow 是面向企业 IT 服务管理场景的工单与知识协同平台，覆盖服务申请、工单处理、知识沉淀、SLA、CMDB、告警事件、变更发布、值班升级、灾备与混沌演练等闭环。

## 技术栈

- 后端：Java 17、Spring Boot 3、Spring Security、MyBatis-Plus、Flyway、Maven
- 数据与消息：MySQL 8.4、Redis 7.4、RabbitMQ 4.2、Transactional Outbox
- 文件与安全：本地/S3 兼容对象存储、RustFS、ClamAV、HMAC-SHA256
- 可观测性：Actuator、Micrometer、OpenTelemetry、Prometheus、Grafana、Loki、Tempo
- 前端：Vue 3、Vite、Vue Router、Pinia、Fetch API、CSS Design Tokens
- 工程化：Docker Compose、GitHub Actions、Trivy、SBOM、镜像签名与来源证明

## 主要后端模块

1. 服务台：工单状态机、评论、附件、勾选导出、批量导入、保存视图、满意度。
2. 知识与服务目录：文章版本、发布/归档、反馈分析、目录申请与审批。
3. 运营治理：SLA、自动化规则、支持组、值班升级、通知模板和多渠道投递。
4. ITSM 扩展：CMDB、告警、重大事件、问题、变更、发布、复盘和公开状态页。
5. 平台工程：多租户权限、审计、异步消息、对象存储治理、可观测性、容量、灾备和混沌实验。

## 核心设计

### 1. 多租户与权限

认证后生成包含用户、租户和角色的 JWT。服务层查询统一带 `tenantId` 条件，请求人只读取自己的资源，客服和管理员再按角色、支持组成员或资源归属做二次校验。敏感后台能力只开放给 ADMIN。

### 2. 工单状态机与并发控制

状态流转由服务层集中验证，申请人与客服拥有不同的可执行动作。更新使用乐观锁避免多人处理时后写覆盖前写；创建和服务申请支持 `Idempotency-Key`，降低网络重试造成的重复数据。

### 3. SLA 与自动化

租户可以配置不同优先级的首次响应和解决时限。定时任务识别临期、逾期工单，结合自动化规则执行分派、优先级调整、通知和升级，并把动作写入审计日志。

### 4. Transactional Outbox 与 RabbitMQ

业务事务不直接依赖消息 Broker 是否可用，而是在同一数据库事务中写入 Outbox。后台发布器异步投递 RabbitMQ，消费端维护重试、死信、重放和处置记录，从而在一致性、可恢复性和吞吐之间取得平衡。

### 5. 附件与对象存储安全

附件先进入隔离区，经过文件类型、大小、压缩包和 ClamAV 扫描后才放行；大文件使用分片上传。对象存储治理会检查孤儿对象、数据库引用、配额和保留策略，避免“上传成功但业务记录失败”等遗留问题。

### 6. 可观测性与可靠性

统一请求 ID 和 trace 信息贯穿 HTTP、日志与异步消息。Prometheus 采集指标，Grafana 展示，Loki 聚合日志，Tempo 保存链路。灾备模块管理备份计划、代理心跳、签名回调和恢复演练；混沌模块要求审批、前置检查、停止条件和证据留存。

## 为什么这样设计

- 选择模块化单体：实习项目需要可完整运行和调试，领域边界清晰后仍可按模块拆分；避免为了“微服务”增加无价值部署复杂度。
- 选择 Outbox：直接发消息会遇到“数据库成功、消息失败”；分布式事务又过重，Outbox 更适合当前规模。
- Redis 允许降级：限流和部分缓存故障不应直接拖垮核心工单流程，因此提供本地保护，但跨实例精度会下降。
- 重要操作必须审计：工单流转、权限、通知、备份和混沌实验都需要回答“谁在什么时候改了什么”。

## 30 秒项目介绍

“我主要负责 OpsFlow 的 Java 后端。它是一个企业 ITSM 平台，我实现了工单、知识库、SLA、CMDB、通知和审计等模块，并重点处理了多租户权限、状态机与乐观锁、幂等创建、Transactional Outbox + RabbitMQ、对象存储安全和 OpenTelemetry 可观测性。项目使用 Flyway V57 管理数据库，当前有 319 项后端单元/组件测试和 6 项 MySQL 集成测试，可以用 Docker Compose 一键运行。”

## 常见追问

### 为什么不用微服务？

当前团队和流量规模下，模块化单体在事务一致性、开发效率和部署成本上更合适。我通过包边界、独立服务层、领域表和事件契约保留后续拆分空间。

### Outbox 如何避免重复消费？

发布器允许至少一次投递，消费者不能假设消息只来一次。系统以消息 ID、业务键和处理状态做幂等判断，失败进入重试，耗尽后进入死信；管理员可以基于完整消息 ID 和处置原因重放或作废。

### 多租户只在 Controller 判断够吗？

不够。Controller 只做入口权限，真正的数据范围限制放在 Service/Mapper 查询条件中，并对资源归属、支持组成员和角色做组合校验，防止通过修改 ID 越权访问。

### 如何证明项目可运行？

仓库提供 Docker Compose、OpenAPI 契约检查、安全/可观测性烟测、最终验收脚本和可重复演示数据；数据库迁移到 V57，并有 319 项后端单元/组件测试及 6 项真实 MySQL 集成测试。

### 项目还有哪些边界？

它是面向求职展示的单节点参考实现，并非已经承载真实企业流量。生产化还需要云上高可用部署、真实 SSO/SMTP/企业微信配置、密钥托管、容量压测和长期运维数据。

## 关键阅读路径

- 工单：`src/main/java/com/opsflow/ticket/`
- 安全：`src/main/java/com/opsflow/config/SecurityConfig.java`、`src/main/java/com/opsflow/auth/`
- 异步消息：`src/main/java/com/opsflow/messaging/`
- 可观测性：`docs/observability.md`、`docker-compose.observability.yml`
- 灾备与混沌：`src/main/java/com/opsflow/recovery/`、`src/main/java/com/opsflow/chaos/`
- 验收与演示：`quality/final-acceptance.mjs`、`quality/demo-seed.mjs`、`docs/demo-guide.md`
