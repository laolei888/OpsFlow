<script setup>
import { onMounted, onUnmounted, ref } from 'vue'

const snapshot = ref(null)
const loading = ref(true)
const error = ref('')
let refreshTimer

const statusLabels = {
  OPERATIONAL: '运行正常', DEGRADED: '性能下降', PARTIAL_OUTAGE: '部分不可用',
  MAJOR_OUTAGE: '重大故障', MAINTENANCE: '维护中'
}
const incidentLabels = { INVESTIGATING: '调查中', IDENTIFIED: '已定位', MONITORING: '持续观察', RESOLVED: '已解决' }
const impactLabels = { MINOR: '轻微影响', MAJOR: '较大影响', CRITICAL: '严重影响' }

function formatDate(value) {
  if (!value) return '刚刚'
  return new Date(value).toLocaleString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
function label(status) { return statusLabels[status] || status || '未知' }
function statusClass(status) { return 'status-' + String(status || 'OPERATIONAL').toLowerCase() }
function incidentClass(status) { return 'incident-' + String(status || '').toLowerCase() }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetch('/api/v1/status-page')
    const payload = await response.json()
    if (!response.ok || !payload.success) throw new Error(payload.message || '状态页暂时不可用')
    snapshot.value = payload.data
  } catch (err) {
    error.value = err.message || '状态页暂时不可用，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  refreshTimer = window.setInterval(load, 60_000)
})
onUnmounted(() => window.clearInterval(refreshTimer))
</script>

<template>
  <main class="public-status-page">
    <header class="public-header">
      <a href="/" class="public-brand"><span>OF</span><div><strong>OpsFlow</strong><small>Service Status</small></div></a>
      <button type="button" class="refresh-button" :disabled="loading" @click="load">{{ loading ? '同步中…' : '↻ 刷新状态' }}</button>
    </header>

    <section v-if="loading && !snapshot" class="public-loading"><i></i><p>正在同步服务状态…</p></section>
    <section v-else-if="error && !snapshot" class="public-error"><h1>暂时无法读取服务状态</h1><p>{{ error }}</p><button type="button" @click="load">重试</button></section>

    <template v-else-if="snapshot">
      <section :class="['overall-banner', statusClass(snapshot.status)]">
        <span class="overall-dot"></span>
        <div><p>OPSFLOW SERVICE STATUS</p><h1>{{ label(snapshot.status) }}</h1><span>{{ snapshot.message }}</span></div>
        <time>更新于 {{ formatDate(snapshot.updatedAt) }}</time>
      </section>

      <section class="public-content">
        <div v-if="error" class="refresh-warning">刚刚刷新失败，当前展示的是上一次成功同步的状态：{{ error }}</div>
        <section class="status-section">
          <div class="section-title"><div><p>SERVICE COMPONENTS</p><h2>服务组件</h2></div><span>{{ snapshot.components?.length || 0 }} 项公开服务</span></div>
          <div v-if="!snapshot.components?.length" class="empty-public">暂未配置公开服务组件。</div>
          <div v-else class="component-list">
            <article v-for="component in snapshot.components" :key="component.slug" class="public-component">
              <div><h3>{{ component.name }}</h3><p>{{ component.description || '该服务当前暂无补充说明。' }}</p><small v-if="component.statusMessage">{{ component.statusMessage }}</small></div>
              <span :class="['component-status', statusClass(component.status)]"><i></i>{{ label(component.status) }}</span>
            </article>
          </div>
        </section>

        <section class="status-section incident-section">
          <div class="section-title"><div><p>INCIDENT HISTORY</p><h2>服务公告</h2></div><span>仅展示已审核发布的信息</span></div>
          <div v-if="!snapshot.incidents?.length" class="empty-public">当前没有已发布的服务公告。</div>
          <article v-for="incident in snapshot.incidents" :key="incident.publicNo" class="public-incident">
            <div class="incident-heading">
              <div><div class="incident-chips"><span :class="['incident-status', incidentClass(incident.status)]">{{ incidentLabels[incident.status] || incident.status }}</span><span class="impact-chip">{{ impactLabels[incident.impact] || incident.impact }}</span></div><h3>{{ incident.title }}</h3><p>{{ incident.summary }}</p></div>
              <time>{{ incident.status === 'RESOLVED' ? '恢复于 ' + formatDate(incident.resolvedAt) : '发布于 ' + formatDate(incident.publishedAt) }}</time>
            </div>
            <div v-if="incident.componentSlugs?.length" class="affected-components">受影响服务：<span v-for="slug in incident.componentSlugs" :key="slug">{{ slug }}</span></div>
            <ol v-if="incident.updates?.length" class="public-timeline">
              <li v-for="(update, index) in incident.updates" :key="index"><i></i><div><strong>{{ incidentLabels[update.status] || update.status }}</strong><time>{{ formatDate(update.createdAt) }}</time><p>{{ update.message }}</p></div></li>
            </ol>
          </article>
        </section>
      </section>
    </template>

    <footer class="public-footer">OpsFlow 服务状态中心 · 公开信息经人工审核后发布</footer>
  </main>
</template>

<style scoped>
.public-status-page { min-height: 100vh; background: #f5f7fb; color: #172033; font-family: Inter, "Microsoft YaHei", system-ui, sans-serif; }
.public-header { max-width: 1080px; margin: auto; height: 86px; display: flex; align-items: center; justify-content: space-between; padding: 0 24px; }
.public-brand { display: flex; align-items: center; gap: 11px; color: inherit; text-decoration: none; }.public-brand > span { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 10px; color: white; background: #16243d; font-weight: 800; letter-spacing: -.08em; }.public-brand strong, .public-brand small { display: block; }.public-brand small { font-size: 11px; color: #8190a8; margin-top: 2px; letter-spacing: .04em; }
.refresh-button { border: 1px solid #d7deea; border-radius: 8px; background: white; color: #475569; padding: 9px 13px; cursor: pointer; font-weight: 600; }.refresh-button:disabled { opacity: .55; cursor: wait; }
.overall-banner { max-width: 1080px; margin: 0 auto; padding: 38px 42px; border-radius: 16px; display: flex; align-items: center; gap: 18px; color: white; box-sizing: border-box; }.overall-banner p { font-size: 11px; opacity: .78; letter-spacing: .12em; margin: 0 0 7px; font-weight: 700; }.overall-banner h1 { font-size: 30px; margin: 0 0 7px; letter-spacing: -.04em; }.overall-banner div > span { opacity: .88; }.overall-banner time { margin-left: auto; align-self: flex-end; font-size: 12px; opacity: .75; }.overall-dot { width: 13px; height: 13px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 7px rgba(255,255,255,.13); }.status-operational { background: #147a55; }.status-degraded { background: #a66308; }.status-partial_outage, .status-major_outage { background: #b83b45; }.status-maintenance { background: #43639a; }
.public-content { max-width: 1080px; padding: 36px 24px 58px; margin: auto; }.status-section { margin-bottom: 42px; }.section-title { display: flex; justify-content: space-between; align-items: end; margin-bottom: 15px; }.section-title p { color: #76849a; font-size: 10px; font-weight: 800; letter-spacing: .12em; margin: 0 0 5px; }.section-title h2 { margin: 0; font-size: 20px; letter-spacing: -.03em; }.section-title > span { color: #8491a5; font-size: 12px; }
.component-list, .public-incident { background: white; border: 1px solid #e5e9f0; border-radius: 13px; box-shadow: 0 5px 18px rgba(15, 23, 42, .035); }.public-component { padding: 20px 22px; display: flex; align-items: center; justify-content: space-between; gap: 18px; border-bottom: 1px solid #eef1f5; }.public-component:last-child { border-bottom: 0; }.public-component h3, .public-incident h3 { font-size: 15px; margin: 0 0 6px; }.public-component p, .public-incident p { margin: 0; color: #66748a; font-size: 13px; line-height: 1.65; }.public-component small { display: block; margin-top: 7px; color: #4e607a; font-size: 12px; }.component-status { background: #eef4f1; color: #147a55; padding: 7px 10px; border-radius: 999px; font-size: 12px; min-width: 86px; white-space: nowrap; text-align: center; }.component-status i { display: inline-block; height: 6px; width: 6px; border-radius: 50%; background: currentColor; margin-right: 6px; }.component-status.status-degraded { background: #fff4df; color: #a66308; }.component-status.status-partial_outage, .component-status.status-major_outage { background: #fff0f1; color: #b83b45; }.component-status.status-maintenance { background: #edf2fc; color: #43639a; }
.public-incident { padding: 23px; margin-bottom: 15px; }.incident-heading { display: flex; justify-content: space-between; gap: 20px; }.incident-heading time { flex: none; color: #8793a6; font-size: 12px; }.incident-chips { display: flex; gap: 7px; margin-bottom: 10px; }.incident-status, .impact-chip { border-radius: 999px; padding: 4px 8px; font-size: 11px; font-weight: 700; }.incident-investigating { background: #fff4df; color: #a66308; }.incident-identified { background: #ffecec; color: #b83b45; }.incident-monitoring { background: #edf2fc; color: #43639a; }.incident-resolved { background: #eaf7f0; color: #147a55; }.impact-chip { background: #f0f3f7; color: #65738a; }.affected-components { margin-top: 16px; color: #77869a; font-size: 12px; }.affected-components span { margin-left: 5px; border: 1px solid #e4e9f0; padding: 3px 7px; border-radius: 5px; color: #4e5d74; background: #fafbfc; }
.public-timeline { padding: 4px 0 0 2px; list-style: none; margin: 19px 0 0; border-top: 1px solid #edf0f4; }.public-timeline li { position: relative; display: flex; gap: 13px; padding: 18px 0 0; }.public-timeline li > i { width: 9px; height: 9px; margin-top: 4px; flex: none; border-radius: 50%; background: #5d738f; box-shadow: 0 0 0 4px #edf2f7; }.public-timeline strong { font-size: 13px; }.public-timeline time { margin-left: 10px; color: #8995a7; font-size: 11px; }.public-timeline p { margin-top: 5px; }
.empty-public, .public-loading, .public-error { text-align: center; background: white; border: 1px dashed #dce3ec; border-radius: 12px; padding: 38px 20px; color: #728097; }.public-loading { min-height: 280px; padding-top: 150px; }.public-loading i { display: inline-block; width: 20px; height: 20px; border: 2px solid #d8e1ec; border-top-color: #426a9f; border-radius: 50%; animation: public-spin .8s linear infinite; }.public-error { max-width: 620px; margin: 90px auto; }.public-error h1 { color: #223149; }.public-error button { padding: 9px 16px; border: 0; border-radius: 7px; background: #213752; color: white; cursor: pointer; }.refresh-warning { margin-bottom: 18px; padding: 11px 14px; background: #fff7e8; color: #996417; border-radius: 8px; font-size: 12px; }.public-footer { text-align: center; padding: 25px; color: #8a96a8; font-size: 12px; border-top: 1px solid #e5e9f0; background: white; }
@keyframes public-spin { to { transform: rotate(360deg); } }
@media (max-width: 620px) { .public-header { height: 68px; padding: 0 15px; }.overall-banner { border-radius: 0; padding: 30px 24px; }.overall-banner time { display: none; }.public-content { padding: 26px 15px; }.public-component, .incident-heading { align-items: flex-start; flex-direction: column; }.component-status { min-width: 0; }.incident-heading time { margin-top: -12px; }.public-incident { padding: 18px; } }
</style>
