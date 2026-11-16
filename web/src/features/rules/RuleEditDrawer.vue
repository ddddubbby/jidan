<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { errorText } from '../../api/http'
import {
  conflictsOf, createRule, fmtPhone, listRules, searchArchive, updateRule,
  type ArchiveRow, type Conflict, type Kind, type RuleView, type Spec, type Target,
} from '../../api/rules'
import Drawer from '../../ui/Drawer.vue'
import { toast } from '../../ui/toast'

// 新建 / 编辑规则抽屉（原型 drawerRuleEdit，WEB-05）。
// 冲突：同一农户在同一商品规格上只能绑一条启用的收购规则。改绑定、商品、规格时就拿同商品规格的收购规则比对，
// 冲突就地标红并写明是哪条规则，点“从那条解绑”立即解绑；保存按钮在有冲突时变成“N 个冲突”，点了滚到冲突处。
const props = defineProps<{ rule: RuleView | null; products: ArchiveRow[]; specs: Spec[] }>()
const open = defineModel<boolean>('open', { default: false })
const emit = defineEmits<{ changed: [] }>()

interface Form { name: string; enabled: boolean; kind: Kind; productId: number | null; specId: number | null; coef: string; binds: Target[] }
const blank = (): Form => ({ name: '', enabled: true, kind: 'BUY', productId: null, specId: null, coef: '1', binds: [] })
const form = ref<Form>(blank())
const saving = ref(false)
const saveError = ref('')

// 同商品规格的启用收购规则（含正在编辑的这条，比对时排除）
const others = ref<RuleView[]>([])
const serverConflicts = ref<Conflict[]>([])
let othersSeq = 0

const isNew = computed(() => props.rule == null)
const selfId = computed(() => props.rule?.id ?? -1)

watch(open, (o) => {
  if (!o) return
  const r = props.rule
  form.value = r
    ? { name: r.name, enabled: r.enabled, kind: r.kind, productId: r.productId, specId: r.specId, coef: String(r.coef), binds: r.binds.map((b) => ({ ...b })) }
    : blank()
  saveError.value = ''
  serverConflicts.value = []
  q.value = ''
  hits.value = []
  others.value = []
  loadOthers()
  if (!r) nextTick(() => nameInput.value?.focus())
})

async function loadOthers() {
  const f = form.value
  if (f.kind !== 'BUY' || f.productId == null || f.specId == null) { others.value = []; return }
  const seq = ++othersSeq
  try {
    const list = await listRules({ kind: 'BUY', productId: f.productId, specId: f.specId })
    if (seq === othersSeq) others.value = list.filter((r) => r.enabled && r.id !== selfId.value)
  } catch {
    if (seq === othersSeq) others.value = []
  }
}
watch(() => [form.value.kind, form.value.productId, form.value.specId], () => { serverConflicts.value = []; loadOthers() })

// ---- 冲突：本地按已载入的同商品规格规则比对；保存时后端再查一遍，返回的冲突也并进来 ----
const conflicts = computed<Conflict[]>(() => {
  const f = form.value
  if (f.kind !== 'BUY' || !f.enabled) return []
  const out: Conflict[] = []
  for (const b of f.binds) {
    const o = others.value.find((r) => r.binds.some((x) => x.id === b.id))
    if (o) out.push({ vendorId: b.id, vendorName: b.name, ruleId: o.id, ruleName: o.name })
  }
  if (out.length) return out
  return serverConflicts.value.filter((c) => f.binds.some((b) => b.id === c.vendorId))
})
const conflictIds = computed(() => new Set(conflicts.value.map((c) => c.vendorId)))

async function resolve(c: Conflict) {
  try {
    // 取最新的那条规则再摘人，避免用旧的绑定列表覆盖掉别人刚加的
    const f = form.value
    const fresh = f.productId != null && f.specId != null ? await listRules({ kind: 'BUY', productId: f.productId, specId: f.specId }) : []
    const other = fresh.find((r) => r.id === c.ruleId)
    if (other) await updateRule(other.id, { bindIds: other.binds.filter((b) => b.id !== c.vendorId).map((b) => b.id) })
    serverConflicts.value = []
    toast(`已把 ${c.vendorName} 从「${c.ruleName}」解绑`)
    await loadOthers()
    emit('changed')
  } catch (e) {
    saveError.value = errorText(e)
  }
}

// ---- 绑定对象检索 ----
const q = ref('')
const hits = ref<ArchiveRow[]>([])
const nameInput = ref<HTMLInputElement>()
const coefInput = ref<HTMLInputElement>()
const conflictBox = ref<HTMLElement>()
let hitSeq = 0
let hitTimer: ReturnType<typeof setTimeout> | undefined

watch(q, (v) => {
  clearTimeout(hitTimer)
  if (!v.trim()) { hits.value = []; return }
  hitTimer = setTimeout(async () => {
    const seq = ++hitSeq
    try {
      const rows = await searchArchive(form.value.kind === 'BUY' ? 'vendors' : 'customers', v.trim(), 20)
      if (seq === hitSeq) hits.value = rows
    } catch {
      if (seq === hitSeq) hits.value = []
    }
  }, 200)
})
const candidates = computed(() => hits.value.filter((h) => !form.value.binds.some((b) => b.id === h.id)).slice(0, 8))

function bind(h: ArchiveRow) {
  const phone = h.phones?.[0]
  form.value.binds.push({ id: h.id, name: h.name, tail4: phone ? phone.slice(-4) : null })
  q.value = ''
  hits.value = []
}
function unbind(id: number) { form.value.binds = form.value.binds.filter((b) => b.id !== id) }

function setKind(k: Kind) {
  if (form.value.kind === k) return
  form.value.kind = k
  form.value.binds = [] // 农户与客户不通用
  q.value = ''
  hits.value = []
}

// ---- 保存 ----
const coefOk = computed(() => /^\d{1,4}(\.\d{1,4})?$/.test(form.value.coef.trim()))
const nameOk = computed(() => form.value.name.trim() !== '')
const shared = computed(() => (form.value.binds.length > 1 ? ` · ${form.value.binds.length} 户共用` : ''))

// 保存按钮就是下一步：缺什么写什么，点了直达
const next = computed<{ text: string; cls: string; go: () => void } | null>(() => {
  const f = form.value
  if (!nameOk.value) return { text: '填写规则名称', cls: 'todo', go: () => nameInput.value?.focus() }
  if (f.productId == null) return { text: '选择商品', cls: 'todo', go: () => document.getElementById('rule-products')?.querySelector('button')?.focus() }
  if (f.specId == null) return { text: '选择规格', cls: 'todo', go: () => document.getElementById('rule-specs')?.querySelector('button')?.focus() }
  if (!coefOk.value) return { text: '填写每 kg 尾差', cls: 'todo', go: () => coefInput.value?.focus() }
  if (conflicts.value.length) return { text: `${conflicts.value.length} 个冲突`, cls: 'fix', go: () => conflictBox.value?.scrollIntoView({ block: 'center' }) }
  return null
})

async function save() {
  const f = form.value
  if (next.value || saving.value || f.productId == null || f.specId == null) return
  saving.value = true
  saveError.value = ''
  const body = { name: f.name.trim(), kind: f.kind, productId: f.productId, specId: f.specId, coef: Number(f.coef), enabled: f.enabled, bindIds: f.binds.map((b) => b.id) }
  try {
    if (props.rule) await updateRule(props.rule.id, body)
    else await createRule(body)
    open.value = false
    toast('规则已保存')
    emit('changed')
  } catch (e) {
    const c = conflictsOf(e)
    if (c) {
      serverConflicts.value = c
      await loadOthers()
      await nextTick()
      conflictBox.value?.scrollIntoView({ block: 'center' })
    } else saveError.value = errorText(e)
  } finally {
    saving.value = false
  }
}

const enabledSpecs = computed(() => props.specs.filter((s) => s.enabled || s.id === form.value.specId))
const tail = (t: Target) => (t.tail4 ? ` ·${t.tail4}` : '')
</script>

<template>
  <Drawer v-model:open="open" :title="isNew ? '新建规则' : '编辑规则'" :sub="rule ? rule.name : undefined">
    <div class="formgrid">
      <label class="field"><span>规则名称</span><input ref="nameInput" v-model="form.name" class="in" autocomplete="off" maxlength="100" /></label>

      <div class="setrow">
        <button class="switch" role="switch" :aria-checked="form.enabled" aria-label="启用这条规则" @click="form.enabled = !form.enabled"></button>
        <span>{{ form.enabled ? '启用' : '已停用' }}</span>
      </div>

      <div class="field"><span>属性</span>
        <div class="seg">
          <button :aria-pressed="form.kind === 'BUY'" @click="setKind('BUY')">收购规则 · 绑定农户</button>
          <button :aria-pressed="form.kind === 'SELL'" @click="setKind('SELL')">发货规则 · 绑定客户</button>
        </div>
      </div>

      <div class="field"><span>商品</span>
        <div id="rule-products" class="seg">
          <button v-for="p in products" :key="p.id" :aria-pressed="form.productId === p.id" @click="form.productId = p.id">{{ p.name }}</button>
        </div>
        <RouterLink v-if="!products.length" class="btn todo" to="/archive" @click="open = false">好生意档案是空的，去同步</RouterLink>
      </div>

      <div class="field"><span>规格</span>
        <div id="rule-specs" class="seg">
          <button v-for="s in enabledSpecs" :key="s.id" :aria-pressed="form.specId === s.id" @click="form.specId = s.id">{{ s.name }}</button>
        </div>
        <RouterLink v-if="!enabledSpecs.length" class="btn todo" to="/basics" @click="open = false">还没有规格，去添加</RouterLink>
      </div>

      <label class="field"><span>每 kg 尾差（元）</span>
        <input ref="coefInput" v-model="form.coef" class="in numin" :class="{ bad: form.coef !== '' && !coefOk }" inputmode="decimal" autocomplete="off" style="width: 160px" />
      </label>

      <div class="field"><span>绑定{{ form.kind === 'BUY' ? '农户' : '客户' }}</span>
        <div class="chips">
          <span v-for="b in form.binds" :key="b.id" class="chip" :class="{ bad: conflictIds.has(b.id) }">
            {{ b.name }}<small v-if="b.tail4" class="muted num">{{ tail(b) }}</small>
            <button :aria-label="'移除 ' + b.name" @click="unbind(b.id)">×</button>
          </span>
        </div>
        <input v-model="q" class="in" :placeholder="form.kind === 'BUY' ? '农户姓名或手机号' : '客户名称'" autocomplete="off" style="margin-top: 8px" aria-label="搜索绑定对象" />
        <div v-if="candidates.length" class="pick">
          <button v-for="h in candidates" :key="h.id" @click="bind(h)">
            <span>{{ h.name }}</span>
            <span class="muted num">{{ h.phones?.length ? fmtPhone(h.phones[0]) : h.hsCode }}</span>
          </button>
        </div>
        <div v-else-if="q.trim() && !hits.length" class="muted" style="font-size: 13px">没有匹配的{{ form.kind === 'BUY' ? '农户' : '客户' }}</div>

        <div ref="conflictBox">
          <div v-for="c in conflicts" :key="c.vendorId" class="conflict" role="alert">
            <span>{{ c.vendorName }} 已绑定「{{ c.ruleName }}」</span>
            <button class="btn sm" @click="resolve(c)">从那条解绑</button>
          </div>
        </div>
      </div>
      <p v-if="saveError" class="inline-err" role="alert">{{ saveError }}</p>
    </div>

    <template #foot>
      <button v-if="next" class="btn" :class="next.cls" @click="next.go()">{{ next.text }}</button>
      <button v-else class="btn primary" :disabled="saving" @click="save()">保存{{ shared }}</button>
    </template>
  </Drawer>
</template>

<style>
/* 规则抽屉（原型“规则抽屉”一段） */
.formgrid { display: grid; gap: 16px; }
.setrow { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip { display: inline-flex; align-items: center; gap: 6px; min-height: 32px; padding: 0 6px 0 12px; border-radius: 999px; background: var(--sunken); border: 1px solid var(--line); font-weight: 600; }
.chip small { font-size: 12px; font-weight: 600; margin-left: -2px; }
.chip button { width: 24px; height: 24px; border: 0; border-radius: 50%; background: none; color: var(--ink-2); }
.chip button:hover { background: #e3e6e1; }
.chip.bad { background: var(--err-bg); border-color: var(--err-ink); color: var(--err-ink); }
.conflict { margin-top: 8px; padding: 10px 12px; border-radius: 10px; background: var(--err-bg); color: var(--err-ink); font-size: 13px; font-weight: 600; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.pick { margin-top: 6px; border: 1px solid var(--line); border-radius: 12px; max-height: 260px; overflow-y: auto; background: var(--surface); }
.pick button { display: flex; justify-content: space-between; gap: 10px; width: 100%; min-height: 40px; padding: 6px 12px; border: 0; border-bottom: 1px solid var(--line); background: var(--surface); text-align: left; }
.pick button:last-child { border-bottom: 0; }
.pick button:hover { background: var(--sunken); }
</style>
