<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  staffMembers: { type: Array, default: () => [] },
  isAdmin: { type: Boolean, default: false }
})

const groups = ref([])
const selected = ref(null)
const ticketCandidates = ref([])
const loading = ref(false)
const detailLoading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const keyword = ref('')
const enabledFilter = ref('')
const showGroupModal = ref(false)
const editingGroup = ref(null)
const routeTicketId = ref('')
const memberUserId = ref('')
const memberRole = ref('MEMBER')
const groupForm = reactive({ groupCode: '', name: '', description: '', managerId: '' })

const activeGroups = computed(() => groups.value.filter(item => item.group.enabled).length)
const activeTickets = computed(() => groups.value.reduce((sum, item) => sum + Number(item.activeTicketCount || 0), 0))
const overdueTickets = computed(() => groups.value.reduce((sum, item) => sum + Number(item.overdueTicketCount || 0), 0))
const unassignedTickets = computed(() => groups.value.reduce((sum, item) => sum + Number(item.unassignedTicketCount || 0), 0))
const selectedGroup = computed(() => selected.value?.summary?.group || null)
const selectedSummary = computed(() => selected.value?.summary || null)
const memberIds = computed(() => new Set((selected.value?.members || []).map(item => String(item.membership.userId))))
const availableStaff = computed(() => props.staffMembers.filter(item => !memberIds.value.has(String(item.id))))
const routeCandidates = computed(() => ticketCandidates.value.filter(ticket =>
  String(ticket.assignmentGroupId || '') !== String(selectedGroup.value?.id || '')))
const groupNames = computed(() => new Map(groups.value.map(item => [String(item.group.id), item.group.name])))

const priorityLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', URGENT: '紧急' }
const statusLabels = { OPEN: '待处理', IN_PROGRESS: '处理中', WAITING_CONFIRMATION: '待确认' }

function staffName(id) {
  if (!id) return '未指定'
  return props.staffMembers.find(item => String(item.id) === String(id))?.displayName || '用户 #' + id
}
function formatDate(value) {
  if (!value) return '未设置'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
function isOverdue(ticket) {
  return ticket.dueAt && new Date(ticket.dueAt) < new Date()
}
function flash(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 3200)
}
function queryString() {
  const params = new URLSearchParams()
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  if (enabledFilter.value !== '') params.set('enabled', enabledFilter.value)
  return params.toString()
}

async function loadGroups({ keepSelection = true } = {}) {
  loading.value = true
  error.value = ''
  const selectedId = keepSelection ? selectedGroup.value?.id : null
  try {
    groups.value = await props.api('/api/v1/support-groups?' + queryString()) || []
    if (selectedId) {
      const exists = groups.value.find(item => item.group.id === selectedId)
      if (exists) await openGroup(exists)
      else selected.value = null
    }
  } catch (exception) {
    error.value = exception.message || '支持组队列加载失败'
  } finally {
    loading.value = false
  }
}

async function loadTicketCandidates() {
  try {
    const page = await props.api('/api/v1/tickets?page=1&size=100&sort=UPDATED_DESC')
    ticketCandidates.value = (page?.records || []).filter(ticket =>
      ['OPEN', 'IN_PROGRESS', 'WAITING_CONFIRMATION'].includes(ticket.status))
  } catch (exception) {
    error.value = exception.message || '待路由工单加载失败'
  }
}

async function openGroup(item) {
  detailLoading.value = true
  error.value = ''
  routeTicketId.value = ''
  try {
    selected.value = await props.api('/api/v1/support-groups/' + item.group.id)
  } catch (exception) {
    error.value = exception.message || '支持组详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

function openGroupEditor(group = null) {
  editingGroup.value = group
  Object.assign(groupForm, group ? {
    groupCode: group.groupCode,
    name: group.name,
    description: group.description || '',
    managerId: group.managerId || ''
  } : { groupCode: '', name: '', description: '', managerId: '' })
  showGroupModal.value = true
}

async function saveGroup() {
  busy.value = true
  error.value = ''
  try {
    const payload = JSON.stringify({
      groupCode: groupForm.groupCode,
      name: groupForm.name,
      description: groupForm.description || null,
      managerId: groupForm.managerId ? Number(groupForm.managerId) : null
    })
    const saved = editingGroup.value
      ? await props.api('/api/v1/support-groups/' + editingGroup.value.id, { method: 'PUT', body: payload })
      : await props.api('/api/v1/support-groups', { method: 'POST', body: payload })
    showGroupModal.value = false
    await loadGroups({ keepSelection: false })
    const item = groups.value.find(current => current.group.id === saved.id)
    if (item) await openGroup(item)
    flash(editingGroup.value ? '支持组资料已更新' : '支持组已创建，可以开始添加成员和路由工单')
  } catch (exception) {
    error.value = exception.message || '保存支持组失败'
  } finally {
    busy.value = false
  }
}

async function toggleGroup() {
  const group = selectedGroup.value
  if (!group) return
  const next = !group.enabled
  if (!window.confirm(next ? '确认重新启用该支持组吗？' : '停用后将不能接收新工单，已有队列仍会保留。确认停用吗？')) return
  await run('/api/v1/support-groups/' + group.id + '/toggle', {
    method: 'POST', body: JSON.stringify({ enabled: next })
  }, next ? '支持组已启用' : '支持组已停用')
}

async function addMember() {
  if (!memberUserId.value || !selectedGroup.value) return
  await run('/api/v1/support-groups/' + selectedGroup.value.id + '/members', {
    method: 'POST', body: JSON.stringify({ userId: Number(memberUserId.value), memberRole: memberRole.value })
  }, '成员已加入支持组')
  memberUserId.value = ''
  memberRole.value = 'MEMBER'
}

async function removeMember(member) {
  if (!window.confirm('确认将“' + staffName(member.membership.userId) + '”移出当前支持组吗？')) return
  await run('/api/v1/support-groups/' + selectedGroup.value.id + '/members/' + member.membership.userId,
    { method: 'DELETE' }, '成员已移出支持组')
}

async function routeTicket() {
  if (!routeTicketId.value || !selectedGroup.value) return
  await run('/api/v1/support-groups/' + selectedGroup.value.id + '/tickets/' + routeTicketId.value,
    { method: 'POST' }, '工单已进入团队队列，组内成员已收到通知')
  routeTicketId.value = ''
  await loadTicketCandidates()
}

async function unrouteTicket(ticket) {
  if (!window.confirm('确认将工单 ' + ticket.ticketNo + ' 移出当前支持组队列吗？')) return
  await run('/api/v1/support-groups/' + selectedGroup.value.id + '/tickets/' + ticket.id,
    { method: 'DELETE' }, '工单已移出支持组队列')
  await loadTicketCandidates()
}

async function run(path, options, message) {
  busy.value = true
  error.value = ''
  try {
    await props.api(path, options)
    await loadGroups()
    flash(message)
  } catch (exception) {
    error.value = exception.message || '操作失败'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadGroups({ keepSelection: false }), loadTicketCandidates()])
})
</script>

<template>
  <section class="sg-view">
    <header class="sg-hero">
      <div>
        <p>TEAM ROUTING &amp; OWNERSHIP</p>
        <h2>支持组与团队队列</h2>
        <span>让工单先进入能力团队，再按成员负载领取或分派，避免请求散落在个人名下。</span>
      </div>
      <button v-if="isAdmin" class="sg-primary" @click="openGroupEditor()">＋ 新建支持组</button>
    </header>

    <div v-if="error" class="sg-alert error">{{ error }}<button @click="error = ''">×</button></div>
    <div v-if="notice" class="sg-alert success">{{ notice }}</div>

    <div class="sg-stats">
      <article><span>运行中的支持组</span><strong>{{ activeGroups }}</strong><small>共 {{ groups.length }} 个团队</small></article>
      <article><span>团队队列工单</span><strong>{{ activeTickets }}</strong><small>待处理、处理中与待确认</small></article>
      <article :class="{ attention: unassignedTickets }"><span>组内待领取</span><strong>{{ unassignedTickets }}</strong><small>已路由但尚未明确负责人</small></article>
      <article :class="{ danger: overdueTickets }"><span>SLA 已逾期</span><strong>{{ overdueTickets }}</strong><small>需要立即升级或调配人力</small></article>
    </div>

    <div class="sg-toolbar">
      <input v-model="keyword" placeholder="搜索支持组名称、编码或说明…" @keyup.enter="loadGroups({ keepSelection: false })" />
      <select v-model="enabledFilter" @change="loadGroups({ keepSelection: false })">
        <option value="">全部状态</option><option value="true">仅运行中</option><option value="false">仅已停用</option>
      </select>
      <button @click="loadGroups({ keepSelection: false })">查询</button>
    </div>

    <div class="sg-layout">
      <section class="sg-panel sg-groups">
        <div class="sg-panel-head"><div><p>SUPPORT GROUPS</p><h3>团队目录</h3></div><span>{{ groups.length }} 个</span></div>
        <div v-if="loading" class="sg-empty">正在加载支持组…</div>
        <div v-else-if="!groups.length" class="sg-empty">暂无支持组。{{ isAdmin ? '创建团队后即可建立独立工单队列。' : '' }}</div>
        <button v-for="item in groups" v-else :key="item.group.id"
          :class="['sg-group-card', { selected: selectedGroup?.id === item.group.id, disabled: !item.group.enabled }]"
          @click="openGroup(item)">
          <div class="sg-group-top"><code>{{ item.group.groupCode }}</code><span>{{ item.group.enabled ? '运行中' : '已停用' }}</span></div>
          <strong>{{ item.group.name }}</strong>
          <p>{{ item.group.description || '暂无团队职责说明' }}</p>
          <div class="sg-group-metrics">
            <span><b>{{ item.activeTicketCount }}</b>活跃</span>
            <span :class="{ warn: item.unassignedTicketCount }"><b>{{ item.unassignedTicketCount }}</b>待领取</span>
            <span :class="{ danger: item.overdueTicketCount }"><b>{{ item.overdueTicketCount }}</b>逾期</span>
          </div>
          <small>{{ item.memberCount }} 名成员 · 负责人 {{ staffName(item.group.managerId) }}</small>
        </button>
      </section>

      <section class="sg-panel sg-detail">
        <div v-if="detailLoading" class="sg-empty large">正在读取团队队列…</div>
        <div v-else-if="!selected" class="sg-empty large"><b>选择一个支持组</b><span>查看成员负载、未领取工单与 SLA 风险。</span></div>
        <template v-else>
          <header class="sg-detail-head">
            <div><p>{{ selectedGroup.groupCode }} · {{ selectedGroup.enabled ? 'ACTIVE' : 'PAUSED' }}</p><h2>{{ selectedGroup.name }}</h2><span>{{ selectedGroup.description || '暂无职责说明' }}</span></div>
            <div v-if="isAdmin" class="sg-head-actions"><button @click="openGroupEditor(selectedGroup)">编辑资料</button><button :class="{ danger: selectedGroup.enabled }" @click="toggleGroup">{{ selectedGroup.enabled ? '停用' : '启用' }}</button></div>
          </header>

          <div class="sg-detail-stats">
            <article><span>活跃工单</span><strong>{{ selectedSummary.activeTicketCount }}</strong></article>
            <article><span>待领取</span><strong>{{ selectedSummary.unassignedTicketCount }}</strong></article>
            <article><span>高优先级</span><strong>{{ selectedSummary.criticalTicketCount }}</strong></article>
            <article :class="{ danger: selectedSummary.overdueTicketCount }"><span>SLA 逾期</span><strong>{{ selectedSummary.overdueTicketCount }}</strong></article>
          </div>

          <section class="sg-section">
            <div class="sg-section-head"><div><p>MEMBER CAPACITY</p><h3>成员与当前负载</h3></div><span>负责人：{{ staffName(selectedGroup.managerId) }}</span></div>
            <div v-if="isAdmin" class="sg-add-member">
              <select v-model="memberUserId"><option value="">选择待加入成员</option><option v-for="staff in availableStaff" :key="staff.id" :value="staff.id">{{ staff.displayName }} · {{ staff.roles.join('/') }}</option></select>
              <select v-model="memberRole"><option value="MEMBER">普通成员</option><option value="LEAD">组长</option></select>
              <button :disabled="busy || !memberUserId" @click="addMember">添加成员</button>
            </div>
            <div v-if="!selected.members.length" class="sg-empty compact">当前支持组还没有成员。</div>
            <div v-else class="sg-member-grid">
              <article v-for="member in selected.members" :key="member.membership.id">
                <div class="sg-avatar">{{ (member.staff?.displayName || '?').slice(0, 1) }}</div>
                <div><strong>{{ member.staff?.displayName || ('用户 #' + member.membership.userId) }}</strong><small>{{ member.membership.memberRole === 'LEAD' ? '组长' : '成员' }} · {{ member.activeAssignments }} 张进行中</small></div>
                <span :class="{ loaded: member.activeAssignments >= 5 }">{{ member.activeAssignments }}</span>
                <button v-if="isAdmin && member.membership.userId !== selectedGroup.managerId" @click="removeMember(member)">移除</button>
              </article>
            </div>
          </section>

          <section class="sg-section sg-queue-section">
            <div class="sg-section-head"><div><p>TEAM QUEUE</p><h3>当前工单队列</h3></div><span>{{ selected.tickets.length }} 张活跃工单</span></div>
            <div class="sg-route-bar">
              <select v-model="routeTicketId" :disabled="!selectedGroup.enabled">
                <option value="">选择要路由到该组的工单</option>
                <option v-for="ticket in routeCandidates" :key="ticket.id" :value="ticket.id">
                  {{ ticket.ticketNo }} · {{ ticket.title }}{{ ticket.assignmentGroupId ? ' · 当前在 ' + (groupNames.get(String(ticket.assignmentGroupId)) || '其他组') : ' · 未分组' }}
                </option>
              </select>
              <button :disabled="busy || !routeTicketId || !selectedGroup.enabled" @click="routeTicket">路由至当前组</button>
            </div>
            <div v-if="!selected.tickets.length" class="sg-empty compact">队列为空。可从上方选择工单路由到当前支持组。</div>
            <div v-else class="sg-ticket-list">
              <article v-for="ticket in selected.tickets" :key="ticket.id" :class="{ overdue: isOverdue(ticket) }">
                <div class="sg-ticket-priority" :class="String(ticket.priority).toLowerCase()"></div>
                <div class="sg-ticket-main">
                  <div><code>{{ ticket.ticketNo }}</code><span>{{ priorityLabels[ticket.priority] }} · {{ statusLabels[ticket.status] }}</span><em v-if="isOverdue(ticket)">SLA 逾期</em></div>
                  <strong>{{ ticket.title }}</strong>
                  <small>{{ ticket.category || '未分类' }} · {{ ticket.assigneeId ? staffName(ticket.assigneeId) : '组内待领取' }} · 截止 {{ formatDate(ticket.dueAt) }}</small>
                </div>
                <button :disabled="busy" @click="unrouteTicket(ticket)">移出队列</button>
              </article>
            </div>
          </section>
        </template>
      </section>
    </div>

    <div v-if="showGroupModal" class="sg-modal-backdrop" @click.self="showGroupModal = false">
      <form class="sg-modal" @submit.prevent="saveGroup">
        <header><div><p>SUPPORT GROUP PROFILE</p><h2>{{ editingGroup ? '编辑支持组' : '创建支持组' }}</h2></div><button type="button" @click="showGroupModal = false">×</button></header>
        <label>支持组编码<input v-model="groupForm.groupCode" maxlength="32" placeholder="例如：APP_SUPPORT" required /><small>用于接口与规则识别，保存后仍可调整。</small></label>
        <label>支持组名称<input v-model="groupForm.name" maxlength="100" placeholder="例如：应用支持组" required /></label>
        <label>团队负责人<select v-model="groupForm.managerId"><option value="">暂不指定</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }} · {{ staff.roles.join('/') }}</option></select></label>
        <label>职责说明<textarea v-model="groupForm.description" maxlength="1000" rows="4" placeholder="说明负责的业务范围、服务边界与升级条件"></textarea></label>
        <footer><button type="button" @click="showGroupModal = false">取消</button><button class="sg-primary" :disabled="busy" type="submit">{{ busy ? '保存中…' : '保存支持组' }}</button></footer>
      </form>
    </div>
  </section>
</template>

<style scoped>
.sg-view { display: grid; gap: 18px; color: #172033; }
.sg-hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 24px; padding: 28px 30px; border-radius: 22px; color: white; background: radial-gradient(circle at 82% 20%, rgba(44, 196, 170, .28), transparent 27%), linear-gradient(125deg, #101d38, #173653 63%, #126b70); box-shadow: 0 18px 45px rgba(20, 47, 77, .18); }
.sg-hero p, .sg-panel-head p, .sg-detail-head p, .sg-section-head p, .sg-modal p { margin: 0 0 6px; color: #31d7b2; font: 800 11px/1.2 ui-monospace, monospace; letter-spacing: .14em; }
.sg-hero h2 { margin: 0 0 8px; font-size: 28px; }
.sg-hero span { color: #c9d8e8; font-size: 14px; }
.sg-primary, .sg-toolbar button, .sg-route-bar button, .sg-add-member button { border: 0; border-radius: 10px; padding: 11px 16px; background: #16a387; color: white; font-weight: 800; cursor: pointer; }
.sg-primary:hover, .sg-toolbar button:hover, .sg-route-bar button:hover, .sg-add-member button:hover { background: #10836d; }
.sg-primary:disabled, .sg-route-bar button:disabled, .sg-add-member button:disabled { opacity: .5; cursor: not-allowed; }
.sg-alert { display: flex; justify-content: space-between; padding: 12px 16px; border-radius: 11px; font-size: 14px; }
.sg-alert.error { color: #a3293b; background: #fff0f2; border: 1px solid #ffd5dc; }
.sg-alert.success { color: #08715d; background: #eafaf6; border: 1px solid #bdeade; }
.sg-alert button { border: 0; background: transparent; color: inherit; cursor: pointer; }
.sg-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.sg-stats article { display: grid; gap: 5px; padding: 18px 20px; border: 1px solid #e2e8f0; border-radius: 15px; background: white; box-shadow: 0 8px 20px rgba(30, 52, 78, .05); }
.sg-stats span { color: #667085; font-size: 13px; }.sg-stats strong { font-size: 28px; }.sg-stats small { color: #98a2b3; }
.sg-stats .attention { border-color: #f4d28f; background: #fffbf2; }.sg-stats .danger { border-color: #f0b6bd; background: #fff6f7; }
.sg-toolbar { display: flex; gap: 10px; }.sg-toolbar input { flex: 1; }.sg-toolbar input, .sg-toolbar select, .sg-add-member select, .sg-route-bar select, .sg-modal input, .sg-modal select, .sg-modal textarea { border: 1px solid #d7e0ea; border-radius: 10px; padding: 11px 12px; color: #243247; background: white; outline: none; }
.sg-toolbar input:focus, .sg-modal input:focus, .sg-modal textarea:focus, .sg-route-bar select:focus { border-color: #16a387; box-shadow: 0 0 0 3px rgba(22, 163, 135, .1); }
.sg-layout { display: grid; grid-template-columns: 330px minmax(0, 1fr); gap: 16px; align-items: start; }
.sg-panel { border: 1px solid #e1e8f0; border-radius: 18px; background: #fff; box-shadow: 0 10px 28px rgba(28, 52, 77, .06); overflow: hidden; }
.sg-panel-head, .sg-section-head { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid #edf1f5; }
.sg-panel-head h3, .sg-section-head h3 { margin: 0; font-size: 17px; }.sg-panel-head > span, .sg-section-head > span { color: #7a8797; font-size: 12px; }
.sg-groups { position: sticky; top: 88px; max-height: calc(100vh - 112px); overflow-y: auto; }
.sg-group-card { display: grid; width: calc(100% - 20px); margin: 10px; padding: 15px; text-align: left; border: 1px solid #e7ecf2; border-radius: 13px; background: white; cursor: pointer; transition: .18s ease; }
.sg-group-card:hover { transform: translateY(-1px); border-color: #9bd8ca; }.sg-group-card.selected { border-color: #16a387; box-shadow: 0 0 0 3px rgba(22, 163, 135, .1); }.sg-group-card.disabled { opacity: .68; }
.sg-group-top { display: flex; justify-content: space-between; align-items: center; }.sg-group-top code { color: #0f8a73; font-weight: 800; }.sg-group-top span { padding: 3px 7px; border-radius: 99px; color: #19715f; background: #e7f8f3; font-size: 10px; }.sg-group-card.disabled .sg-group-top span { color: #697586; background: #eef1f4; }
.sg-group-card > strong { margin-top: 9px; font-size: 16px; }.sg-group-card > p { min-height: 34px; margin: 5px 0 10px; color: #667085; font-size: 12px; line-height: 1.45; }.sg-group-card > small { margin-top: 10px; color: #8a96a5; }
.sg-group-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }.sg-group-metrics span { padding: 7px; border-radius: 8px; color: #718096; background: #f6f8fa; font-size: 10px; }.sg-group-metrics b { display: block; color: #26364a; font-size: 16px; }.sg-group-metrics .warn b { color: #b27007; }.sg-group-metrics .danger b { color: #bf3045; }
.sg-detail { min-height: 560px; }.sg-detail-head { display: flex; justify-content: space-between; gap: 20px; padding: 23px 24px; border-bottom: 1px solid #eaf0f4; }.sg-detail-head h2 { margin: 0 0 7px; }.sg-detail-head > div > span { color: #667085; font-size: 13px; }.sg-head-actions { display: flex; gap: 8px; align-items: flex-start; }.sg-head-actions button, .sg-ticket-list button { border: 1px solid #d6dfe8; border-radius: 8px; padding: 8px 11px; color: #344054; background: white; cursor: pointer; }.sg-head-actions button.danger, .sg-ticket-list article.overdue button { color: #b4233a; border-color: #f0c3ca; }
.sg-detail-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; background: #edf1f5; border-bottom: 1px solid #edf1f5; }.sg-detail-stats article { padding: 16px 20px; background: #f9fbfc; }.sg-detail-stats span { display: block; color: #748094; font-size: 11px; }.sg-detail-stats strong { font-size: 22px; }.sg-detail-stats .danger strong { color: #bd2f44; }
.sg-section { padding: 20px 22px; border-bottom: 1px solid #edf1f5; }.sg-section .sg-section-head { padding: 0 0 14px; border: 0; }
.sg-add-member, .sg-route-bar { display: flex; gap: 8px; padding: 10px; border-radius: 12px; background: #f5f8fa; }.sg-add-member select:first-child, .sg-route-bar select { flex: 1; min-width: 0; }
.sg-member-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }.sg-member-grid article { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto auto; gap: 10px; align-items: center; padding: 11px; border: 1px solid #e7edf2; border-radius: 11px; }.sg-avatar { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 10px; color: #08725e; background: #dff5ef; font-weight: 900; }.sg-member-grid strong, .sg-member-grid small { display: block; }.sg-member-grid small { margin-top: 3px; color: #7c8998; }.sg-member-grid > article > span { min-width: 28px; padding: 5px 8px; border-radius: 8px; color: #137964; background: #e8f7f3; text-align: center; font-weight: 900; }.sg-member-grid > article > span.loaded { color: #a76000; background: #fff1d8; }.sg-member-grid button { border: 0; color: #a33b4b; background: transparent; cursor: pointer; }
.sg-route-bar { margin-bottom: 12px; }.sg-ticket-list { display: grid; gap: 8px; }.sg-ticket-list article { display: grid; grid-template-columns: 4px minmax(0, 1fr) auto; gap: 13px; align-items: center; padding: 13px; border: 1px solid #e7edf2; border-radius: 11px; }.sg-ticket-list article.overdue { border-color: #efc2c8; background: #fff9f9; }.sg-ticket-priority { align-self: stretch; border-radius: 5px; background: #84a7c6; }.sg-ticket-priority.high { background: #f4a440; }.sg-ticket-priority.urgent { background: #d83c54; }.sg-ticket-priority.low { background: #5cbea7; }.sg-ticket-main > div { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }.sg-ticket-main code { color: #187b68; font-weight: 800; }.sg-ticket-main span { color: #667085; font-size: 11px; }.sg-ticket-main em { color: #b4233a; font-size: 11px; font-style: normal; font-weight: 800; }.sg-ticket-main strong { display: block; margin: 5px 0; }.sg-ticket-main small { color: #7d8998; }
.sg-empty { padding: 34px 20px; color: #8a96a5; text-align: center; }.sg-empty.large { display: grid; place-items: center; align-content: center; min-height: 480px; gap: 8px; }.sg-empty.large b { color: #344054; font-size: 18px; }.sg-empty.compact { padding: 22px 10px; }
.sg-modal-backdrop { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; padding: 24px; background: rgba(13, 25, 43, .56); backdrop-filter: blur(3px); }.sg-modal { display: grid; gap: 15px; width: min(520px, 100%); max-height: 90vh; overflow-y: auto; padding: 24px; border-radius: 18px; background: white; box-shadow: 0 28px 80px rgba(4, 17, 34, .35); }.sg-modal header, .sg-modal footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.sg-modal h2 { margin: 0; }.sg-modal header > button { border: 0; background: transparent; font-size: 24px; cursor: pointer; }.sg-modal label { display: grid; gap: 7px; color: #344054; font-size: 13px; font-weight: 800; }.sg-modal label small { color: #8a96a5; font-weight: 400; }.sg-modal textarea { resize: vertical; }.sg-modal footer { justify-content: flex-end; padding-top: 8px; }.sg-modal footer > button:not(.sg-primary) { border: 1px solid #d7dfe8; border-radius: 10px; padding: 10px 16px; background: white; cursor: pointer; }
@media (max-width: 1100px) { .sg-layout { grid-template-columns: 1fr; }.sg-groups { position: static; max-height: none; }.sg-stats { grid-template-columns: repeat(2, 1fr); }.sg-member-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .sg-hero, .sg-detail-head { align-items: stretch; flex-direction: column; }.sg-stats, .sg-detail-stats { grid-template-columns: repeat(2, 1fr); }.sg-toolbar, .sg-add-member, .sg-route-bar { flex-direction: column; }.sg-ticket-list article { grid-template-columns: 4px minmax(0, 1fr); }.sg-ticket-list button { grid-column: 2; justify-self: start; } }
</style>
