# OpsFlow 部署指南

仓库提供 `deploy/docker-compose.demo.yml`，用于把 GitHub Container Registry 中已经扫描和签名的 OpsFlow 镜像部署到一台 Linux 主机。该模板适合简历演示、个人测试和小规模验收，不等同于高可用生产架构。

## 1. 准备主机

建议使用安装了 Docker Engine 与 Compose 插件的 Linux 主机。只向公网开放 80/443；MySQL 和 Redis 位于 Compose 内部网络，不映射宿主机端口。

```bash
git clone https://github.com/laolei888/OpsFlow.git
cd OpsFlow
cp deploy/.env.demo.example deploy/.env.demo
```

编辑 `deploy/.env.demo`，替换全部 `CHANGE_ME`，并将 `OPSFLOW_CORS_ALLOWED_ORIGINS` 改为实际 HTTPS 域名。`JWT_SECRET` 至少使用 32 个随机字符，例如：

```bash
openssl rand -base64 48
```

如需初始化第一个管理员，同时填写 `OPSFLOW_BOOTSTRAP_USERNAME` 和 `OPSFLOW_BOOTSTRAP_PASSWORD`。账号创建成功后应从环境文件中删除这两个值并重新创建应用容器。

## 2. 启动与升级

```bash
docker compose --env-file deploy/.env.demo -f deploy/docker-compose.demo.yml pull
docker compose --env-file deploy/.env.demo -f deploy/docker-compose.demo.yml up -d
docker compose --env-file deploy/.env.demo -f deploy/docker-compose.demo.yml ps
curl --fail http://127.0.0.1:8081/actuator/health
```

升级时先备份数据库和附件卷，再拉取新镜像：

```bash
docker compose --env-file deploy/.env.demo -f deploy/docker-compose.demo.yml pull app
docker compose --env-file deploy/.env.demo -f deploy/docker-compose.demo.yml up -d app
```

`main` 标签跟随主分支。需要可重复部署时，将 `OPSFLOW_IMAGE_TAG` 固定为 Git 提交 SHA 标签或 `v*` 发布标签。

## 3. HTTPS 与域名

应用容器只提供 HTTP。应在主机前方配置 Caddy、Nginx、Traefik 或云负载均衡器终止 TLS，并把请求代理到 `127.0.0.1:8081`。不要把数据库、Redis、Swagger 或 Prometheus 指标直接暴露到公网。

模板启用了生产配置并默认关闭公开注册、Swagger、公开指标、明文及私网出站访问；应用容器同时采用只读根文件系统、非 root 用户、能力移除和 `no-new-privileges`。单机模板中的应用到 MySQL 流量只经过隔离的 Docker 网络，因此 JDBC 示例关闭了 TLS；跨主机或使用托管数据库时必须改用服务商的 TLS 连接参数。

## 4. 镜像供应链

`Supply Chain Security` 工作流对同一个构建镜像依次执行 Trivy 漏洞扫描、SPDX JSON SBOM 生成、GHCR 发布和 GitHub artifact attestation。修复方案已存在的 CRITICAL 漏洞会阻断发布，高危与严重发现会上传到仓库 Code Scanning。

可使用 GitHub CLI 验证镜像来源证明：

```bash
gh attestation verify oci://ghcr.io/laolei888/opsflow:main --repo laolei888/OpsFlow
```

每次工作流运行还会保存对应的 SPDX SBOM 构件。仓库同时启用了 CodeQL、Dependabot、安全更新、Secret Scanning、Push Protection 和私密漏洞报告。

## 5. 上线前检查

- 使用不可复用的数据库、Redis、JWT 和管理员密码。
- 通过 HTTPS 域名访问，并确认 CORS 只包含该域名。
- 验证注册、文档和指标端点未对公网开放。
- 建立 MySQL 与附件卷的定期备份和恢复演练。
- 为主机、防火墙、磁盘、证书和容器状态配置外部监控。
- 正式生产环境应使用托管数据库、Redis、高可用编排、集中密钥管理和独立对象存储。
