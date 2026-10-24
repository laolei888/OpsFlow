<script setup>
import { computed, onMounted, ref, watch } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  users: { type: Array, default: () => [] }
})

const providers = ref([])
const permissions = ref([])
const selectedUserId = ref('')
const permissionView = ref(null)
const permissionRows = ref([])
const loading = ref(false)
const saving = ref(false)
const notice = ref('')
const error = ref('')
const showProviderEditor = ref(false)
const editingProviderId = ref(null)
const providerForm = ref(emptyProvider())

const selectedUser = computed(() => props.users.find(item => String(item.id) === String(selectedUserId.value)))
const effectiveCount = computed(() => permissionView.value?.effectiveCodes?.length || 0)

watch(() => props.users, users => {
  if (!selectedUserId.value && users.length) selectedUserId.value = String(users[0].id)
}, { deep: true })

watch(selectedUserId, value => {
  if (value) loadUserPermissions()
})

onMounted(async () => {
  if (props.users.length) selectedUserId.value = String(props.users[0].id)
  await load()
})

function emptyProvider() {
  return {
    registrationId: '', displayName: '', enabled: true, autoProvision: true,
    defaultRole: 'REQUESTER', allowedEmailDomains: '', requiredGroup: '',
    usernameClaim: 'preferred_username', displayNameClaim: 'name',
    emailClaim: 'email', groupsClaim: 'groups'
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [nextProviders, nextPermissions] = await Promise.all([
      props.api('/api/v1/admin/identity/providers'),
      props.api('/api/v1/admin/permissions')
    ])
    providers.value = nextProviders || []
    permissions.value = nextPermissions || []
    if (selectedUserId.value) await loadUserPermissions()
  } catch (exception) {
    error.value = exception.message || '企业身份与权限数据加载失败'
  } finally {
    loading.value = false
  }
}

async function loadUserPermissions() {
  if (!selectedUserId.value) return
  try {
    permissionView.value = await props.api('/api/v1/admin/users/' + selectedUserId.value + '/permissions')
    const overrides = new Map((permissionView.value?.overrides || []).map(item => [item.code, item]))
    permissionRows.value = permissions.value.map(permission => ({
      ...permission,
      inherited: permissionView.value?.inheritedCodes?.includes(permission.code),
      state: overrides.get(permission.code)?.effect || 'INHERIT',
      reason: overrides.get(permission.code)?.reason || ''
    }))
  } catch (exception) {
    error.value = exception.message || '用户权限加载失败'
  }
}

async function savePermissions() {
  if (!selectedUserId.value) return
  saving.value = true
  error.value = ''
  try {
    const overrides = permissionRows.value
      .filter(item => item.state !== 'INHERIT')
      .map(item => ({ code: item.code, effect: item.state, reason: item.reason?.trim() || null }))
    permissionView.value = await props.api('/api/v1/admin/users/' + selectedUserId.value + '/permissions', {
      method: 'PUT', body: JSON.stringify({ overrides })
    })
    notice.value = '权限覆盖已保存，目标账号的旧令牌已失效'
    window.setTimeout(() => { notice.value = '' }, 4000)
  } catch (exception) {
    error.value = exception.message || '权限策略保存失败'
  } finally {
    saving.value = false
  }
}

function openProvider(provider = null) {
  editingProviderId.value = provider?.id || null
  providerForm.value = provider ? {
    registrationId: provider.registrationId,
    displayName: provider.displayName,
    enabled: provider.enabled,
    autoProvision: provider.autoProvision,
    defaultRole: provider.defaultRole,
    allowedEmailDomains: provider.allowedEmailDomains || '',
    requiredGroup: provider.requiredGroup || '',
    usernameClaim: provider.usernameClaim,
    displayNameClaim: provider.displayNameClaim,
    emailClaim: provider.emailClaim,
    groupsClaim: provider.groupsClaim
  } : emptyProvider()
  showProviderEditor.value = true
}

async function saveProvider() {
  saving.value = true
  error.value = ''
  try {
    const path = '/api/v1/admin/identity/providers' + (editingProviderId.value ? '/' + editingProviderId.value : '')
    await props.api(path, {
      method: editingProviderId.value ? 'PUT' : 'POST',
      body: JSON.stringify(providerForm.value)
    })
    showProviderEditor.value = false
    notice.value = editingProviderId.value ? '身份提供商映射已更新' : '身份提供商映射已创建'
    await load()
  } catch (exception) {
    error.value = exception.message || '身份提供商保存失败'
  } finally {
    saving.value = false
  }
}

async function toggleProvider(provider) {
  error.value = ''
  try {
    await props.api('/api/v1/admin/identity/providers/' + provider.id + '/enabled', {
      method: 'PATCH', body: JSON.stringify({ enabled: !provider.enabled })
    })
    await load()
  } catch (exception) {
    error.value = exception.message || '身份提供商状态更新失败'
  }
}

function inheritedLabel(row) {
  return row.inherited ? '角色默认允许' : '角色默认不允许'
}

function effectiveLabel(row) {
  if (row.state === 'ALLOW') return '最终允许'
  if (row.state === 'DENY') return '最终拒绝'
  return row.inherited ? '最终允许' : '最终拒绝'
}
</script>

<template>
  <Teleport to=".content-area">
  <section class="identity-center">
    <header class="identity-heading">
      <div><p>ENTERPRISE IDENTITY &amp; ACCESS</p><h2>企业身份与细粒度权限</h2><span>OIDC 负责可信认证，OpsFlow 继续负责租户边界、角色与业务授权。</span></div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="banner error">{{ error }}</p>
    <p v-if="notice" class="banner notice">{{ notice }}</p>
    <div v-if="loading" class="loading">正在同步身份与授权策略…</div>
    <template v-else>
      <div class="identity-grid">
        <article class="identity-card provider-card">
          <div class="card-heading"><div><small>OIDC PROVIDERS</small><h3>企业单点登录</h3></div><button type="button" @click="openProvider()">＋ 新增映射</button></div>
          <p class="description">Client ID 与密钥通过部署环境注入；这里仅维护租户映射、准入条件和自动开户策略。</p>
          <div v-if="!providers.length" class="empty">尚未配置身份提供商。账号密码登录不会受影响。</div>
          <div v-for="provider in providers" :key="provider.id" class="provider-row">
            <div class="provider-mark">{{ provider.displayName.slice(0, 1) }}</div>
            <div class="provider-main">
              <strong>{{ provider.displayName }}</strong>
              <code>{{ provider.registrationId }}</code>
              <span>{{ provider.autoProvision ? '允许自动开户' : '仅允许已绑定账号' }} · 默认 {{ provider.defaultRole }}</span>
              <small v-if="provider.issuer">{{ provider.issuer }}</small>
            </div>
            <div class="provider-state">
              <b :class="provider.clientConfigured ? 'ready' : 'warning'">{{ provider.clientConfigured ? '凭据已就绪' : '待注入凭据' }}</b>
              <i :class="provider.enabled ? 'enabled' : 'disabled'">{{ provider.enabled ? '已启用' : '已停用' }}</i>
            </div>
            <div class="row-actions"><button @click="openProvider(provider)">编辑</button><button @click="toggleProvider(provider)">{{ provider.enabled ? '停用' : '启用' }}</button></div>
          </div>
        </article>

        <article class="identity-card permission-card">
          <div class="card-heading"><div><small>POLICY OVERRIDES</small><h3>用户级权限覆盖</h3></div><span>{{ effectiveCount }} 项最终允许</span></div>
          <div class="user-picker">
            <label>选择成员<select v-model="selectedUserId"><option v-for="item in users" :key="item.id" :value="String(item.id)">{{ item.displayName }} · {{ item.roles.join('/') }}</option></select></label>
            <div v-if="selectedUser"><strong>{{ selectedUser.displayName }}</strong><small>{{ selectedUser.authSource === 'OIDC' ? '企业身份账号' : '本地账号' }} · {{ selectedUser.email || selectedUser.username }}</small></div>
          </div>
          <p class="description">“继承”使用角色默认值；显式拒绝优先于角色授权，保存后会使目标账号旧令牌失效。</p>
          <div class="permission-head"><span>权限</span><span>角色基线</span><span>用户覆盖</span><span>最终结果</span></div>
          <div v-for="row in permissionRows" :key="row.code" class="permission-row">
            <div><strong>{{ row.code }}</strong><small>{{ row.description }}</small></div>
            <span :class="row.inherited ? 'allowed' : 'muted'">{{ inheritedLabel(row) }}</span>
            <select v-model="row.state"><option value="INHERIT">继承</option><option value="ALLOW">显式允许</option><option value="DENY">显式拒绝</option></select>
            <b :class="effectiveLabel(row) === '最终允许' ? 'allowed' : 'denied'">{{ effectiveLabel(row) }}</b>
            <input v-if="row.state !== 'INHERIT'" v-model="row.reason" maxlength="300" placeholder="覆盖原因（可选）" />
          </div>
          <footer><span>拒绝策略会优先覆盖角色权限。</span><button type="button" :disabled="saving || !selectedUserId" @click="savePermissions">{{ saving ? '保存中…' : '保存权限策略' }}</button></footer>
        </article>
      </div>
    </template>

    <div v-if="showProviderEditor" class="modal-backdrop" @click.self="showProviderEditor = false">
      <form class="provider-editor" @submit.prevent="saveProvider">
        <header><div><p>OIDC TENANT MAPPING</p><h2>{{ editingProviderId ? '编辑身份提供商' : '新增身份提供商' }}</h2><span>服务端 OAuth2 Client 配置中的 registrationId 必须与此处一致。</span></div><button type="button" @click="showProviderEditor = false">×</button></header>
        <div class="form-grid">
          <label>Registration ID<input v-model="providerForm.registrationId" required maxlength="64" placeholder="例如 corp-oidc" /></label>
          <label>显示名称<input v-model="providerForm.displayName" required maxlength="120" placeholder="例如 公司统一身份" /></label>
          <label>默认角色<select v-model="providerForm.defaultRole"><option value="REQUESTER">提单人</option><option value="AGENT">客服 / 处理人</option><option value="ADMIN">管理员</option></select></label>
          <label>邮箱域名白名单<input v-model="providerForm.allowedEmailDomains" maxlength="1000" placeholder="example.com,corp.example.com" /></label>
          <label>必须属于用户组<input v-model="providerForm.requiredGroup" maxlength="191" placeholder="留空不限制" /></label>
          <label class="checks"><span><input v-model="providerForm.enabled" type="checkbox" /> 启用登录入口</span><span><input v-model="providerForm.autoProvision" type="checkbox" /> 首次登录自动开户</span></label>
        </div>
        <details><summary>高级 Claim 映射</summary><div class="claim-grid"><label>用户名 Claim<input v-model="providerForm.usernameClaim" /></label><label>显示名 Claim<input v-model="providerForm.displayNameClaim" /></label><label>邮箱 Claim<input v-model="providerForm.emailClaim" /></label><label>用户组 Claim<input v-model="providerForm.groupsClaim" /></label></div></details>
        <p class="security-note">OpsFlow 不在数据库中保存 OIDC Client Secret。请通过 spring.security.oauth2.client 配置或环境变量注入凭据。</p>
        <footer><button type="button" class="cancel" @click="showProviderEditor = false">取消</button><button :disabled="saving">{{ saving ? '保存中…' : '保存映射' }}</button></footer>
      </form>
    </div>
  </section>
  </Teleport>
</template>

<style scoped>
.identity-center{margin-top:18px}.identity-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding:20px 22px;border-radius:15px;background:linear-gradient(135deg,#17283d,#203d56);color:#fff;box-shadow:0 16px 34px rgba(18,39,61,.14)}.identity-heading p,.provider-editor header p{margin:0 0 5px;color:#89b8ff;font-size:8px;font-weight:900;letter-spacing:1.4px}.identity-heading h2{margin:0 0 6px;font-size:18px}.identity-heading span{color:#b5c7d8;font-size:10px}.identity-heading button,.card-heading button,.identity-card footer button,.provider-editor footer button{padding:9px 13px;border:0;border-radius:8px;background:#6377dc;color:#fff;font-size:9px;font-weight:900;cursor:pointer}.identity-grid{display:grid;grid-template-columns:.92fr 1.45fr;gap:16px;margin-top:16px}.identity-card{padding:19px;border:1px solid #e1e7ed;border-radius:14px;background:#fff;box-shadow:0 10px 28px rgba(35,53,72,.06)}.card-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.card-heading small{color:#6f80cf;font-size:8px;font-weight:900;letter-spacing:1.1px}.card-heading h3{margin:4px 0 0;color:#293b50;font-size:15px}.card-heading>span{color:#718095;font-size:9px}.description{margin:12px 0;color:#8290a1;font-size:9px;line-height:1.65}.empty,.loading{padding:35px;color:#8996a6;text-align:center;font-size:10px}.provider-row{display:grid;grid-template-columns:34px minmax(0,1fr) auto;gap:10px;align-items:center;padding:13px 0;border-top:1px solid #edf1f4}.provider-mark{display:grid;width:34px;height:34px;place-items:center;border-radius:10px;background:#e8edff;color:#5369c7;font-size:13px;font-weight:900}.provider-main{min-width:0}.provider-main strong,.provider-main code,.provider-main span,.provider-main small{display:block}.provider-main strong{color:#33465d;font-size:11px}.provider-main code{margin:3px 0;color:#61718a;font-size:9px}.provider-main span,.provider-main small{overflow:hidden;color:#94a0ae;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.provider-state{display:grid;justify-items:end;gap:5px}.provider-state b,.provider-state i{padding:4px 6px;border-radius:5px;font-size:7px;font-style:normal}.provider-state .ready{background:#e7f8ee;color:#22744b}.provider-state .warning{background:#fff3dc;color:#936416}.provider-state .enabled{color:#4870c2}.provider-state .disabled{color:#a55a66}.row-actions{grid-column:2/-1;display:flex;gap:9px}.row-actions button{padding:0;border:0;background:transparent;color:#6678cb;font-size:8px;cursor:pointer}.user-picker{display:flex;align-items:end;justify-content:space-between;gap:15px;margin-top:14px;padding:12px;border-radius:10px;background:#f7f9fc}.user-picker label{display:grid;gap:5px;color:#65758b;font-size:8px;font-weight:800}.user-picker select,.permission-row select,.permission-row input,.form-grid input,.form-grid select,.claim-grid input{padding:8px;border:1px solid #d8e0e8;border-radius:7px;background:#fff;color:#3e5067;font:inherit}.user-picker div{text-align:right}.user-picker strong,.user-picker small{display:block}.user-picker strong{color:#3c4e64;font-size:10px}.user-picker small{margin-top:4px;color:#8e9aa9;font-size:8px}.permission-head,.permission-row{display:grid;grid-template-columns:minmax(190px,1.5fr) 100px 105px 80px;gap:9px;align-items:center}.permission-head{padding:8px;color:#909baa;font-size:7px;font-weight:900;text-transform:uppercase}.permission-row{padding:10px 8px;border-top:1px solid #edf1f4}.permission-row>div strong,.permission-row>div small{display:block}.permission-row>div strong{color:#405169;font-size:9px}.permission-row>div small{margin-top:3px;color:#94a0ae;font-size:8px}.permission-row>span,.permission-row>b{font-size:8px}.permission-row input{grid-column:2/-1}.allowed{color:#218059}.denied{color:#b04759}.muted{color:#9ca7b4}.identity-card>footer{display:flex;align-items:center;justify-content:space-between;margin-top:14px;padding-top:13px;border-top:1px solid #e9edf2}.identity-card>footer span{color:#929dab;font-size:8px}.banner{margin:12px 0 0;padding:10px 13px;border-radius:8px;font-size:9px}.banner.error{background:#fff0f2;color:#a2384c}.banner.notice{background:#eaf8ef;color:#27744d}.modal-backdrop{position:fixed;z-index:1300;inset:0;display:grid;place-items:center;padding:20px;background:rgba(13,27,42,.64);backdrop-filter:blur(4px)}.provider-editor{width:min(740px,100%);max-height:90vh;overflow:auto;padding:22px;border-radius:16px;background:#fff;box-shadow:0 30px 80px rgba(10,24,39,.3)}.provider-editor header{display:flex;justify-content:space-between}.provider-editor header h2{margin:0 0 6px;color:#283b51}.provider-editor header span{color:#8995a5;font-size:9px}.provider-editor header>button{border:0;background:transparent;color:#8290a1;font-size:23px}.form-grid,.claim-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:18px}.form-grid label,.claim-grid label{display:grid;gap:6px;color:#5d6e83;font-size:9px;font-weight:800}.checks{align-content:center}.checks span{display:flex;align-items:center;gap:7px}.checks input{width:auto}.provider-editor details{margin-top:15px;padding:12px;border:1px solid #e2e7ed;border-radius:9px}.provider-editor summary{color:#596b81;font-size:9px;font-weight:900;cursor:pointer}.claim-grid{margin-top:12px}.security-note{padding:10px;border-radius:8px;background:#fff7e8;color:#826223;font-size:8px;line-height:1.6}.provider-editor>footer{display:flex;justify-content:flex-end;gap:9px;margin-top:17px}.provider-editor footer .cancel{border:1px solid #dbe2e9;background:#fff;color:#64758a}@media(max-width:980px){.identity-grid{grid-template-columns:1fr}}@media(max-width:700px){.permission-head{display:none}.permission-row{grid-template-columns:1fr 1fr}.permission-row>div{grid-column:1/-1}.form-grid,.claim-grid{grid-template-columns:1fr}}
</style>
