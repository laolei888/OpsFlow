# 备份恢复与灾难演练

OpsFlow 的灾备模块是控制平面，不是数据库管理员。它负责固化恢复目标、管理备份证据、评估备份是否满足策略，并把恢复演练变成可审计的门禁流程。生产数据的备份和恢复仍由数据库、对象存储或基础设施平台执行。

## 安全边界

- Web 应用不持有 MySQL 管理员权限，也不执行 `DROP`、`RESTORE`、`mysql` 或 `mysqldump` 命令。
- 备份必须先由外部基础设施作业生成，OpsFlow 只登记制品位置和元数据。
- 恢复演练必须使用隔离数据库、独立对象桶或临时命名空间，不能把生产地址作为演练环境。
- 备份登记、策略变更、校验、演练开始、门禁结果和最终结论全部写入租户审计日志。
- 保留期后台任务只把过期台账标记为 `EXPIRED`，不会自动删除真实备份制品。

## 策略模型

每个租户可以维护一份灾备策略：

- RPO：允许丢失的数据时间窗口，范围 5-10080 分钟。
- RTO：从开始恢复到服务可验证的最长时间，范围 5-10080 分钟。
- 保留期：备份台账的可用期限，范围 1-3650 天。
- 复验周期：备份完整性证据需要重新确认的周期，范围 1-720 小时。
- 加密要求、不可变副本要求和跨区域副本要求。

租户未保存策略时使用安全默认值：RPO 60 分钟、RTO 120 分钟、保留 30 天、每 24 小时复验、强制加密和不可变副本。

## 备份登记与校验

支持 `FULL`、`DATABASE` 和 `OBJECT_STORAGE` 三类备份。登记时需要提供：

- 恢复点、采集开始时间和完成时间；
- 备份大小与 64 位 SHA-256；
- 存储提供方、区域和不包含凭据的制品位置；
- 是否加密、不可变保护截止时间、是否已有跨区域副本；
- 外部作业编号、日志位置或审批记录等证据。

系统会校验备份时间顺序、SHA-256 格式、制品大小、恢复点间隔、加密要求、不可变期限和跨区域策略。结果为 `PASSED`、`WARNING` 或 `FAILED`。就绪度还会结合最新恢复点年龄与复验周期生成 `HEALTHY`、`WARNING`、`CRITICAL` 或 `DISABLED` 状态。

## 隔离恢复演练

使用可用备份创建演练时，系统会固化当时的 RPO/RTO，并生成以下门禁：

1. 备份制品可访问；
2. 备份策略与校验和通过；
3. 静态加密验证；
4. 不可变副本验证；
5. 数据库恢复与一致性校验；
6. 对象存储清单校验；
7. 登录、工单查询、附件读取和审计链路冒烟测试。

开始演练后才启动 RTO 计时。操作人员逐项记录通过、警告或失败以及查询结果、日志编号或截图位置。全部必需检查项完成后，系统根据备份恢复点、开始/完成时间和门禁结果自动计算实际 RPO、实际 RTO、完整性得分和最终通过状态。

## 管理接口

所有接口仅允许 `ADMIN` 使用：

```text
GET  /api/v1/admin/disaster-recovery
PUT  /api/v1/admin/disaster-recovery/policy
POST /api/v1/admin/disaster-recovery/backups
POST /api/v1/admin/disaster-recovery/backups/{id}/verify
POST /api/v1/admin/disaster-recovery/drills
GET  /api/v1/admin/disaster-recovery/drills/{id}
POST /api/v1/admin/disaster-recovery/drills/{id}/start
PUT  /api/v1/admin/disaster-recovery/drills/{drillId}/checks/{checkId}
POST /api/v1/admin/disaster-recovery/drills/{id}/complete
POST /api/v1/admin/disaster-recovery/drills/{id}/cancel
```

## 生产落地建议

1. 使用云数据库 PITR、MySQL Enterprise Backup 或受控 `mysqldump` 作业生成数据库制品。
2. 为对象存储启用版本控制、Object Lock 和跨区域复制。
3. 使用 KMS 管理备份密钥，应用日志和台账中不得保存访问密钥。
4. 外部备份作业完成后由受签名保护的 Agent 回调登记制品；当前版本先由管理员登记。
5. 至少每季度执行一次完整恢复演练，并把演练发现转成问题、变更或容量治理任务。
