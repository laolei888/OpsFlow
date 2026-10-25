# OpsFlow 安全基线

本文记录 OpsFlow 当前已实现的安全控制、生产环境配置要求和上线前检查项。它用于说明项目的安全设计边界，不替代组织级渗透测试、依赖治理和基础设施安全评审。

## 已实现的控制

### 身份认证与密码

- JWT 密钥至少 32 字节；缺失或过短时应用拒绝启动，生产环境不提供默认值。
- 用户名在注册、登录和管理员创建账号时统一去除首尾空格并转为小写。
- 密码长度限制为 10-72 位，拒绝常见弱密码以及包含用户名的密码。
- 密码使用 BCrypt 存储；修改密码后递增 `tokenVersion`，使历史 JWT 立即失效。
- 登录按来源 IP 使用 Redis 原子计数限流；同一账号连续失败达到阈值后进入临时锁定。
- Redis 暂时不可用时切换到进程内保护，Redis 恢复后仍会先检查本机产生的账号锁定。
- 查询不存在账号时仍执行一次固定 BCrypt 校验，减少账号枚举的时序差异。
- 可选 OIDC 登录由 Spring Security 完成授权码与 ID Token 校验；Client Secret 只通过部署配置注入，不进入 OpsFlow 业务表、管理 API 或审计快照。
- OIDC 回调不会把业务 JWT 放入查询参数，而是签发随机 256 位一次性交换码；数据库只保存 SHA-256 摘要，默认 2 分钟过期并在事务锁内消费一次。
- 外部身份使用 `providerId + subject` 唯一绑定，不根据同名用户名或邮箱隐式关联本地账号，降低账号接管风险。
- OIDC 租户映射可限制邮箱域名和必须所属用户组；首次自动开户默认角色由租户管理员明确配置。
- 业务权限由角色默认集与用户级 ALLOW/DENY 覆盖合并，DENY 优先；权限变更使目标用户全部旧 JWT 失效。

### HTTP 与接口边界

- API 的 401 和 403 均返回统一 JSON 响应，不跳转 HTML 登录页。
- 响应包含 CSP、Referrer-Policy、Permissions-Policy、COOP、CORP，以及 Spring Security 默认安全响应头。
- API 响应默认 `Cache-Control: no-store`，降低浏览器或中间代理缓存敏感数据的风险。
- CORS 只接受 `OPSFLOW_CORS_ALLOWED_ORIGINS` 中明确列出的来源，不接受通配符。
- 登录、注册、告警接入和普通写请求分别限流，并返回 `X-RateLimit-*` 与 `Retry-After`。
- 生产配置默认关闭公开注册、Swagger/OpenAPI 和未认证 Prometheus 指标访问。

### 文件、审计与外连

- 附件同时校验允许的扩展名、实际文件签名和大小；文本类文件必须能被严格 UTF-8 解码。
- 附件存储支持本地卷与私有 S3/MinIO 后端；对象键按隔离区、租户和工单分层，数据库固化后端、ETag 与版本信息，切换默认后端不会改变历史附件的读取介质。
- 大文件通过短时效签名 URL 分片直传私有隔离区；服务端校验会话归属、完整分片清单和合并后对象大小，并自动中止取消或过期会话。生产环境必须由基础设施配置精确的对象存储 CORS 来源，不应开放通配来源。
- 租户附件配额在事务锁内同时计算已保存附件和活动上传预留，降低并发超额风险；对象一致性任务只巡检不自动删除。管理员清理孤儿对象或历史版本时必须等待观察期、确认对象键、填写原因，服务端还会在删除前重新验证业务引用。
- 灾备模块只管理恢复目标、制品元数据和演练证据，不持有数据库管理员权限，也不从 Web 请求执行备份或恢复命令；备份位置不得包含访问密钥，真实恢复必须在隔离环境由受控基础设施作业完成。
- 对象存储下载地址只在租户与工单资源级权限校验、附件安全状态校验通过后签发，默认 5 分钟失效且应用限制最长 1 小时；桶本身不得公开。
- CSV 导入只接受 `.csv`，拒绝 NUL 字节和非法 UTF-8，避免将二进制文件伪装成批量导入文件。
- 审计详情和对象快照在序列化前递归遮蔽 password、secret、token、API key、签名等敏感字段。
- Webhook 在保存配置和每次实际投递前都进行出站 URL 校验，拒绝用户信息、URL fragment、回环、私网、链路本地、组播、保留地址和云元数据地址。
- 外部通知模板采用创建人与审核人分离的双人复核，只有 `APPROVED` 版本可以发布；语言、模板版本、渲染结果和时间窗调度原因会固化到投递证据。
- 企业微信机器人仅允许官方 HTTPS 域名和固定发送路径；机器人 key 不通过偏好 API 回显，审计快照只保留掩码末四位，投递管理页面会移除 URL 查询参数。
- 生产配置默认只允许 HTTPS。确有内网 Webhook 需求时，应通过 `OPSFLOW_OUTBOUND_ALLOWED_HOSTS` 精确放行主机，不应直接开放全部私网。

> 应用层 DNS 校验无法完全替代网络边界控制。生产环境仍应通过防火墙、代理或容器网络策略限制应用的出站范围。

## 生产环境配置

启用生产配置时至少提供以下环境变量：

```text
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:mysql://db.example.internal:3306/opsflow?useSSL=true&requireSSL=true&verifyServerCertificate=true
DB_USERNAME=opsflow_runtime
DB_PASSWORD=<database-password>
REDIS_HOST=redis.example.internal
REDIS_PORT=6379
REDIS_PASSWORD=<redis-password>
JWT_SECRET=<at-least-32-random-bytes>
OPSFLOW_CORS_ALLOWED_ORIGINS=https://opsflow.example.com
```

如启用 OIDC，还需通过 Spring Security OAuth2 Client 配置注入 `client-id`、`client-secret`、
`issuer-uri` 和 `scope`，并在身份提供商登记 `{baseUrl}/login/oauth2/code/{registrationId}`
回调地址。完整示例见 [oidc.md](oidc.md)。

建议同时显式确认：

```text
OPSFLOW_REGISTRATION_ENABLED=false
OPSFLOW_PUBLIC_METRICS=false
OPSFLOW_API_DOCS_ENABLED=false
OPSFLOW_OUTBOUND_ALLOW_HTTP=false
OPSFLOW_OUTBOUND_ALLOW_PRIVATE_NETWORKS=false
```

不要把真实密钥写入 `.env.example`、Compose 文件、Git 历史、日志或截图。生产密钥应由部署平台的 Secret 管理能力注入，并定期轮换。

## 上线前检查清单

- [ ] 使用独立的生产数据库账号，权限仅覆盖 OpsFlow 所需 schema。
- [ ] 数据库使用 TLS，Redis 启用认证并位于受限网络内。
- [ ] JWT、数据库、Redis、Grafana 密钥均已替换且未进入版本库。
- [ ] 仅配置实际使用的 CORS 来源，不包含 `*`。
- [ ] 对象存储桶保持私有，并为分片直传单独限制 PUT 来源、方法和允许的响应头。
- [ ] 按租户确认附件配额、预警阈值和孤儿对象观察期；首次上线先运行只读巡检并人工复核结果。
- [ ] 为每个租户确认 RPO、RTO、保留期、加密与不可变副本策略，并至少完成一次隔离恢复演练。
- [ ] 公开注册、API 文档和公开指标已关闭。
- [ ] OIDC 回调地址仅使用 HTTPS，Client Secret 由 Secret 管理系统注入且未进入镜像、仓库或截图。
- [ ] OIDC 邮箱域名/用户组准入、默认角色和自动开户策略已按租户复核，并保留至少一个本地应急管理员。
- [ ] Webhook 出站域名已形成允许列表，网络层同步限制出站访问。
- [ ] 租户至少保留两名可用管理员，可以完成通知模板异人审核。
- [ ] 反向代理配置 TLS、请求体上限、可信代理来源和访问日志脱敏。
- [ ] `mvn verify`、前端构建、`quality/security-smoke.mjs` 与容器运行验收均通过。
- [ ] 依赖漏洞扫描、镜像扫描和独立渗透测试已纳入正式发布流程。

## 本地验证

启动 Compose 后执行：

```bash
node quality/security-smoke.mjs
```

测试会验证受保护接口返回 JSON 401、安全响应头生效、恶意跨域来源未被授权，以及登录接口在默认阈值后返回 429。它只应针对本地或专用测试环境运行，不应直接对生产登录入口施压。
