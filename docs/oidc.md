# OpsFlow 企业 OIDC 与权限策略

OpsFlow 采用“外部身份提供商负责认证，OpsFlow 负责租户与业务授权”的设计。现有账号密码登录继续保留，企业 OIDC 登录成功后通过一次性交换码换取 OpsFlow 自有 JWT，因此工单、知识、审计等接口不直接依赖外部 Access Token。

## 1. 配置 OAuth2 Client

先在企业身份提供商中创建 Web/OIDC 应用，并登记回调地址：

```text
https://opsflow.example.com/login/oauth2/code/corp
```

`corp` 是 Spring Security 的 `registrationId`。在不提交真实密钥的部署配置中加入：

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          corp:
            provider: corp
            client-id: ${OPSFLOW_OIDC_CLIENT_ID}
            client-secret: ${OPSFLOW_OIDC_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid,profile,email,groups
        provider:
          corp:
            issuer-uri: ${OPSFLOW_OIDC_ISSUER_URI}
```

Google、Okta、Microsoft Entra ID、Keycloak 等标准 OIDC 服务均可使用该模式。Claim 名称和用户组内容由实际身份提供商决定。生产环境必须通过 Secret 管理能力注入 Client Secret；不要把真实值写入仓库、镜像或 `.env.example`。

反向代理需要正确传递外部协议和主机，使 `{baseUrl}` 生成 HTTPS 地址。身份提供商中的回调地址必须与最终地址完全一致。

## 2. 创建租户映射

1. 使用本地 ADMIN 账号登录。
2. 进入“团队管理 → 企业身份与细粒度权限”。
3. 新增身份提供商映射，`Registration ID` 填写 `corp`。
4. 选择默认角色，并按需配置邮箱域名白名单、必须所属用户组和 Claim 映射。
5. 确认页面显示“凭据已就绪”，再启用登录入口。

只有“Spring OAuth2 Client 已配置”和“数据库租户映射已启用”两个条件同时成立时，公开登录页才展示对应企业登录按钮。

自动开户开启后，用户首次登录会创建 OIDC 专用账号并绑定 `(providerId, subject)`；系统不会因为邮箱或用户名相同而自动合并本地账号。关闭自动开户后，未绑定身份会被拒绝。

## 3. 登录安全链路

```text
浏览器 → /oauth2/authorization/corp → 企业 IdP
企业 IdP → /login/oauth2/code/corp → Spring Security 校验
OpsFlow → 创建 256 位随机交换码，仅保存 SHA-256 摘要
浏览器 → /sso/callback?code=... → POST /api/v1/auth/sso/exchange
OpsFlow → 原子消费交换码 → 返回短期 JWT
```

交换码默认 2 分钟过期，只能成功消费一次。可通过 `OPSFLOW_OIDC_EXCHANGE_TTL` 调整，但不建议扩大窗口。前端完成交换后会立即从地址栏移除查询参数。

## 4. 细粒度权限

角色仍提供稳定的默认权限集：

- ADMIN：全部管理权限。
- AGENT：工单读写、知识维护和运营数据读取。
- REQUESTER：基础工单读取能力，具体数据仍受申请人资源边界约束。

管理员可以为某个用户设置 `ALLOW` 或 `DENY` 覆盖；`DENY` 优先于角色默认授权。保存策略后，目标账号 `tokenVersion` 自动递增，历史 JWT 在下一次请求时失效。系统禁止当前管理员显式拒绝自己的 `PERMISSION_MANAGE`，但仍建议至少保留一个不依赖 OIDC 的应急管理员。

当前细粒度权限已用于用户管理、身份提供商管理和权限策略管理；原有业务模块继续保留租户隔离、角色判断和资源所有权校验。

## 5. 验证

```bash
mvn test
mvn -Dtest=DatabaseMigrationIT test
cd frontend && npm run build
```

真实 OIDC 联调至少验证：允许域名成功登录、非允许域名被拒绝、缺少必要用户组被拒绝、交换码重放失败、停用映射后登录入口消失，以及本地应急管理员仍可登录。
