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

## 大文件分片直传与断点续传

普通附件仍经过 OpsFlow 后端执行即时文件签名校验。超过普通上传上限后，前端改用 S3 Multipart Upload：

1. `POST /api/v1/tickets/{ticketId}/attachments/uploads` 创建上传会话。服务端校验工单可见范围、文件名、扩展名、声明大小和 S3 能力，并把 provider upload ID 仅保存在数据库。
2. 前端按默认 8 MiB 切片，逐片请求短时 `UploadPart` 签名地址，最多并发上传 3 片；对象键始终位于当前租户和工单的 `quarantine/` 前缀。
3. 浏览器只保存会话 ID 和文件指纹。网络中断后重新选择同一文件，系统通过 `ListParts` 获取已经成功落盘的分片，只上传缺失部分。
4. 完成时前端提交完整的分片编号和 ETag。服务端调用 S3 合并后再次读取对象元数据，实际大小必须与会话声明完全一致，随后才创建 `PENDING` 附件记录。
5. 安全任务以流式方式计算 SHA-256、识别 EICAR/可执行文件和实际内容签名，避免大文件整体进入 JVM 堆；通过检查后才从 `quarantine/` 移动至 `clean/`。
6. 用户取消会中止 Multipart Upload；默认 24 小时未完成的会话由后台任务清理，已结束会话记录默认保留 7 天用于幂等和排障。

默认上限为 1 GiB，分片签名 15 分钟有效。S3 单分片除最后一片外不得小于 5 MiB，配置校验会阻止不合法的分片大小。浏览器直传需要对象桶允许 OpsFlow 页面来源执行 `PUT`；本地 RustFS profile 会自动配置 `http://localhost:8081`，托管 S3 建议由基础设施代码显式管理 CORS，并保持应用侧自动配置关闭。

## 租户配额与对象一致性治理

每个租户拥有独立附件配额。系统在普通上传事务和 Multipart 会话创建事务中锁定租户策略行，并以“业务附件大小 + 未结束上传会话的声明大小”计算占用，避免并发请求同时通过检查。默认配额为 10 GiB、预警阈值为 80%，管理员可以在附件安全中心调整；新配额不得低于当前已使用与预留空间。

后台默认每 6 小时执行一次只读巡检，也可以由管理员立即触发。巡检分别读取 `quarantine/{tenantId}/` 和 `clean/{tenantId}/`：

- 对象或版本没有附件记录、活动上传会话引用时，记录为孤儿对象。
- 已引用对象的非当前 S3 版本记录为历史版本；最新版本始终受到保护。
- 无业务引用的 S3 删除标记单独展示，避免清理版本时遗漏。
- 数据库引用对应的最新对象不存在时产生缺失对象告警，该类型只能排障或忽略，不能执行删除。

系统不会定时删除对象。孤儿对象和历史版本经过默认 24 小时观察期后，管理员仍需在页面逐项确认；服务端删除前会再次查询附件及活动上传会话，校验对象键，并把操作者、原因、对象键、版本号和大小写入审计日志。这样既能回收复制移动、事务回滚或外部工具遗留的对象，也不会让配置错误直接演变成数据丢失。

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
OPSFLOW_STORAGE_S3_AUTO_CONFIGURE_CORS=true
OPSFLOW_STORAGE_S3_AUTO_CONFIGURE_VERSIONING=true
OPSFLOW_STORAGE_S3_CORS_ALLOWED_ORIGINS=http://localhost:8081
OPSFLOW_STORAGE_MULTIPART_ENABLED=true
OPSFLOW_STORAGE_MULTIPART_PART_SIZE=8388608
OPSFLOW_STORAGE_MULTIPART_MAX_FILE_SIZE=1073741824
OPSFLOW_STORAGE_MULTIPART_PRESIGNED_TTL=PT15M
OPSFLOW_STORAGE_MULTIPART_SESSION_TTL=PT24H
OPSFLOW_STORAGE_GOVERNANCE_ENABLED=true
OPSFLOW_STORAGE_DEFAULT_QUOTA_BYTES=10737418240
OPSFLOW_STORAGE_DEFAULT_WARNING_PERCENT=80
OPSFLOW_STORAGE_ORPHAN_GRACE_PERIOD=PT24H
OPSFLOW_STORAGE_MAX_OBJECTS_PER_SCAN=10000
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
OPSFLOW_STORAGE_S3_AUTO_CONFIGURE_CORS=false
OPSFLOW_STORAGE_S3_AUTO_CONFIGURE_VERSIONING=false
OPSFLOW_STORAGE_MULTIPART_ENABLED=true
OPSFLOW_STORAGE_MULTIPART_PART_SIZE=8388608
OPSFLOW_STORAGE_MULTIPART_MAX_FILE_SIZE=1073741824
OPSFLOW_STORAGE_GOVERNANCE_ENABLED=true
OPSFLOW_STORAGE_DEFAULT_QUOTA_BYTES=10737418240
OPSFLOW_STORAGE_ORPHAN_GRACE_PERIOD=PT24H
```

AWS 环境可不填写静态密钥，改用实例、容器或工作负载身份；其他 S3 兼容服务可额外配置内部 `OPSFLOW_STORAGE_S3_ENDPOINT` 和浏览器可访问的 `OPSFLOW_STORAGE_S3_PUBLIC_ENDPOINT`。两个静态密钥必须同时设置，且只允许通过部署密钥系统注入。

建议为桶启用服务端加密、版本控制、访问日志和生命周期规则。`quarantine/` 前缀不得公开；需要自动清理时，应根据组织留存策略处理已删除对象版本和过期隔离证据，不能绕过 OpsFlow 的审计与人工处置流程。

## 运维验证

- `GET /api/v1/admin/attachment-security/storage` 查看当前后端、可用适配器、桶名和预签名有效期。
- `GET /api/v1/admin/attachment-security/governance/summary` 查看配额、占用、预留、可回收空间和缺失对象数量。
- `POST /api/v1/admin/attachment-security/governance/reconcile` 立即执行当前租户对象一致性巡检；结果较大时会返回 `truncated=true`，应先提高扫描上限或缩小桶内非 OpsFlow 数据范围。
- `GET /api/v1/tickets/{ticketId}/attachments/upload-capabilities` 查看普通上传上限、分片上限、分片大小与会话有效期。
- 上传测试附件后确认数据库 `storage_backend` 为 `S3`，并观察对象从 `quarantine/` 移动到 `clean/`。
- 复制预签名地址时不得写入工单评论、日志或监控标签；该地址虽然短时有效，但在失效前等同于临时凭据。
- 切换后端前先备份数据库、本地附件卷和对象桶，并分别执行恢复演练。
