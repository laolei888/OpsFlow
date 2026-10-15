# OpsFlow

[![OpsFlow CI](https://github.com/laolei888/OpsFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/laolei888/OpsFlow/actions/workflows/ci.yml)
[![CodeQL](https://github.com/laolei888/OpsFlow/actions/workflows/codeql.yml/badge.svg)](https://github.com/laolei888/OpsFlow/actions/workflows/codeql.yml)
[![Supply Chain Security](https://github.com/laolei888/OpsFlow/actions/workflows/supply-chain.yml/badge.svg)](https://github.com/laolei888/OpsFlow/actions/workflows/supply-chain.yml)

企业级工单与知识库协同平台，面向 Java 后端实习项目实践。

## 当前版本

当前后端 MVP 已包含：

- 现代 Java 后端基线：Java 17、Spring Boot 4.1.1、Spring Framework 7、Jackson 3、Tomcat 11、MyBatis-Plus 3.5.17、RabbitMQ 4.2
- 用户注册、登录、JWT 无状态认证
- BCrypt 密码存储与 tokenVersion 注销失效
- 登录接口按来源 IP 限流，账号连续失败自动锁定；Redis 不可用时自动切换本机保护状态
- 统一密码策略、生产环境必填 JWT/数据库/Redis 密钥，并默认关闭公开注册、接口文档与指标端点
- 安全响应头、精确 CORS 白名单、JSON 401/403 响应和审计敏感字段脱敏
- 用户角色模型和租户字段
- 工单创建、分页查询、详情查询
- 工单状态机流转与乐观锁版本字段
- 工单按 tenantId 隔离查询
- 知识库草稿、发布、归档状态模型；AGENT/ADMIN 可编辑、发布、归档和恢复，按租户隔离并记录审计；支持分类/状态筛选、全文阅读、阅读量统计、文章版本历史、用户帮助度反馈和知识库运营分析看板
- 知识库按关键词和分类检索
- Redis 分布式窗口限流；Redis 不可用时降级到本地内存
- 参数校验、统一异常响应
- MySQL 表结构与 Flyway 版本迁移
- OpenAPI 3 接口说明与 Swagger UI 在线调试，支持 JWT Bearer Token
- OpenAPI 契约回归守卫：校验关键业务路由、公开接口鉴权豁免、工单参数、响应定义、路径规模和 operationId 唯一性
- k6 性能基线：真实注册测试用户并携带 JWT 压测工单、运营总览、SLA 和公开状态页，CI 自动执行错误率与 p95 延迟门禁
- OpenTelemetry + Micrometer 全链路观测：业务响应返回 Trace ID，日志同时携带 Trace ID、Span ID 与 Request ID，前端错误会展示可检索的追踪编号
- Prometheus 指标与慢接口治理：开放标准 `/actuator/prometheus` 抓取端点，为 HTTP 延迟生成可聚合直方图，并按低基数路由记录超阈值请求
- Grafana 可观测性工作台：预置 Prometheus、Tempo、Loki 数据源和 OpsFlow 服务仪表盘，支持从指标 exemplar 跳转 Trace、从 Trace 关联日志
- Actuator 健康检查和指标入口
- Docker Compose 编排 MySQL、Redis、RabbitMQ 和应用
- 软件供应链安全：CodeQL 静态分析、Dependabot、依赖变更审查、Trivy 镜像扫描、SPDX SBOM、GHCR 镜像发布与来源证明
- 工单服务状态机单元测试
- 工单评论、协作时间线与操作审计日志
- 按优先级计算 SLA 截止时间与租户级逾期汇总
- 总览页 SLA 风险工单工作列表
- 工单详情按分类推荐知识文章
- 评论区支持公开回复与内部备注可见性隔离
- AGENT/ADMIN 可批量更新工单状态与优先级，并逐张记录审计日志
- AGENT/ADMIN 可从工单详情编辑标题、描述、分类和优先级，更新写入 TICKET_UPDATED 审计事件；无变化提交会自动短路
- AGENT/ADMIN 可领取/释放工单，支持按 assigneeId 查询“我的工单”
- 申请人访问工单详情、评论与审计日志时按 requesterId 做资源级权限校验
- 工单服务端关键词、状态、优先级与逾期组合检索
- Vue 3 管理台：登录、总览、工单中心、知识库和工单详情
- 后端运营看板：工单总量、SLA 风险、优先级、处理覆盖、今日变化统计和可选时间范围运营报表，支持按时间范围导出 CSV
- 统一运营待办中心：跨服务审批、变更审批、监控告警、重大事件、SLO 违规和状态公告草稿聚合可执行队列；按严重度与等待时长排序，支持类型/风险筛选、角色裁剪和一键跳转到对应业务中心
- 支持组与团队队列：管理员维护团队编码、负责人、组长与成员，客服将活跃工单路由至能力组；系统统计组级活跃量、待领取量、高优先级和 SLA 逾期，并展示成员当前负载。跨组路由时若原负责人不属于目标组会自动退回组内待领取，路由通知、审计和 Webhook 事件全链路留痕
- 角色感知全局搜索：通过侧边栏或 Ctrl+K 跨工单、知识库、CMDB、变更、问题、告警和重大事件检索，按编号精确命中、标题相关度与更新时间统一排序；REQUESTER 仅能搜索本人工单和已发布知识，AGENT/ADMIN 才能检索内部运维数据
- 报表订阅：AGENT/ADMIN 可配置每日或每周运营摘要，支持 7/14/30 天统计周期、启停、立即生成、通知去重、租户隔离和审计记录
- 企业服务目录与审批：管理员配置标准服务、默认优先级和审批策略；提单人提交幂等服务申请，审批通过后自动创建工单并启动 SLA/自动化分派，审批结果进入通知与审计链路
- CMDB 资产与配置项：维护服务器、应用、数据库、网络和业务服务台账，支持上下游依赖关系、核心资产影响分析，以及从工单详情关联受影响配置项
- 企业级变更管理：支持标准/普通/紧急变更与风险分级，标准低风险自动预批准，高风险进入管理员审批；覆盖计划窗口、实施/验证/回滚方案、实施任务 checklist、审批意见、状态流转和 CMDB 受影响资产关联
- 发布治理中心（Release Governance）：以发布列车聚合多项已批准变更，内置预检、部署、验证三阶段必做门禁；Go/No-Go 决策会校验发布窗口、变更审批状态和预检证据，发布完成前需保留部署与验收结果，支持可审计的回滚与取消闭环
- 发布日历与冻结策略：管理员可按环境或全局配置节假日/大促等冻结窗口；Go 决策自动识别冻结期和同环境、同受影响 CMDB 配置项的重叠发布，普通发布会被阻断，纯紧急变更例外会独立进入审计轨迹
- 运行手册与处置编排中心（Runbook Orchestration）：管理员维护可版本化的告警、重大事件、工单与通用处置手册；客服从受影响对象发起人工处置时固化步骤快照，逐项记录验证证据，所有必做步骤通过后才能完成，失败/取消原因与全部过程进入审计链路。平台不直接连接服务器或执行命令
- 告警驱动运行手册推荐：管理员可按告警源、最低严重度和标题/描述关键词配置推荐规则；告警详情会展示可解释的匹配原因，客服一键确认后才创建人工处置执行记录，避免自动化误操作
- 问题管理：登记重复故障和重大事件根因，支持 RCA、已知错误、临时规避方案、分析任务，以及与症状工单和修复变更的双向闭环关联
- 重大事件响应中心（Incident Command）：面向 SEV1-SEV4 的宣布、调查、缓解、观察、解决与关闭闭环；覆盖事件指挥人、影响范围、动态时间线、协同任务、关联工单、CMDB 受影响配置项与强制事后复盘，关键动作自动进入审计与 Webhook 事件流
- 重大事件复盘中心（Post-Incident Review）：每个重大事件可建立一份结构化复盘，覆盖执行摘要、客户影响、根因、促成因素、发现缺口、解决过程、经验教训和预防计划；草稿通过完整度门禁后提交管理员评审，支持驳回、批准和发布状态机。整改行动项独立维护负责人、优先级、截止时间、进度和完成证据，逾期项自动标识；已批准/已发布复盘可作为重大事件关闭门禁，所有动作进入审计与 Webhook
- 值班与升级中心（On-Call & Escalation）：管理员维护业务线值班表、主/备轮班与按 SEV 阈值匹配的多级升级策略；未分派 SLA 告警优先通知当前主值班人，重大事件按策略逐级派发，投递记录、审计与 Webhook 全程可追溯
- 监控告警中心（Alert Center）：为 Prometheus、Grafana 等监控源签发一次性接入密钥，按来源与指纹聚合重复信号；支持确认、抑制、恢复、维护静默窗口、CMDB 资产关联，以及按严重度规则通知当前值班人、自动创建工单或宣布重大事件
- 服务状态中心（Status Page）：客服起草面向用户的服务公告，管理员审核发布；支持公开服务组件、影响等级、调查/定位/观察/恢复时间线、CMDB 与内部事故追溯关联、审计与 Webhook。内部告警和事故默认不公开，公开页面只返回经过审核的安全字段
- 可靠性工程中心（SLO & Error Budget）：管理员为服务状态组件设定 1-90 天可用性目标与预算阈值；客服可记录 SLI 样本并关联监控告警证据，系统聚合窗口可用性、错误预算消耗并生成可确认/关闭的违规事件，违规可关联处置工单或重大事件，全链路进入审计与 Webhook
- Webhook 集成中心：ADMIN 配置多租户 Webhook 订阅，按审计事件筛选并使用 HMAC-SHA256 签名；采用 Transactional Outbox 保证业务数据、审计日志和集成事件同事务提交，再由后台任务幂等展开投递记录，支持多实例并发抢占、失败指数退避、双层死信、人工重试、积压监控和 30 天已发布事件清理；创建和实际投递前均执行 SSRF 地址校验
- RabbitMQ 可靠异步消息中心：运营报表通知与订阅状态在同一数据库事务写入通用消息 Outbox，后台发布器通过 Publisher Confirm 确认后才标记已发布；消费者使用通知去重键保证至少一次投递下的业务幂等，消费异常最多重试 5 次后进入持久化死信队列。Outbox 支持多实例租约抢占、8 次指数退避、人工重试、30 天清理和 Prometheus 积压/消费指标
- AGENT/ADMIN 客服目录与工单指派，指派动作进入审计时间线
- 申请人的看板、SLA 汇总与工单列表按 requesterId 做资源级隔离
- 可选环境变量初始化 AGENT/ADMIN 演示账号，不写死默认高权限密码
- SLA 定时扫描与站内通知：逾期、24 小时内到期、未读数、单条/全部已读
- 通知按 tenantId 与 recipientId 隔离，并通过 dedupeKey 避免重复提醒
- 工单附件上传、列表、下载和删除，支持租户隔离、文件名清洗、类型与大小限制，并通过文件签名或严格文本解码识别伪装文件
- 图片附件详情页预览，继续复用带 JWT 的安全下载链路
- ADMIN 团队管理：创建账号、启停用、角色调整，并保护最后一名管理员
- ADMIN 全局审计日志检索：按动作、操作者和时间范围筛选
- 用户管理变更自动写入 USER_CREATED、USER_STATUS_CHANGED、USER_ROLE_CHANGED 审计记录
- 工单 CSV 导出：在工单队列中勾选具体工单，再在导出弹窗选择字段，只导出勾选项；支持按优先级、SLA、更新时间和创建时间排序，排序可保存到个人/团队视图；服务端按 tenantId、requesterId 和 assigneeId 再次校验，支持 Excel 中文打开
- AGENT/ADMIN 批量导入工单：支持 CSV 模板、中文/英文表头、引号与换行、逐行校验、失败行回传和重复导入幂等
- 工单中心支持将状态、优先级、关键词、逾期和“我的工单”筛选保存为个人/团队视图，按租户隔离并记录审计
- ADMIN 审计日志 CSV 导出，导出动作本身也会进入审计链路
- ADMIN 审计日志导出支持选择多个事件类型（actions），并沿用操作者与时间范围筛选
- 租户级 SLA 策略配置：按优先级设置 1-720 小时，新工单动态计算截止时间
- 总览近 14 天创建/解决趋势，按 requesterId 自动隔离数据范围
- 登录用户可修改密码，修改后 tokenVersion 递增并强制旧令牌失效
- ADMIN 可配置租户级工单自动化规则：按分类、优先级、关键词匹配，自动分派、路由至支持组、调优先级或推进状态
- 自动化规则采用租户隔离、执行顺序和首条命中语义；支持“进入组队列待领取”和“最少负载成员自动接单”两种团队策略。负载分配在事务内锁定组成员并统计其全部活跃工单，空组自动回退为待领取，执行结果写入通知、审计和 Webhook 链路
- 客户满意度闭环（CSAT）：工单进入已解决状态后自动向申请人发送去重评价邀请；只有原申请人可对已解决/已关闭工单提交一次 1-5 星评分、标准化体验标签和文字反馈。评价提交时固化处理人及支持组快照，客服可按 7/30/90/365 天查看评分分布、正向率、低分预警、支持组与处理人排行，提交动作进入审计及 Webhook 链路
- 工单创建支持 Idempotency-Key：网络重试或重复点击不会产生重复工单，并校验请求指纹
- 幂等键按 24 小时保留并由定时任务清理，避免长期占用业务表
- 全链路响应返回 X-Request-Id，未捕获异常日志带请求标识，便于线上排查
- Docker 使用独立 opsflow_uploads 卷持久化附件文件

## 启动

```bash
# 可选：复制配置模板后按需修改本地密钥和安全参数
cp .env.example .env

# 轻量业务栈：应用、MySQL、Redis、RabbitMQ
docker compose up --build

# 完整可观测栈：额外启用 Prometheus、Tempo、Loki、Alloy、Grafana
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

RabbitMQ 管理台位于 http://localhost:15672，账号来自 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD`；
默认本地开发值为 `opsflow / opsflow`，共享环境必须通过 `.env` 修改密码。

完整可观测栈启动后，Grafana 位于 http://localhost:3000，默认本地账号为
`admin / admin`；Prometheus 位于 http://localhost:9090，Tempo API 位于
http://localhost:3200。部署到共享环境前必须通过 `GRAFANA_ADMIN_PASSWORD` 修改密码。

## 测试与持续集成

```bash
# 186 个业务单元测试，不依赖 Docker
mvn test

# 完整验收：单元测试 + MySQL 8.4 集成测试
mvn verify

# 前端生产构建
cd frontend
npm ci
npm run build

# 启动完整栈后执行接口契约守卫
node quality/openapi-contract.mjs

# 验证未授权响应、安全响应头、CORS 和登录限流
node quality/security-smoke.mjs

# 验证 Trace ID、Prometheus、Tempo、Loki 和 Grafana 的完整数据链路
node quality/observability-smoke.mjs

# 执行 k6 渐进式性能基线（最高 10 VU）
docker compose --profile quality run --rm --no-deps k6
```

`mvn verify` 默认由 Testcontainers 启动一次性 MySQL，验证从 V1 到 V38 的全部 Flyway
迁移、CSAT 唯一约束和数据库聚合查询。Windows 本地环境若无法访问 Docker 命名管道，
也可以通过 `OPSFLOW_IT_JDBC_URL`、`OPSFLOW_IT_DB_USERNAME` 和
`OPSFLOW_IT_DB_PASSWORD` 指向一个独立测试数据库；不要连接生产库。

仓库内的 GitHub Actions 会并行执行后端 `mvn verify`、前端生产构建和 Docker 镜像构建，
并缓存 Maven、npm 与 BuildKit 依赖。镜像构建后还会启动完整 Compose 栈，自动检查应用健康、
管理台首页、Swagger UI 和 OpenAPI JSON，随后执行 OpenAPI 契约、安全基线、可观测性全链路冒烟及 k6 性能阈值测试，
避免只验证编译、不验证运行。运行镜像使用非 root 用户，
Compose 同时配置了 MySQL/Redis 健康检查与容器日志轮转。

性能基线报告：[docs/performance-baseline.md](docs/performance-baseline.md)。当前报告用于持续回归，
不把开发机上的短时测试结果解释为生产容量结论。

可观测性设计、常用 PromQL/TraceQL/LogQL 查询和故障定位流程见
[docs/observability.md](docs/observability.md)。

安全设计、生产环境变量和上线前检查项见 [docs/security.md](docs/security.md)。

单机 HTTPS 演示环境、GHCR 镜像与供应链验证流程见 [docs/deployment.md](docs/deployment.md)。

管理台：打开 http://localhost:8081/，使用自行注册或通过环境变量初始化的账号登录。登录页不会预填账号和密码；ADMIN 登录后还会看到“团队管理”工作台。

接口文档：打开 http://localhost:8081/docs，在右上角 `Authorize` 中填入登录接口返回的 JWT，
即可按业务模块调试受保护接口；原始 OpenAPI JSON 位于 http://localhost:8081/api-docs。
生产环境可设置 `OPSFLOW_API_DOCS_ENABLED=false` 同时关闭文档与调试页面。

运营待办中心：AGENT/ADMIN 登录后从侧边栏进入“运营待办”。AGENT 只会看到有权处置的告警、重大事件和可靠性违规；ADMIN 额外看到待审批服务申请、待审批变更和待发布状态公告。聚合接口为 `GET /api/v1/operations-workbench`，支持 `types`、`priorities` 和 `limit` 查询参数，汇总与明细始终按当前租户隔离。

全局搜索：登录后点击侧边栏“全局搜索”或按 `Ctrl+K`，输入至少 2 个字符即可搜索。接口为 `GET /api/v1/search?q=VPN&limit=30`；后端会在各领域内限制候选数量，再按编号、标题、正文和更新时间计算相关度，不向普通提单人泄露内部资产、告警、事故或未发布知识。

公开服务状态页：打开 http://localhost:8081/status，无需登录。它默认不会显示内部告警、内部事故或 CMDB 资产信息；先由 AGENT/ADMIN 在“服务状态”创建公告草稿，再由 ADMIN 审核发布。

可靠性中心：ADMIN 在“可靠性 SLO”中设定目标和预算阈值；AGENT/ADMIN 录入总请求数、成功请求数以及可选的监控告警 ID 作为 SLI 证据。关联告警只用于追溯，不会自动推导请求量、生成公开公告或修改服务状态页。

发布治理：AGENT/ADMIN 在“发布治理”新建发布单并关联已批准变更，先逐项通过预检门禁，再记录 Go 决策并开始实施。部署和验证阶段的必做项全部通过后才能完成发布；任何范围或预检调整都会使此前的 Go 决策失效，从而要求重新评估。

发布日历：ADMIN 可在“发布治理”的“冻结策略”中为全部环境或指定环境设置冻结时间。发布单会即时提示重叠冻结期和共享受影响配置项的其他发布；只有全部关联为紧急变更的发布可以绕过冻结，并自动记录例外审计，发布冲突则仍需调整窗口或范围。

运行手册：ADMIN 在“运行手册”配置适用对象、版本和人工处置步骤；AGENT/ADMIN 可从通用、监控告警、重大事件或工单上下文发起处置。开始时会冻结当前步骤版本，模板后续修改不会改写历史证据；每项必做步骤必须标记为“已通过”才能完成，失败或取消需要填写原因。该模块仅编排人工动作，绝不在平台内直接执行真实环境命令。

事件复盘：AGENT/ADMIN 在“事件复盘”中选择重大事件创建草稿，填写结构化 RCA 并为每项整改指定负责人和截止时间；只有事件已解决、关键字段完整且整改责任明确时才能提交。ADMIN 可从“运营待办”直接进入评审，通过后复盘可以满足重大事件关闭门禁；整改项在复盘批准或发布后仍可继续推进，标记完成时必须填写验证证据。复盘编号、标题、根因与预防计划也会进入全局搜索。

支持组队列：AGENT/ADMIN 从侧边栏进入“支持组队列”。ADMIN 可创建、编辑、启停支持组并维护成员角色；AGENT/ADMIN 可从最多 100 张近期活跃工单中选择具体工单路由到当前组，也可将工单移出队列。停用组不能接收新工单，但历史队列会保留，便于团队安全交接。

    GET    /api/v1/support-groups                              # AGENT/ADMIN
    GET    /api/v1/support-groups/{id}                         # AGENT/ADMIN
    POST   /api/v1/support-groups                              # ADMIN
    PUT    /api/v1/support-groups/{id}                         # ADMIN
    POST   /api/v1/support-groups/{id}/toggle                  # ADMIN
    POST   /api/v1/support-groups/{id}/members                 # ADMIN
    DELETE /api/v1/support-groups/{id}/members/{userId}        # ADMIN
    POST   /api/v1/support-groups/{id}/tickets/{ticketId}      # AGENT/ADMIN
    DELETE /api/v1/support-groups/{id}/tickets/{ticketId}      # AGENT/ADMIN

服务评价：客服将工单流转为 `RESOLVED` 后，系统自动向申请人发送评价邀请。申请人从侧边栏“服务评价”选择星级、体验标签和可选意见；AGENT/ADMIN 在同一页面查看按周期聚合的 CSAT 看板。每张工单仅允许原申请人评价一次，处理人和支持组在提交时固化为历史快照。

    GET  /api/v1/satisfaction/my-surveys                      # 当前用户可评价及已评价工单
    POST /api/v1/satisfaction/tickets/{ticketId}               # 原申请人提交一次评价
    GET  /api/v1/satisfaction/analytics?days=30&limit=50       # AGENT/ADMIN

告警推荐：对于适用对象为“通用”或“监控告警”的运行手册，ADMIN 可在手册详情添加推荐规则，至少指定告警源、最低严重度或关键词之一。告警详情将显示命中手册和匹配原因；客服点击“发起处置”后才会生成运行手册执行记录，不会自动执行。

如需演示客服工作台，可在启动 Compose 前配置一次性初始化账号；只有同时配置用户名和密码时才会创建，已有同名账号不会覆盖：

    $env:OPSFLOW_BOOTSTRAP_USERNAME = "opsflow_agent"
    $env:OPSFLOW_BOOTSTRAP_PASSWORD = "请使用至少 10 位的本地演示密码"
    $env:OPSFLOW_BOOTSTRAP_DISPLAY_NAME = "客服演示账号"
    $env:OPSFLOW_BOOTSTRAP_ROLE = "ADMIN"
    docker compose up -d --build

初始化账号只允许 AGENT 或 ADMIN 角色，密码不会写入日志。ADMIN 登录后可以管理当前租户成员、查看全局审计链路，并使用客服工作台的全部能力；AGENT 可以查看处理人目录、领取/释放/指派工单，并使用“我的工单”和批量处理。

注册用户：

```bash
curl -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"StrongDemoPass!2026","displayName":"Demo User"}'
```

登录并复制响应中的 `data.token`：

```bash
curl -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"StrongDemoPass!2026"}'
```

创建工单：

```bash
curl -X POST http://localhost:8081/api/v1/tickets \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Idempotency-Key: 7f7a4f2d-创建工单-001" \
  -H "Content-Type: application/json" \
  -d '{"title":"VPN 无法连接","description":"办公室网络无法访问 VPN","category":"网络","priority":"HIGH"}'
```

查询工单：

```bash
curl -H "Authorization: Bearer <TOKEN>" http://localhost:8081/api/v1/tickets
```

创建知识库草稿并发布：

```bash
curl -X POST http://localhost:8081/api/v1/knowledge/articles \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"title":"VPN 排障指南","slug":"vpn-troubleshooting","category":"网络","content":"检查账号状态、网络和客户端配置。"}'

curl -X POST -H "Authorization: Bearer <TOKEN>" \
  http://localhost:8081/api/v1/knowledge/articles/1/publish
```

附件接口：

    curl -X POST http://localhost:8081/api/v1/tickets/1/attachments \
      -H "Authorization: Bearer <TOKEN>" \
      -F "file=@./排障截图.png"

    curl -H "Authorization: Bearer <TOKEN>" \
      http://localhost:8081/api/v1/tickets/1/attachments

管理员团队与审计接口：

    curl -H "Authorization: Bearer <ADMIN_TOKEN>" \
      http://localhost:8081/api/v1/admin/users

    curl -H "Authorization: Bearer <ADMIN_TOKEN>" \
      "http://localhost:8081/api/v1/audit-logs?action=STATUS_CHANGED&limit=50"

工单精确导出（按 ticketIds）：

页面操作：在工单中心每条工单前的白色复选框中勾选需要导出的工单，点击“导出 CSV”后仅配置字段，不再在弹窗内重复选择工单。
队列排序：排序在服务端分页前执行，支持 PRIORITY_DESC、SLA_ASC、UPDATED_DESC、CREATED_DESC 和 CREATED_ASC；保存视图会同时记住排序方式，视图所有者可直接编辑名称、可见范围、筛选条件和排序。

    curl -H "Authorization: Bearer <TOKEN>" \
      "http://localhost:8081/api/v1/exports/tickets.csv?ticketIds=12%2C15&fields=ticketNo%2Ctitle%2Cstatus" -o opsflow-tickets.csv

审计日志多事件导出（仅 ADMIN）：

    curl -H "Authorization: Bearer <ADMIN_TOKEN>" \
      "http://localhost:8081/api/v1/exports/audit-logs.csv?actions=CREATED%2CSTATUS_CHANGED&limit=200" -o opsflow-audit-logs.csv

自动化规则接口（仅 ADMIN）：

    curl -H "Authorization: Bearer <ADMIN_TOKEN>" \
      http://localhost:8081/api/v1/admin/automation-rules

    curl -X POST http://localhost:8081/api/v1/admin/automation-rules \
      -H "Authorization: Bearer <ADMIN_TOKEN>" \
      -H "Content-Type: application/json" \
      -d '{"name":"网络问题自动分派","sortOrder":10,"matchCategory":"网络","targetAssigneeId":2,"targetPriority":"HIGH"}'

批量导入工单（仅 AGENT/ADMIN）：

    curl -H "Authorization: Bearer <TOKEN>" \
      http://localhost:8081/api/v1/tickets/import/template.csv -o opsflow-ticket-import-template.csv

    curl -X POST http://localhost:8081/api/v1/tickets/import \
      -H "Authorization: Bearer <TOKEN>" \
      -F "file=@./opsflow-ticket-import-template.csv"
保存当前工单筛选视图：

    curl -H "Authorization: Bearer <TOKEN>" http://localhost:8081/api/v1/ticket-views

视图所有者更新已有视图：

    curl -X PUT http://localhost:8081/api/v1/ticket-views/1 `
      -H "Authorization: Bearer <TOKEN>" `
      -H "Content-Type: application/json" `
      -d '{"name":"我的近期工单","visibility":"PRIVATE","status":"IN_PROGRESS","sort":"UPDATED_DESC","mine":true}'

    curl -X POST http://localhost:8081/api/v1/ticket-views `
      -H "Authorization: Bearer <TOKEN>" `
      -H "Content-Type: application/json" `
      -d '{"name":"我的逾期工单","visibility":"PRIVATE","overdue":true,"mine":true}'

报表订阅接口（仅 AGENT/ADMIN）：

    GET    /api/v1/dashboard/report-subscriptions
    POST   /api/v1/dashboard/report-subscriptions
    PUT    /api/v1/dashboard/report-subscriptions/{id}
    POST   /api/v1/dashboard/report-subscriptions/{id}/run
    DELETE /api/v1/dashboard/report-subscriptions/{id}

订阅生成的运营摘要会写入当前用户的通知中心，重复生成同一统计周期不会重复创建通知。

服务目录与审批接口：

    GET    /api/v1/service-catalog/items
    POST   /api/v1/service-catalog/items                         # ADMIN
    PUT    /api/v1/service-catalog/items/{id}                    # ADMIN
    POST   /api/v1/service-catalog/items/{id}/toggle             # ADMIN
    GET    /api/v1/service-catalog/requests
    POST   /api/v1/service-catalog/requests
    POST   /api/v1/service-catalog/requests/{id}/approve         # ADMIN
    POST   /api/v1/service-catalog/requests/{id}/reject          # ADMIN

待审批申请不会提前创建工单；审批通过后才生成工单并开始计算 SLA。服务申请支持 Idempotency-Key，避免网络重试产生重复申请。

CMDB 资产与配置项接口：

    GET    /api/v1/cmdb/assets
    GET    /api/v1/cmdb/assets/{id}
    POST   /api/v1/cmdb/assets                                  # AGENT/ADMIN
    PUT    /api/v1/cmdb/assets/{id}                             # AGENT/ADMIN
    GET    /api/v1/cmdb/assets/{id}/relations
    POST   /api/v1/cmdb/assets/{id}/relations                   # AGENT/ADMIN
    DELETE /api/v1/cmdb/relations/{relationId}                  # AGENT/ADMIN
    GET    /api/v1/cmdb/assets/{id}/impact                      # 影响分析
    GET    /api/v1/cmdb/tickets/{ticketId}/assets
    POST   /api/v1/cmdb/tickets/{ticketId}/assets               # AGENT/ADMIN
    DELETE /api/v1/cmdb/tickets/{ticketId}/assets/{assetId}     # AGENT/ADMIN

资产、关系和工单关联均按 tenantId 隔离；资产退役后不会出现在普通用户的可关联列表中，避免把历史配置项继续挂到新工单上。
变更管理接口：

    GET    /api/v1/changes
    GET    /api/v1/changes/{id}
    POST   /api/v1/changes
    POST   /api/v1/changes/{id}/approve                         # ADMIN
    POST   /api/v1/changes/{id}/reject                          # ADMIN
    POST   /api/v1/changes/{id}/transition?target=IN_PROGRESS   # AGENT/ADMIN
    POST   /api/v1/changes/{id}/tasks                           # AGENT/ADMIN
    PUT    /api/v1/changes/tasks/{taskId}                       # AGENT/ADMIN
    POST   /api/v1/changes/{id}/assets                          # AGENT/ADMIN
    DELETE /api/v1/changes/{id}/assets/{assetId}                # AGENT/ADMIN

变更申请支持 Idempotency-Key；标准低风险变更自动进入可实施状态，其他风险等级需管理员审批。完成变更前必须完成全部实施任务，且变更与配置项均按 tenantId 隔离。

发布治理接口（仅 AGENT/ADMIN）：

    GET    /api/v1/releases
    GET    /api/v1/releases/{id}
    POST   /api/v1/releases
    PUT    /api/v1/releases/{id}
    POST   /api/v1/releases/{id}/changes
    DELETE /api/v1/releases/{id}/changes/{changeId}
    POST   /api/v1/releases/{id}/checklist
    PUT    /api/v1/releases/checklist/{itemId}
    POST   /api/v1/releases/{id}/go-no-go
    POST   /api/v1/releases/{id}/start
    POST   /api/v1/releases/{id}/verify
    POST   /api/v1/releases/{id}/rollback
    POST   /api/v1/releases/{id}/cancel
    GET    /api/v1/releases/calendar
    GET    /api/v1/releases/freezes
    POST   /api/v1/releases/freezes
    PUT    /api/v1/releases/freezes/{id}
    DELETE /api/v1/releases/freezes/{id}

发布单按 tenantId 隔离，只能纳入处于 APPROVED 状态的变更。Go 决策要求完整发布窗口、至少一项获批变更及全部必做预检通过；结束发布前则要求部署与验证阶段的全部必做门禁均为 PASSED。

运行手册接口：

    GET    /api/v1/runbooks                                      # AGENT/ADMIN
    GET    /api/v1/runbooks/{id}                                 # AGENT/ADMIN
    POST   /api/v1/runbooks                                      # ADMIN
    PUT    /api/v1/runbooks/{id}                                 # ADMIN
    POST   /api/v1/runbooks/{id}/steps                           # ADMIN
    PUT    /api/v1/runbooks/steps/{stepId}                       # ADMIN
    POST   /api/v1/runbooks/executions                           # AGENT/ADMIN
    GET    /api/v1/runbooks/executions/{id}                      # AGENT/ADMIN
    PUT    /api/v1/runbooks/executions/steps/{stepId}            # AGENT/ADMIN
    POST   /api/v1/runbooks/executions/{id}/complete             # AGENT/ADMIN
    POST   /api/v1/runbooks/executions/{id}/fail                 # AGENT/ADMIN
    POST   /api/v1/runbooks/executions/{id}/cancel               # AGENT/ADMIN
    GET    /api/v1/runbooks/recommendations/alerts/{alertId}     # AGENT/ADMIN
    POST   /api/v1/runbooks/{id}/alert-matchers                  # ADMIN
    PUT    /api/v1/runbooks/alert-matchers/{matcherId}           # ADMIN

运行手册、模板步骤与执行记录均按 tenantId 隔离。非通用手册只能绑定匹配类型的告警、重大事件或工单；执行时复制模板步骤作为证据快照，必做步骤不可跳过，完成前必须全部 PASSED。
告警推荐规则同样按租户隔离，且仅允许通用/告警类运行手册使用；匹配结果仅提供建议与可解释原因，最终执行始终需要客服或管理员确认。
问题管理接口：

    GET    /api/v1/problems
    GET    /api/v1/problems/{id}
    POST   /api/v1/problems
    PUT    /api/v1/problems/{id}                             # AGENT/ADMIN，维护 RCA
    POST   /api/v1/problems/{id}/transition?target=KNOWN_ERROR # AGENT/ADMIN
    POST   /api/v1/problems/{id}/tasks                       # AGENT/ADMIN
    PUT    /api/v1/problems/tasks/{taskId}                   # AGENT/ADMIN
    POST   /api/v1/problems/{id}/tickets                     # AGENT/ADMIN
    DELETE /api/v1/problems/{id}/tickets/{ticketId}          # AGENT/ADMIN
    POST   /api/v1/problems/{id}/changes                     # AGENT/ADMIN
    DELETE /api/v1/problems/{id}/changes/{changeId}          # AGENT/ADMIN

问题单与工单、变更均按 tenantId 隔离；问题关闭前必须完成分析任务，修复动作通过 Change Management 承接。

重大事件响应接口（仅 AGENT/ADMIN）：

    GET    /api/v1/incidents?status=&severity=&keyword=&limit=100
    GET    /api/v1/incidents/{id}
    POST   /api/v1/incidents                              # 支持 Idempotency-Key
    PUT    /api/v1/incidents/{id}                         # 更新指挥资料 / 复盘
    POST   /api/v1/incidents/{id}/transition?target=...   # DECLARED → INVESTIGATING → MITIGATING → MONITORING → RESOLVED → CLOSED
    POST   /api/v1/incidents/{id}/updates
    POST   /api/v1/incidents/{id}/tasks
    PUT    /api/v1/incidents/tasks/{taskId}
    POST   /api/v1/incidents/{id}/tickets
    DELETE /api/v1/incidents/{id}/tickets/{ticketId}
    POST   /api/v1/incidents/{id}/assets
    DELETE /api/v1/incidents/{id}/assets/{assetId}

关闭重大事件前必须填写事后复盘，并完成全部协同任务；SEV1 / SEV2 宣布时会向当前租户的客服成员发送去重通知。

值班与升级接口：

    GET    /api/v1/oncall/schedules                             # AGENT/ADMIN
    POST   /api/v1/oncall/schedules                             # ADMIN
    PUT    /api/v1/oncall/schedules/{id}                        # ADMIN
    GET    /api/v1/oncall/shifts?from=&to=&scheduleId=          # AGENT/ADMIN
    POST   /api/v1/oncall/schedules/{scheduleId}/shifts         # ADMIN
    PUT    /api/v1/oncall/shifts/{id}                           # ADMIN
    DELETE /api/v1/oncall/shifts/{id}                           # ADMIN
    GET    /api/v1/oncall/policies                              # AGENT/ADMIN
    POST   /api/v1/oncall/policies                              # ADMIN
    PUT    /api/v1/oncall/policies/{id}                         # ADMIN
    POST   /api/v1/oncall/policies/{policyId}/steps             # ADMIN
    PUT    /api/v1/oncall/steps/{id}                            # ADMIN
    DELETE /api/v1/oncall/steps/{id}                            # ADMIN
    GET    /api/v1/oncall/escalation-events?limit=80            # AGENT/ADMIN

主值班轮班不能在同一值班表内重叠；重大事件按 SEV 阈值命中启用策略后从首级开始派发，后续按延迟配置逐级升级。未分派的 SLA 告警优先路由给当前主值班人，没有有效轮班时才回退到全部客服成员。

监控告警中心接口：

    GET    /api/v1/alert-center/alerts                           # AGENT/ADMIN
    GET    /api/v1/alert-center/alerts/{id}                      # AGENT/ADMIN
    GET    /api/v1/alert-center/summary                          # AGENT/ADMIN
    POST   /api/v1/alert-center/alerts/{id}/acknowledge          # AGENT/ADMIN
    POST   /api/v1/alert-center/alerts/{id}/suppress             # AGENT/ADMIN
    POST   /api/v1/alert-center/alerts/{id}/resolve              # AGENT/ADMIN
    POST   /api/v1/alert-center/alerts/{id}/reopen               # AGENT/ADMIN
    GET    /api/v1/alert-center/sources                          # AGENT/ADMIN
    POST   /api/v1/alert-center/sources                          # ADMIN，密钥仅返回一次
    PUT    /api/v1/alert-center/sources/{id}                     # ADMIN
    POST   /api/v1/alert-center/sources/{id}/rotate-key          # ADMIN
    GET    /api/v1/alert-center/rules                            # AGENT/ADMIN
    POST   /api/v1/alert-center/rules                            # ADMIN
    PUT    /api/v1/alert-center/rules/{id}                       # ADMIN
    GET    /api/v1/alert-center/silences                         # AGENT/ADMIN
    POST   /api/v1/alert-center/silences                         # ADMIN
    PUT    /api/v1/alert-center/silences/{id}                    # ADMIN
    DELETE /api/v1/alert-center/silences/{id}                    # ADMIN

外部监控使用告警源返回的 `sourceKey` 和一次性 `ingestKey` 推送信号，无需用户 JWT：

    curl -X POST "http://localhost:8081/api/v1/alert-ingest/<SOURCE_KEY>" \
      -H "X-OpsFlow-Alert-Key: <INGEST_KEY>" \
      -H "Content-Type: application/json" \
      -d '{"fingerprint":"order-api:error-rate","externalAlertId":"prom-9001","title":"订单 API 错误率过高","description":"5 分钟错误率超过 10%","severity":"HIGH","state":"FIRING","labels":{"service":"order-api","env":"prod"}}'

同一告警源的相同指纹会累加出现次数，不会重复执行自动化；已解决或静默到期的告警再次触发时会进入新一代响应周期。CRITICAL/HIGH 等严重度可按规则自动建单或宣布重大事件，并继续复用值班升级链路。

Webhook 集成接口（仅 ADMIN）：

    GET    /api/v1/admin/webhooks
    POST   /api/v1/admin/webhooks
    PUT    /api/v1/admin/webhooks/{id}
    POST   /api/v1/admin/webhooks/{id}/toggle
    DELETE /api/v1/admin/webhooks/{id}
    GET    /api/v1/admin/webhooks/deliveries
    POST   /api/v1/admin/webhooks/deliveries/{id}/retry
    GET    /api/v1/admin/webhooks/outbox
    GET    /api/v1/admin/webhooks/outbox/summary
    POST   /api/v1/admin/webhooks/outbox/{id}/retry

Webhook 投递记录和 Outbox 运维视图均按租户隔离。业务服务写入审计日志时，会在同一数据库事务中写入 `webhook_outbox_events`；后台任务通过行锁抢占到期事件，按订阅规则幂等生成 `webhook_deliveries`，再执行实际 HTTP 投递。Outbox 展开失败最多尝试 8 次，HTTP 投递失败最多尝试 5 次，两层均采用指数退避并在耗尽后进入死信。事件载荷通过 `X-OpsFlow-Signature` 发送 HMAC-SHA256 签名。

RabbitMQ 异步消息运维接口（ADMIN）：

    GET    /api/v1/admin/async-messages
    GET    /api/v1/admin/async-messages/summary
    POST   /api/v1/admin/async-messages/{id}/retry

主交换机为 `opsflow.events.v1`，通知队列为 `opsflow.notifications.v1`，消费重试耗尽后消息进入 `opsflow.notifications.dead.v1`。管理员可以通过接口检查数据库 Outbox，也可以通过 RabbitMQ 管理台检查队列与死信积压。

健康检查：

```bash
curl http://localhost:8081/actuator/health
```

## 后续迭代计划

1. 增加附件病毒扫描和异步隔离区
2. 将更多高扇出通知与统计任务迁移到 RabbitMQ，并增加死信消息安全回放
3. 基于当前 Prometheus 历史数据增加持续容量趋势报告和版本对比
4. 增加邮件、企业微信等多渠道消息通知
5. 增加 SSO/OIDC 与更细粒度的权限策略

通知接口：

    curl -H "Authorization: Bearer <TOKEN>" http://localhost:8081/api/v1/notifications
    curl -H "Authorization: Bearer <TOKEN>" http://localhost:8081/api/v1/notifications/unread-count
