#!/usr/bin/env node

const baseUrl = (process.env.OPSFLOW_BASE_URL || "http://localhost:8081").replace(/\/$/, "");
const username = process.env.OPSFLOW_ACCEPTANCE_USERNAME;
const password = process.env.OPSFLOW_ACCEPTANCE_PASSWORD;
const strictAuth = process.argv.includes("--strict-auth");
const skipOpenApi = process.argv.includes("--skip-openapi");
const results = [];

function record(name, status, detail) {
  results.push({ name, status, detail });
  const icon = status === "PASS" ? "✓" : status === "SKIP" ? "-" : "✗";
  console.log(`${icon} ${name}: ${detail}`);
}

async function request(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10000);
  try {
    return await fetch(`${baseUrl}${path}`, {
      redirect: "manual",
      ...options,
      signal: controller.signal,
      headers: {
        Accept: "application/json, text/html;q=0.9, */*;q=0.8",
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(options.headers || {}),
      },
    });
  } finally {
    clearTimeout(timeout);
  }
}

async function check(name, work) {
  try {
    const detail = await work();
    record(name, "PASS", detail);
  } catch (error) {
    record(name, "FAIL", error instanceof Error ? error.message : String(error));
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

await check("应用健康", async () => {
  const response = await request("/actuator/health");
  assert(response.status === 200, `HTTP ${response.status}`);
  const payload = await response.json();
  assert(payload.status === "UP", `status=${payload.status}`);
  return "Actuator status=UP";
});

await check("前端入口", async () => {
  const response = await request("/");
  assert(response.status === 200, `HTTP ${response.status}`);
  const html = await response.text();
  assert(/<html/i.test(html) && /<script/i.test(html), "未返回可运行的前端页面");
  return "首页 HTML 可加载";
});

await check("公开状态页", async () => {
  const page = await request("/status");
  assert(page.status === 200, `页面 HTTP ${page.status}`);
  const api = await request("/api/v1/status-page");
  assert(api.status === 200, `API HTTP ${api.status}`);
  const payload = await api.json();
  assert(payload.success === true, "状态页 API success=false");
  return "页面和匿名 API 均可用";
});

await check("未授权防护", async () => {
  const response = await request("/api/v1/tickets");
  assert(response.status === 401, `期望 401，实际 ${response.status}`);
  const payload = await response.json();
  assert(payload.success === false, "401 响应格式不符合统一响应约定");
  return "受保护接口返回统一 JSON 401";
});

if (skipOpenApi) {
  record("OpenAPI 契约入口", "SKIP", "已通过 --skip-openapi 跳过");
} else {
  await check("OpenAPI 契约入口", async () => {
    const response = await request("/api-docs");
    assert(response.status === 200, `HTTP ${response.status}（生产环境可使用 --skip-openapi）`);
    const payload = await response.json();
    const pathCount = Object.keys(payload.paths || {}).length;
    assert(pathCount >= 40, `接口数量异常：${pathCount}`);
    return `${pathCount} 个 API 路径`;
  });
}

if (username && password) {
  let token;
  let roles = [];
  await check("账号登录", async () => {
    const response = await request("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    assert(response.status === 200, `HTTP ${response.status}`);
    const payload = await response.json();
    assert(payload.success && payload.data?.token, payload.message || "未返回 token");
    token = payload.data.token;
    roles = payload.data.roles || [];
    return `${payload.data.username} [${roles.join(", ")}]`;
  });

  if (token) {
    const authenticatedChecks = [
      ["工单队列", "/api/v1/tickets?page=1&size=5"],
      ["运营驾驶舱", "/api/v1/dashboard/overview"],
      ["知识库", "/api/v1/knowledge/articles?page=1&size=5"],
      ["通知中心", "/api/v1/notifications?limit=5"],
    ];
    for (const [name, path] of authenticatedChecks) {
      await check(name, async () => {
        const response = await request(path, { headers: { Authorization: `Bearer ${token}` } });
        assert(response.status === 200, `HTTP ${response.status}`);
        const payload = await response.json();
        assert(payload.success === true, payload.message || "success=false");
        return "认证读取通过";
      });
    }

    if (roles.includes("ADMIN")) {
      for (const [name, path] of [
        ["灾备治理", "/api/v1/admin/disaster-recovery"],
        ["混沌演练", "/api/v1/admin/chaos-experiments?limit=5"],
      ]) {
        await check(name, async () => {
          const response = await request(path, { headers: { Authorization: `Bearer ${token}` } });
          assert(response.status === 200, `HTTP ${response.status}`);
          return "管理员读取通过";
        });
      }
    } else {
      record("管理员治理接口", "SKIP", "当前账号不是 ADMIN");
    }
  }
} else {
  const detail = "未设置 OPSFLOW_ACCEPTANCE_USERNAME/OPSFLOW_ACCEPTANCE_PASSWORD";
  record("认证业务验收", strictAuth ? "FAIL" : "SKIP", detail);
}

const failed = results.filter((item) => item.status === "FAIL");
const passed = results.filter((item) => item.status === "PASS").length;
const skipped = results.filter((item) => item.status === "SKIP").length;
console.log(`\nOpsFlow 最终验收：${passed} 项通过，${skipped} 项跳过，${failed.length} 项失败。`);
process.exitCode = failed.length ? 1 : 0;
