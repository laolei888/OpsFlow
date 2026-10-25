# 通知模板审批、多语言与发送时间窗

OpsFlow 将外部通知视为需要审批和留痕的生产配置。模板内容、接收语言和投递时间均由服务端重新校验，前端只负责提交配置。

## 模板审批状态机

模板路由由 `tenant + eventType + channel + locale` 唯一确定，当前支持 `zh-CN` 和 `en-US`。

1. 创建模板或新版本后状态为 `DRAFT`。
2. 创建人提交后进入 `PENDING_APPROVAL`。
3. 另一位管理员执行批准或驳回；创建人不能审核自己的版本。
4. 批准后进入 `APPROVED`，只有该状态可以发布。
5. 发布后进入 `PUBLISHED`，原活动版本转为 `ARCHIVED`。
6. 驳回版本保留为 `REJECTED`，审核意见、审核人和审核时间作为证据保留。

发布、启停、提交和审核动作均写入租户审计日志。模板变量仅允许白名单字段，未知变量和不完整花括号会在保存前被拒绝。

## 多语言路由

用户在通知偏好中选择接收语言。外部渠道渲染顺序如下：

1. 当前事件 + 接收人语言；
2. 通配事件 `*` + 接收人语言；
3. 当前事件 + `zh-CN`；
4. 通配事件 `*` + `zh-CN`；
5. 未命中时使用业务通知原文。

投递记录固化 `templateVersionId`、`deliveryLocale`、渲染标题和渲染正文，模板后续修改不会覆盖历史证据。

## 发送时间窗

用户可以配置 IANA 时区、开始时间和结束时间。时间窗支持同日区间以及跨午夜区间，例如 `22:00–07:00`。

- 站内信不受时间窗影响，始终即时写入。
- 邮件、个人 Webhook 和企业微信在窗口外保持 `PENDING`，`nextAttemptAt` 指向下一个允许时间。
- `scheduledReason` 保存用户时区和窗口，用于管理端解释延迟原因。
- 重大事件、重大事件状态变化、值班升级、监控告警和 SLA 逾期可配置越过时间窗，避免静默期阻断紧急响应。
- 人工重试会重新读取当前目标地址和当前发送窗口，不会绕过用户最新策略。

## 管理接口

```text
GET  /api/v1/admin/notification-templates?eventType=&channel=&locale=
POST /api/v1/admin/notification-templates
POST /api/v1/admin/notification-templates/{id}/versions
POST /api/v1/admin/notification-templates/versions/{versionId}/submit
POST /api/v1/admin/notification-templates/versions/{versionId}/review
POST /api/v1/admin/notification-templates/versions/{versionId}/publish

GET  /api/v1/notification-preferences
PUT  /api/v1/notification-preferences
```

生产环境应至少保留两名可用管理员，以执行创建人与审核人分离的双人复核流程。
