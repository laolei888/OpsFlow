# 关键链路韧性与受控混沌演练

OpsFlow 的“韧性演练”是混沌工程控制平面：它管理稳态假设、恢复目标、状态机、验证门禁和审计证据，但不会从 Web 应用执行 `docker stop`、网络阻断、进程终止、数据库破坏或任何生产环境命令。

## 安全模型

- 只允许 `ADMIN` 创建和推进实验。
- 环境名为 `prod*`、包含 `production`/`生产` 或等于 `live` 时，后端直接拒绝创建。
- 实际故障注入与回滚必须由获授权人员在隔离环境执行，平台只接收明确的人工确认和证据。
- 基线检查失败时禁止进入故障阶段；阶段未完成时不能越级推进或提前填写未来阶段证据。
- 进入下一阶段后，上一阶段证据被冻结，不能回写历史结论。
- 状态更新使用乐观锁，避免两个管理员并发推进同一实验。
- 每次创建、开始、故障确认、恢复、检查记录、完成和取消都写入租户审计日志。

## 状态机

```text
PLANNED → BASELINE → FAULT_INJECTED → RECOVERING → PASSED / FAILED
    └────────────── 任意未结束阶段可取消 ──────────────→ CANCELLED
```

系统为每场实验生成六项门禁：

1. 故障前关键链路健康；
2. 隔离环境故障注入证据；
3. 降级或容错行为符合预期；
4. 告警与值班链路被触发；
5. 依赖恢复后关键链路健康；
6. 数据一致性与幂等性通过。

完成实验时，系统从“确认故障已注入”开始计算恢复耗时。所有必需门禁均无失败且耗时不超过目标时结果为 `PASSED`，否则为 `FAILED`。警告可以保留风险说明，但不会单独阻断通过。

## 覆盖目标

- `DATABASE`：连接中断、快速失败、事务与审计一致性；
- `REDIS`：缓存不可用、事实数据保护和缓存重建；
- `RABBITMQ`：Outbox 保留、积压告警、幂等消费和死信治理；
- `OBJECT_STORAGE`：上传失败语义、隔离区与元数据一致性；
- `WEBHOOK_DELIVERY`：超时重试、失败分类、死信与受控重放。

页面会按目标显示注入建议、预期行为和回滚指引。这些内容是演练清单，不是自动执行脚本。

## 管理接口

```text
GET  /api/v1/admin/chaos-experiments
POST /api/v1/admin/chaos-experiments
GET  /api/v1/admin/chaos-experiments/{id}
POST /api/v1/admin/chaos-experiments/{id}/start
POST /api/v1/admin/chaos-experiments/{id}/fault-injected
POST /api/v1/admin/chaos-experiments/{id}/recovery
PUT  /api/v1/admin/chaos-experiments/{experimentId}/checks/{checkId}
POST /api/v1/admin/chaos-experiments/{id}/complete
POST /api/v1/admin/chaos-experiments/{id}/cancel
```

## 推荐首场实验

本地演示环境建议选择 RabbitMQ：先记录应用健康与队列基线，再由操作者暂停测试 Broker，验证事务 Outbox、积压告警和失败分类；恢复 Broker 后观察积压清空，最后确认同一幂等键未产生重复副作用。任何暂停和恢复动作都应在 OpsFlow 页面之外执行。
