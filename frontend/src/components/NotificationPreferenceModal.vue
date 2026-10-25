<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  api: { type: Function, required: true }
})
const emit = defineEmits(['close', 'saved'])

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const form = ref({ emailAddress: '', webhookUrl: '', webhookSecret: '', webhookSecretPreview: '', wecomWebhookUrl: '', wecomWebhookUrlPreview: '', clearWecomWebhook: false, preferredLocale: 'zh-CN', timeZone: 'Asia/Shanghai', deliveryWindowEnabled: false, deliveryWindowStart: '08:00', deliveryWindowEnd: '22:00', urgentBypass: true, rules: [] })

const eventOptions = [
  ['TICKET_CREATED', '工单创建'], ['TICKET_ASSIGNED', '工单指派'],
  ['TICKET_STATUS_CHANGED', '工单状态'], ['TICKET_GROUP_ROUTED', '支持组路由'],
  ['SLA_DUE_SOON', 'SLA 即将到期'], ['SLA_OVERDUE', 'SLA 已逾期'],
  ['SATISFACTION_REQUESTED', '服务评价'], ['INCIDENT_DECLARED', '重大事件宣布'],
  ['INCIDENT_STATUS_CHANGED', '重大事件状态'], ['CHANGE_APPROVAL_REQUIRED', '变更待审批'],
  ['CHANGE_STATUS_CHANGED', '变更状态'], ['MONITORING_ALERT', '监控告警'],
  ['SERVICE_APPROVAL_REQUIRED', '服务申请待审批'], ['SERVICE_REQUEST_RESULT', '服务申请结果'],
  ['PROBLEM_CREATED', '问题单创建'], ['PROBLEM_STATUS_CHANGED', '问题单状态'],
  ['POSTMORTEM_REVIEW_REQUIRED', '复盘待评审'], ['POSTMORTEM_PUBLISHED', '复盘已发布'],
  ['ONCALL_ESCALATION', '值班升级'], ['CAPACITY_THRESHOLD', '容量风险预警'],
  ['REPORT_READY', '报表生成']
]
const hasEmailRule = computed(() => form.value.rules.some(rule => rule.emailEnabled))
const hasWebhookRule = computed(() => form.value.rules.some(rule => rule.webhookEnabled))
const hasWecomRule = computed(() => form.value.rules.some(rule => rule.wecomEnabled))

watch(() => props.visible, visible => {
  if (visible) load()
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const value = await props.api('/api/v1/notification-preferences')
    form.value = {
      emailAddress: value.emailAddress || '',
      webhookUrl: value.webhookUrl || '',
      webhookSecret: '',
      webhookSecretPreview: value.webhookSecretPreview || '',
      wecomWebhookUrl: '',
      wecomWebhookUrlPreview: value.wecomWebhookUrlPreview || '',
      clearWecomWebhook: false,
      preferredLocale: value.preferredLocale || 'zh-CN',
      timeZone: value.timeZone || 'Asia/Shanghai',
      deliveryWindowEnabled: Boolean(value.deliveryWindowEnabled),
      deliveryWindowStart: String(value.deliveryWindowStart || '08:00').slice(0, 5),
      deliveryWindowEnd: String(value.deliveryWindowEnd || '22:00').slice(0, 5),
      urgentBypass: value.urgentBypass !== false,
      rules: (value.rules || []).map(rule => ({ ...rule }))
    }
  } catch (exception) {
    error.value = exception.message || '通知偏好加载失败'
  } finally {
    loading.value = false
  }
}

function addRule() {
  const used = new Set(form.value.rules.map(rule => rule.eventType))
  const next = eventOptions.find(([type]) => !used.has(type))?.[0] || ''
  form.value.rules.push({ eventType: next, inAppEnabled: true, emailEnabled: false, webhookEnabled: false, wecomEnabled: false })
}

function removeRule(index) {
  if (form.value.rules[index]?.eventType === '*') return
  form.value.rules.splice(index, 1)
}

function eventLabel(type) {
  if (type === '*') return '全部其他事件（默认规则）'
  return eventOptions.find(([value]) => value === type)?.[1] || type || '未选择事件'
}

async function save() {
  error.value = ''
  const rules = form.value.rules.map(rule => ({
    eventType: String(rule.eventType || '').trim().toUpperCase(),
    inAppEnabled: Boolean(rule.inAppEnabled),
    emailEnabled: Boolean(rule.emailEnabled),
    webhookEnabled: Boolean(rule.webhookEnabled),
    wecomEnabled: Boolean(rule.wecomEnabled)
  }))
  if (rules.some(rule => !rule.eventType)) {
    error.value = '请为每条规则选择或填写事件类型'
    return
  }
  if (new Set(rules.map(rule => rule.eventType)).size !== rules.length) {
    error.value = '同一个事件只能配置一条规则'
    return
  }
  if (hasEmailRule.value && !form.value.emailAddress.trim()) {
    error.value = '启用邮件通知前请填写邮件地址'
    return
  }
  if (hasWebhookRule.value && !form.value.webhookUrl.trim()) {
    error.value = '启用个人 Webhook 前请填写回调地址'
    return
  }
  if (hasWecomRule.value && (!form.value.wecomWebhookUrl.trim() && !form.value.wecomWebhookUrlPreview || form.value.clearWecomWebhook)) {
    error.value = '启用企业微信通知前请填写群机器人 Webhook 地址'
    return
  }
  if (form.value.deliveryWindowEnabled && form.value.deliveryWindowStart === form.value.deliveryWindowEnd) {
    error.value = '发送时间窗的开始和结束时间不能相同'
    return
  }
  saving.value = true
  try {
    await props.api('/api/v1/notification-preferences', {
      method: 'PUT',
      body: JSON.stringify({
        emailAddress: form.value.emailAddress.trim() || null,
        webhookUrl: form.value.webhookUrl.trim() || null,
        webhookSecret: form.value.webhookSecret.trim() || null,
        wecomWebhookUrl: form.value.wecomWebhookUrl.trim() || null,
        clearWecomWebhook: Boolean(form.value.clearWecomWebhook),
        preferredLocale: form.value.preferredLocale,
        timeZone: form.value.timeZone.trim(),
        deliveryWindowEnabled: Boolean(form.value.deliveryWindowEnabled),
        deliveryWindowStart: form.value.deliveryWindowStart,
        deliveryWindowEnd: form.value.deliveryWindowEnd,
        urgentBypass: Boolean(form.value.urgentBypass),
        rules
      })
    })
    emit('saved')
    emit('close')
  } catch (exception) {
    error.value = exception.message || '通知偏好保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="visible" class="preference-backdrop" @click.self="emit('close')">
    <form class="preference-card" @submit.prevent="save">
      <header>
        <div><p>NOTIFICATION ROUTING</p><h2>多渠道通知偏好</h2><span>默认只发送站内信；可针对高优先级事件开启邮件、个人 Webhook 或企业微信。</span></div>
        <button type="button" @click="emit('close')">×</button>
      </header>
      <p v-if="error" class="preference-error">{{ error }}</p>
      <div v-if="loading" class="preference-loading">正在读取通知偏好…</div>
      <template v-else>
        <section class="destination-grid">
          <label>邮件地址<input v-model="form.emailAddress" maxlength="254" type="email" placeholder="name@example.com" /><small>仅在某条规则启用邮件时使用。</small></label>
          <label>个人 Webhook URL<input v-model="form.webhookUrl" maxlength="1000" type="url" placeholder="https://example.com/opsflow" /><small>仅允许通过安全出站策略校验的地址。</small></label>
          <label class="secret-field">Webhook 签名密钥<input v-model="form.webhookSecret" maxlength="200" type="password" :placeholder="form.webhookSecretPreview ? '留空保持 ' + form.webhookSecretPreview : '留空自动生成'" /><small>请求通过 HMAC-SHA256 签名，密钥不会回显。</small></label>
          <label class="wecom-field">企业微信群机器人 Webhook<input v-model="form.wecomWebhookUrl" maxlength="1000" type="url" :disabled="form.clearWecomWebhook" :placeholder="form.wecomWebhookUrlPreview ? '留空保持 ' + form.wecomWebhookUrlPreview : 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…'" /><small>仅接受企业微信官方 HTTPS 机器人地址，完整密钥不会回显。</small><span v-if="form.wecomWebhookUrlPreview" class="wecom-clear"><input v-model="form.clearWecomWebhook" type="checkbox" />清除现有企业微信机器人配置</span></label>
        </section>

        <section class="delivery-policy">
          <div><strong>语言与发送时间窗</strong><small>站内信始终即时产生；时间窗只约束邮件、Webhook 和企业微信。</small></div>
          <div class="policy-grid">
            <label>通知语言<select v-model="form.preferredLocale"><option value="zh-CN">简体中文</option><option value="en-US">English</option></select></label>
            <label>接收人时区<select v-model="form.timeZone"><option value="Asia/Shanghai">Asia/Shanghai</option><option value="Asia/Hong_Kong">Asia/Hong_Kong</option><option value="Asia/Tokyo">Asia/Tokyo</option><option value="Europe/London">Europe/London</option><option value="America/New_York">America/New_York</option><option value="UTC">UTC</option></select></label>
            <label class="window-switch"><input v-model="form.deliveryWindowEnabled" type="checkbox" /><span>启用外部通知发送时间窗</span></label>
            <template v-if="form.deliveryWindowEnabled"><label>开始时间<input v-model="form.deliveryWindowStart" type="time" required /></label><label>结束时间<input v-model="form.deliveryWindowEnd" type="time" required /></label><label class="window-switch"><input v-model="form.urgentBypass" type="checkbox" /><span>重大事件、监控告警、值班升级和 SLA 逾期可越过时间窗</span></label></template>
          </div>
        </section>

        <section class="rule-section">
          <div class="rule-heading"><div><strong>事件路由规则</strong><small>具体事件优先于“全部其他事件”默认规则。</small></div><button type="button" @click="addRule">＋ 添加覆盖规则</button></div>
          <datalist id="notification-event-types"><option v-for="option in eventOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></datalist>
          <div class="rule-table-head"><span>事件</span><span>站内信</span><span>邮件</span><span>Webhook</span><span>企业微信</span><span></span></div>
          <div v-for="(rule, index) in form.rules" :key="index" class="rule-row">
            <div><strong>{{ eventLabel(rule.eventType) }}</strong><input v-if="rule.eventType !== '*'" v-model="rule.eventType" list="notification-event-types" maxlength="64" placeholder="EVENT_TYPE" /></div>
            <label><input v-model="rule.inAppEnabled" type="checkbox" /><span>启用</span></label>
            <label><input v-model="rule.emailEnabled" type="checkbox" /><span>启用</span></label>
            <label><input v-model="rule.webhookEnabled" type="checkbox" /><span>启用</span></label>
            <label><input v-model="rule.wecomEnabled" type="checkbox" /><span>启用</span></label>
            <button type="button" :disabled="rule.eventType === '*'" @click="removeRule(index)">删除</button>
          </div>
        </section>
        <p class="channel-hint">邮件需要管理员配置 SMTP；企业微信使用群机器人并验证官方域名。通道异常会进入统一重试与死信治理，不会静默丢失。</p>
      </template>
      <footer><button type="button" class="cancel" @click="emit('close')">取消</button><button class="save" :disabled="loading || saving">{{ saving ? '保存中…' : '保存通知偏好' }}</button></footer>
    </form>
  </div>
</template>

<style scoped>
.preference-backdrop{position:fixed;z-index:1200;inset:0;display:grid;place-items:center;padding:20px;background:rgba(15,29,46,.62);backdrop-filter:blur(4px)}.preference-card{width:min(860px,100%);max-height:92vh;overflow:auto;padding:24px;border-radius:18px;background:#fff;box-shadow:0 28px 80px rgba(8,23,39,.32)}header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}header p{margin:0 0 5px;color:#5e72d8;font-size:9px;font-weight:900;letter-spacing:1.3px}header h2{margin:0 0 6px;color:#20304a;font-size:22px}header span{color:#8491a4;font-size:11px}header>button{border:0;background:transparent;color:#8b97a7;font-size:24px;cursor:pointer}.preference-error,.channel-hint{padding:10px 12px;border-radius:9px;font-size:10px}.preference-error{background:#fff0f2;color:#a42e42}.preference-loading{padding:55px;color:#8491a4;text-align:center}.destination-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:20px}.destination-grid label{display:grid;gap:6px;color:#54657a;font-size:10px;font-weight:800}.destination-grid input,.rule-row input{min-width:0;padding:10px;border:1px solid #d8e0e9;border-radius:8px;color:#314158;font:inherit}.destination-grid small{color:#98a3b2;font-weight:400}.secret-field{grid-column:1/-1}.rule-section{margin-top:22px;border:1px solid #e2e8ef;border-radius:13px;overflow:hidden}.rule-heading{display:flex;align-items:center;justify-content:space-between;padding:14px;background:#f8fafc}.rule-heading strong,.rule-heading small{display:block}.rule-heading strong{color:#33445b;font-size:12px}.rule-heading small{margin-top:4px;color:#8b98a8;font-size:9px}.rule-heading button{padding:8px 10px;border:0;border-radius:8px;background:#e9edff;color:#5369c4;font-size:9px;font-weight:900;cursor:pointer}.rule-table-head,.rule-row{display:grid;grid-template-columns:minmax(260px,1fr) 85px 85px 95px 48px;align-items:center;gap:8px;padding:10px 13px}.rule-table-head{color:#8a96a6;background:#fcfdfe;font-size:8px;font-weight:900;text-transform:uppercase}.rule-row{border-top:1px solid #edf1f4}.rule-row>div{display:grid;gap:5px}.rule-row strong{color:#43546b;font-size:10px}.rule-row label{display:flex;align-items:center;gap:5px;color:#65758a;font-size:9px}.rule-row label input{width:auto}.rule-row>button{border:0;background:transparent;color:#b04a5a;font-size:9px;cursor:pointer}.rule-row>button:disabled{color:#c8ced6;cursor:default}.channel-hint{margin:12px 0 0;background:#fff8e8;color:#80601f;line-height:1.6}.preference-card>footer{display:flex;justify-content:flex-end;gap:9px;margin-top:20px}.preference-card>footer button{padding:10px 15px;border-radius:9px;font-size:10px;font-weight:900;cursor:pointer}.cancel{border:1px solid #d8e0e8;background:#fff;color:#657489}.save{border:0;background:#6075db;color:#fff}.save:disabled{opacity:.55;cursor:not-allowed}@media(max-width:700px){.destination-grid{grid-template-columns:1fr}.secret-field{grid-column:auto}.rule-section{overflow-x:auto}.rule-heading,.rule-table-head,.rule-row{min-width:690px}}
.secret-field,.wecom-field{grid-column:1/-1}.rule-table-head,.rule-row{grid-template-columns:minmax(240px,1fr) 70px 70px 82px 82px 48px}@media(max-width:700px){.secret-field,.wecom-field{grid-column:auto}.rule-heading,.rule-table-head,.rule-row{min-width:760px}}
.wecom-clear{display:flex;align-items:center;gap:6px;color:#a23b4d!important;font-size:9px!important;font-weight:700}.wecom-clear input{width:auto;padding:0}
.delivery-policy{margin-top:18px;padding:15px;border:1px solid #e2e8ef;border-radius:13px;background:#fbfcff}.delivery-policy>div:first-child strong,.delivery-policy>div:first-child small{display:block}.delivery-policy>div:first-child strong{color:#33445b;font-size:12px}.delivery-policy>div:first-child small{margin-top:4px;color:#8b98a8;font-size:9px}.policy-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:13px}.policy-grid label{display:grid;gap:6px;color:#54657a;font-size:10px;font-weight:800}.policy-grid select,.policy-grid input[type=time]{padding:10px;border:1px solid #d8e0e9;border-radius:8px;color:#314158;background:#fff}.policy-grid .window-switch{display:flex;grid-column:1/-1;align-items:center;gap:7px}.policy-grid .window-switch input{width:auto}@media(max-width:700px){.policy-grid{grid-template-columns:1fr}.policy-grid .window-switch{grid-column:auto}}
</style>
