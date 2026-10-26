#!/usr/bin/env node

const baseUrl = (process.env.OPSFLOW_BASE_URL || "http://localhost:8081").replace(/\/$/, "");
const username = process.env.OPSFLOW_DEMO_USERNAME;
const password = process.env.OPSFLOW_DEMO_PASSWORD;
const dryRun = process.argv.includes("--dry-run");
const allowRemote = process.env.OPSFLOW_DEMO_ALLOW_REMOTE === "true";

const localHosts = new Set(["localhost", "127.0.0.1", "::1"]);
const target = new URL(baseUrl);
if (!localHosts.has(target.hostname) && !allowRemote) {
  throw new Error("为避免污染共享环境，演示数据脚本默认只允许 localhost；确认后设置 OPSFLOW_DEMO_ALLOW_REMOTE=true");
}

const plan = [
  "登录并确认 ADMIN 权限",
  "创建或复用 VPN 核心网关 CMDB 配置项",
  "创建或复用 Network Operations 支持组，并加入当前管理员",
  "创建或复用 VPN 接入服务目录项",
  "创建并发布 VPN 故障排查知识文章",
  "创建演示工单并关联支持组和 CMDB 配置项",
  "创建一条待审批的 VPN 服务申请",
];

if (dryRun) {
  console.log(`OpsFlow 演示数据目标：${baseUrl}`);
  plan.forEach((item, index) => console.log(`${index + 1}. ${item}`));
  console.log("\n--dry-run 未写入任何数据。");
  process.exit(0);
}

if (!username || !password) {
  throw new Error("请设置 OPSFLOW_DEMO_USERNAME 和 OPSFLOW_DEMO_PASSWORD（需要 ADMIN 账号）");
}

let token;
async function api(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      Accept: "application/json",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.success === false) {
    throw new Error(`${options.method || "GET"} ${path} -> HTTP ${response.status}: ${payload.message || "请求失败"}`);
  }
  return payload.data;
}

function pageRecords(page) {
  return page?.records || page?.content || [];
}

function logResult(label, entity, reused) {
  console.log(`${reused ? "复用" : "创建"} ${label}: id=${entity.id}`);
}

const session = await api("/api/v1/auth/login", {
  method: "POST",
  body: JSON.stringify({ username, password }),
});
token = session.token;
if (!(session.roles || []).includes("ADMIN")) {
  throw new Error(`账号 ${session.username} 不是 ADMIN，无法创建完整演示数据`);
}

const assetNo = "DEMO-VPN-GW-01";
const assets = await api(`/api/v1/cmdb/assets?keyword=${encodeURIComponent(assetNo)}&limit=100`);
let asset = (assets || []).find((item) => item.assetNo === assetNo);
const assetReused = Boolean(asset);
if (!asset) {
  asset = await api("/api/v1/cmdb/assets", {
    method: "POST",
    body: JSON.stringify({
      assetNo,
      name: "VPN 核心接入网关（演示）",
      assetType: "NETWORK",
      environment: "PRODUCTION",
      status: "ACTIVE",
      criticality: "CRITICAL",
      ownerId: session.userId,
      businessSystem: "员工远程接入",
      location: "华南主机房",
      description: "用于 OpsFlow 端到端演示的核心网络配置项",
      metadataJson: JSON.stringify({ vendor: "DemoNet", cluster: "vpn-prod", seededBy: "quality/demo-seed.mjs" }),
    }),
  });
}
logResult("CMDB 配置项", asset, assetReused);

const groupCode = "NETWORK_OPS_DEMO";
const groups = await api(`/api/v1/support-groups?keyword=${encodeURIComponent(groupCode)}`);
let group = (groups || []).find((item) => item.group?.groupCode === groupCode)?.group;
const groupReused = Boolean(group);
if (!group) {
  group = await api("/api/v1/support-groups", {
    method: "POST",
    body: JSON.stringify({
      groupCode,
      name: "Network Operations（演示）",
      description: "处理 VPN、网络与远程接入事件",
      managerId: session.userId,
    }),
  });
}
const groupDetail = await api(`/api/v1/support-groups/${group.id}`);
if (!(groupDetail.members || []).some((member) =>
  (member.membership?.userId ?? member.staff?.id) === session.userId)) {
  await api(`/api/v1/support-groups/${group.id}/members`, {
    method: "POST",
    body: JSON.stringify({ userId: session.userId, memberRole: "LEAD" }),
  });
}
logResult("支持组", group, groupReused);

const catalogName = "员工 VPN 接入";
const catalogItems = await api("/api/v1/service-catalog/items");
let catalogItem = (catalogItems || []).find((item) => item.name === catalogName);
const catalogReused = Boolean(catalogItem);
if (!catalogItem) {
  catalogItem = await api("/api/v1/service-catalog/items", {
    method: "POST",
    body: JSON.stringify({
      name: catalogName,
      description: "申请开通或恢复员工远程 VPN 接入权限",
      category: "网络与访问",
      defaultPriority: "HIGH",
      approvalRequired: true,
      enabled: true,
      sortOrder: 10,
    }),
  });
}
logResult("服务目录项", catalogItem, catalogReused);

const articleSlug = "demo-vpn-access-troubleshooting";
const articlePage = await api(`/api/v1/knowledge/articles?page=1&size=50&keyword=${encodeURIComponent("VPN 接入故障排查")}`);
let article = pageRecords(articlePage).find((item) => item.slug === articleSlug);
const articleReused = Boolean(article);
if (!article) {
  article = await api("/api/v1/knowledge/articles", {
    method: "POST",
    body: JSON.stringify({
      title: "VPN 接入故障排查手册（演示）",
      slug: articleSlug,
      category: "网络与访问",
      content: "## 适用范围\n员工无法连接企业 VPN。\n\n## 排查步骤\n1. 确认本地网络可用。\n2. 校验账号状态与双因素认证。\n3. 检查 VPN 网关健康和并发容量。\n4. 收集客户端日志并关联工单。\n\n## 升级条件\n核心网关不可用或 15 分钟内影响超过 20 人时升级为重大事件。",
    }),
  });
  article = await api(`/api/v1/knowledge/articles/${article.id}/publish`, { method: "POST" });
}
logResult("知识文章", article, articleReused);

const ticketTitle = "[OPS-DEMO] 员工 VPN 远程接入失败";
const ticketPage = await api(`/api/v1/tickets?page=1&size=50&keyword=${encodeURIComponent(ticketTitle)}`);
let ticket = pageRecords(ticketPage).find((item) => item.title === ticketTitle);
const ticketReused = Boolean(ticket);
if (!ticket) {
  ticket = await api("/api/v1/tickets", {
    method: "POST",
    headers: { "Idempotency-Key": "opsflow-demo-vpn-ticket-v1" },
    body: JSON.stringify({
      title: ticketTitle,
      description: "用户在家庭网络下连接企业 VPN 时持续收到握手超时，请检查账号状态、网关容量与链路健康。",
      category: "网络与访问",
      priority: "HIGH",
    }),
  });
}
await api(`/api/v1/support-groups/${group.id}/tickets/${ticket.id}`, { method: "POST" });
const ticketAssets = await api(`/api/v1/cmdb/tickets/${ticket.id}/assets`);
if (!(ticketAssets || []).some((item) => item.assetId === asset.id || item.asset?.id === asset.id)) {
  await api(`/api/v1/cmdb/tickets/${ticket.id}/assets`, {
    method: "POST",
    body: JSON.stringify({ assetId: asset.id, linkType: "AFFECTS", note: "演示工单关联的核心 VPN 网关" }),
  });
}
logResult("演示工单", ticket, ticketReused);

const requestSummary = "[OPS-DEMO] 新员工 VPN 权限开通";
const serviceRequests = await api("/api/v1/service-catalog/requests");
let serviceRequest = (serviceRequests || []).find((item) => item.summary === requestSummary);
const requestReused = Boolean(serviceRequest);
if (!serviceRequest) {
  serviceRequest = await api("/api/v1/service-catalog/requests", {
    method: "POST",
    headers: { "Idempotency-Key": "opsflow-demo-vpn-service-request-v1" },
    body: JSON.stringify({
      catalogItemId: catalogItem.id,
      summary: requestSummary,
      detail: "为演示账号申请 VPN 权限，审批通过后由 Network Operations 处理。",
    }),
  });
}
logResult("服务申请", serviceRequest, requestReused);

console.log("\n演示数据准备完成。重复执行会复用同名数据，不会批量制造重复记录。");
