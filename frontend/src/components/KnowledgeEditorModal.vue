<script setup>
import { reactive, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true }
})

const emit = defineEmits(['saved', 'notify', 'error'])
const visible = ref(false)
const saving = ref(false)
const editing = ref(null)
const form = reactive({ title: '', slug: '', category: '网络', content: '' })

function open(article = null) {
  editing.value = article
  Object.assign(form, {
    title: article?.title || '',
    slug: article?.slug || '',
    category: article?.category || '网络',
    content: article?.content || ''
  })
  visible.value = true
}

function close() {
  visible.value = false
  editing.value = null
  Object.assign(form, { title: '', slug: '', category: '网络', content: '' })
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const article = editing.value
      ? await props.api(`/api/v1/knowledge/articles/${editing.value.id}`, {
          method: 'PUT', body: JSON.stringify(form)
        })
      : await props.api('/api/v1/knowledge/articles', {
          method: 'POST', body: JSON.stringify(form)
        })
    const message = editing.value
      ? '知识草稿已更新'
      : '知识文章已保存为草稿，请完成质量检查后提交审核'
    close()
    emit('saved', article)
    emit('notify', message)
  } catch (error) {
    emit('error', error?.message || '知识草稿保存失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ open, close })
</script>

<template>
  <div v-if="visible" class="editor-backdrop" @click.self="close">
    <form class="editor-modal" @submit.prevent="save">
      <header>
        <div>
          <span>KNOWLEDGE EDITOR</span>
          <h2>{{ editing ? '编辑知识草稿' : '创建知识草稿' }}</h2>
          <small>保存后完成三项质量检查，再提交管理员审核。</small>
        </div>
        <button type="button" aria-label="关闭" @click="close">×</button>
      </header>

      <label>标题<input v-model="form.title" required maxlength="160" placeholder="例如：VPN 排障指南" /></label>
      <div class="form-grid">
        <label>Slug<input v-model="form.slug" required maxlength="180" placeholder="vpn-troubleshooting" /></label>
        <label>分类<input v-model="form.category" required maxlength="64" /></label>
      </div>
      <label>内容<textarea v-model="form.content" required maxlength="50000" rows="11" placeholder="记录适用范围、解决步骤、注意事项、验证结果和回滚方法"></textarea></label>
      <div class="quality-note">
        <strong>提交审核前</strong>
        <span>内容至少 80 字，并确认敏感信息已移除、适用范围已定义、验证步骤完整。</span>
      </div>
      <footer>
        <button type="button" class="secondary" @click="close">取消</button>
        <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存草稿' }}</button>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.editor-backdrop{position:fixed;inset:0;z-index:225;display:grid;place-items:center;padding:20px;background:rgba(13,22,38,.5)}
.editor-modal{width:min(720px,100%);display:flex;flex-direction:column;gap:15px;box-sizing:border-box;border-radius:18px;background:#fff;padding:25px;box-shadow:0 28px 80px rgba(14,27,49,.3)}
header{display:flex;align-items:flex-start;justify-content:space-between}header span{color:#8792a3;font-size:9px;font-weight:850;letter-spacing:.13em}header h2{margin:5px 0 3px;color:#1d2b40}header small{color:#7d899a}header>button{border:0;background:none;color:#768298;font-size:26px;cursor:pointer}
label{display:flex;flex-direction:column;gap:6px;color:#536176;font-size:11px;font-weight:700}.form-grid{display:grid;grid-template-columns:1.4fr 1fr;gap:12px}input,textarea{box-sizing:border-box;width:100%;border:1px solid #d9e0e9;border-radius:9px;padding:10px 12px;color:#24354e;background:#fff;font:inherit;font-weight:500}textarea{line-height:1.65;resize:vertical}input:focus,textarea:focus{outline:2px solid rgba(47,111,237,.15);border-color:#2f6fed}
.quality-note{display:flex;gap:9px;border:1px solid #dce8ff;border-radius:10px;background:#f5f8ff;padding:11px 13px;color:#536176;font-size:10px;line-height:1.5}.quality-note strong{white-space:nowrap;color:#2f5ea9}
footer{display:flex;justify-content:flex-end;gap:9px}footer button{border:0;border-radius:9px;background:#2f6fed;color:#fff;padding:10px 15px;font-weight:750;cursor:pointer}footer button.secondary{background:#edf1f6;color:#536176}footer button:disabled{opacity:.55;cursor:not-allowed}@media(max-width:650px){.form-grid{grid-template-columns:1fr}.quality-note{flex-direction:column}}
</style>
