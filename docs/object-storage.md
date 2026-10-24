# OpsFlow 附件对象存储

OpsFlow 的附件业务通过 `AttachmentObjectStore` 适配层与具体介质解耦。默认使用本地持久化卷；启用 S3 适配器后可连接 Amazon S3、MinIO 及其他兼容实现。每条附件记录固化 `storageBackend`、对象键、ETag 和可选版本号，因此切换默认后端后，历史本地附件仍由本地适配器读取，新附件写入新的默认后端。

## 安全链路

1. 服务端校验工单可见范围、文件大小、扩展名和实际文件签名。
2. 文件写入 `quarantine/{tenantId}/{ticketId}/{uuid}`，数据库记录进入 `PENDING`。
3. 扫描任务从对应后端把对象受控暂存到临时目录，执行 SHA-256、压缩包策略和可选 ClamAV 检查。
4. 通过扫描后，对象移动到 `clean/` 前缀；感染或失败对象继续留在 `quarantine/`。
5. 只有 `CLEAN` 或 `RELEASED` 状态允许下载。S3 模式返回短时预签名地址，本地模式继续使用带 JWT 的代理下载。
6. 删除附件先提交数据库事务，事务成功后再清理对象，避免数据库回滚时丢失仍然有效的附件记录。

对象桶必须保持私有。预签名地址默认 5 分钟失效，最长被应用限制为 1 小时；地址只会在租户和工单资源级授权通过后签发。

## 本地 S3 演示

仓库的开发 profile 使用 RustFS 1.0.3 提供本地 S3 兼容端点。OpsFlow 只依赖标准 S3 API，连接已有 MinIO、Amazon S3 或其他兼容服务时不需要修改业务代码。

在仓库根目录创建不会提交到 Git 的 `.env`：

```dotenv
OPSFLOW_STORAGE_TYPE=S3
OPSFLOW_STORAGE_S3_ENABLED=true
OPSFLOW_STORAGE_S3_ENDPOINT=http://object-storage:9000
OPSFLOW_STORAGE_S3_PUBLIC_ENDPOINT=http://localhost:9000
OPSFLOW_STORAGE_S3_REGION=us-east-1
OPSFLOW_STORAGE_S3_ACCESS_KEY=opsflow
OPSFLOW_STORAGE_S3_SECRET_KEY=change-this-object-storage-password
OPSFLOW_STORAGE_S3_BUCKET=opsflow-attachments
OPSFLOW_STORAGE_S3_PATH_STYLE=true
OPSFLOW_STORAGE_S3_AUTO_CREATE_BUCKET=true
```

然后启动对象存储 profile：

```bash
docker compose --profile object-storage up -d --build
```

S3 API 位于 `http://localhost:9000`，RustFS 管理控制台位于 `http://localhost:9001`。本地示例密码只用于个人开发环境，共享或公网环境必须更换。

## 连接托管 S3

生产环境至少配置：

```dotenv
OPSFLOW_STORAGE_TYPE=S3
OPSFLOW_STORAGE_S3_ENABLED=true
OPSFLOW_STORAGE_S3_REGION=ap-southeast-1
OPSFLOW_STORAGE_S3_BUCKET=company-opsflow-attachments
OPSFLOW_STORAGE_S3_PATH_STYLE=false
OPSFLOW_STORAGE_S3_AUTO_CREATE_BUCKET=false
```

AWS 环境可不填写静态密钥，改用实例、容器或工作负载身份；其他 S3 兼容服务可额外配置内部 `OPSFLOW_STORAGE_S3_ENDPOINT` 和浏览器可访问的 `OPSFLOW_STORAGE_S3_PUBLIC_ENDPOINT`。两个静态密钥必须同时设置，且只允许通过部署密钥系统注入。

建议为桶启用服务端加密、版本控制、访问日志和生命周期规则。`quarantine/` 前缀不得公开；需要自动清理时，应根据组织留存策略处理已删除对象版本和过期隔离证据，不能绕过 OpsFlow 的审计与人工处置流程。

## 运维验证

- `GET /api/v1/admin/attachment-security/storage` 查看当前后端、可用适配器、桶名和预签名有效期。
- 上传测试附件后确认数据库 `storage_backend` 为 `S3`，并观察对象从 `quarantine/` 移动到 `clean/`。
- 复制预签名地址时不得写入工单评论、日志或监控标签；该地址虽然短时有效，但在失效前等同于临时凭据。
- 切换后端前先备份数据库、本地附件卷和对象桶，并分别执行恢复演练。
