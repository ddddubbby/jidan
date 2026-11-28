<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorText } from '../../api/http'
import { getPrices } from '../../api/basics'
import { getOptions } from '../../api/options'
import {
  getBatch, listWarehouses, quote, searchVendors, submitOrder,
  type NamedRow, type QuoteResult, type VendorRow,
} from '../../api/orderCreate'
import { listRules, packText, type RuleView } from '../../api/rules'
import { useMeta } from '../../stores/meta'
import DateField from '../../ui/DateField.vue'
import { dot, today } from '../../ui/date'
import Num from '../../ui/Num.vue'
import { fmtPhone } from '../../ui/phone'
import { toast } from '../../ui/toast'
import BoxEntryTable from './BoxEntryTable.vue'

// 新建入库单（WEB-06，原型 pageNew）：农户 → 定价规则 → 仓库与日期 → 逐箱净重，当前一步高亮，
// 主按钮始终写明还缺哪一步（v7 4.5-1），点了直达。缺基准价时主按钮改成对应的动作。
// 定价规则按名称直接选，不绑农户；选了规则，商品、规格、包装箱与每箱损耗就都定了（附录 E R-1 行）。
// 保存时先按收购日期取批次号，再带着它提交；保存失败重试复用同一个批次号，改了日期才重新取（开发规格 8.2）。
const route = useRoute()
const router = useRouter()
const meta = useMeta()

type StepKey = 'farmer' | 'rule' | 'warehouse' | 'boxes'
const STEPS: [StepKey, string][] = [['farmer', '农户'], ['rule', '定价规则'], ['warehouse', '仓库与日期'], ['boxes', '逐箱净重']]

// ---- 选择 ----
const q = ref('')
const hits = ref<VendorRow[]>([])
const hitsFor = ref('') // hits 是按哪个关键词查到的；没查完时不说“没有匹配”
const vendor = ref<VendorRow | null>(null)
const ruleId = ref<number | null>(null)
const ruleQ = ref('')
const warehouseId = ref<number | null>(null)
const date = ref(today())
const boxes = ref<number[]>([])

// ---- 选项数据 ----
const rules = ref<RuleView[]>([])
const warehouses = ref<NamedRow[]>([])
const specs = ref<{ id: number; name: string; price: number | null }[]>([])
const blockNoPrice = ref(true) // submit.without-price：默认不能带着缺项提交
const loadErr = ref('')

// ---- 计价与保存 ----
const result = ref<QuoteResult | null>(null)
const quoteErr = ref('')
const saving = ref(false)
const saveErr = ref('')
let batch: { batchNo: string; date: string } | null = null

const boxTable = ref<InstanceType<typeof BoxEntryTable> | null>(null)
const qEl = ref<HTMLInputElement | null>(null)

const rule = computed(() => rules.value.find((r) => r.id === ruleId.value) ?? null)
const specId = computed(() => rule.value?.specId ?? null)
const specName = computed(() => rule.value?.specName ?? '')
const priceOf = (specId: number) => specs.value.find((s) => s.id === specId)?.price ?? null
// 规则多时按名称、商品、规格、包装箱筛；空格分开的几个词都要命中
const shownRules = computed(() => {
  const words = ruleQ.value.trim().split(/\s+/).filter(Boolean)
  return rules.value.filter((r) => words.every((w) => `${r.name} ${r.productName} ${r.specName} ${r.tareName ?? ''}`.includes(w)))
})
const warehouseName = computed(() => warehouses.value.find((w) => w.id === warehouseId.value)?.name ?? '')

const done = computed<Record<StepKey, boolean>>(() => ({
  farmer: !!vendor.value,
  rule: ruleId.value != null,
  warehouse: warehouseId.value != null && !!date.value,
  boxes: boxes.value.length > 0,
}))
const active = computed(() => STEPS.find(([k]) => !done.value[k])?.[0] ?? null)
const stepState = (k: StepKey) => (done.value[k] ? 'done' : k === active.value ? 'active' : 'todo')


// ---- 总数：箱数、总重用整数分算，不引入浮点误差；均重按后端取整规则 ----
const totalJin = computed(() => boxes.value.reduce((s, n) => s + Math.round(n * 100), 0) / 100)
const jin = (n: number) => String(Math.round(n * 100) / 100)
const money = (n: number) => n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const num = (n: number) => String(Math.round(n * 100) / 100)

const issues = computed(() => result.value?.issues ?? [])
const noBase = computed(() => issues.value.includes('NO_BASE_PRICE'))
const ROUND_NAME: Record<string, string> = { HALF: '四舍五入', FLOOR: '抹零', CEIL: '进位' }
const formula = computed(() => {
  const r = result.value
  if (!r || r.unitPrice == null) return ''
  const raw = r.rawAvgJin ?? 0
  const loss = r.lossJin ?? 0
  // 每箱损耗调整加在均重上再取整（附录 E R-1 行）
  const adj = loss ? ` ${loss > 0 ? '+' : '−'} 每箱损耗 ${num(Math.abs(loss))} = ${(raw + loss).toFixed(2)}` : ''
  const head = `均重 ${raw.toFixed(2)}${adj} ${ROUND_NAME[r.roundMode ?? 'HALF']}为 ${num(r.avgJin ?? 0)} 斤`
  const x = r.tailJin ?? 0
  if (x === 0) return `${head} · 正好标准，按基准 ${num(r.basePrice ?? 0)} 元/箱`
  return `${head} · 基准 ${num(r.basePrice ?? 0)} ${x > 0 ? '+ 多' : '− 少'} ${num(Math.abs(x))} 斤 × ${num(r.coefficient ?? 0)} 元 = ${num(r.unitPrice)} 元/箱`
})

// ---- 主按钮：还缺哪一步 / 缺什么就写什么，点了直达 ----
interface Primary { text: string; kind: 'todo' | 'fix' | 'primary'; disabled?: boolean; go: () => void }
const primary = computed<Primary>(() => {
  const a = active.value
  if (a) return { text: '还差：' + STEPS.find(([k]) => k === a)![1], kind: 'todo', go: () => focusStep(a) }
  if (saving.value) return { text: '保存中…', kind: 'primary', disabled: true, go: () => {} }
  if (blockNoPrice.value && noBase.value) return { text: `录入 ${specName.value} 基准价`, kind: 'fix', go: fixPrice }
  return { text: '保存为待审核', kind: 'primary', go: save }
})

function focusStep(k: StepKey) {
  const el = document.getElementById('st-' + k)
  el?.scrollIntoView({ block: 'center' })
  if (el && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
    el.animate([{ boxShadow: '0 0 0 0 rgba(11,107,58,.45)' }, { boxShadow: '0 0 0 10px rgba(11,107,58,0)' }], { duration: 500, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' })
  }
  if (k === 'farmer') qEl.value?.focus()
  else if (k === 'boxes') boxTable.value?.focus()
  else el?.querySelector<HTMLElement>('.seg button:not([disabled]), .pick button:not([disabled]), input')?.focus()
}

// 去基准价页录入当日（收购日期）这个规格的价，录完回到这里，已填的内容从草稿恢复（P4-2 的约定：back、backLabel、spec、date）
function fixPrice() {
  saveDraft()
  router.push({ path: '/prices', query: { spec: String(specId.value), date: date.value, back: '/orders/new?resume=1', backLabel: '新建入库单' } })
}

// ---- 选农户 ----
let hitTimer: ReturnType<typeof setTimeout> | undefined
let hitSeq = 0
watch(q, (v) => {
  clearTimeout(hitTimer)
  const text = v.trim()
  if (!text) { hits.value = []; hitsFor.value = ''; return }
  hitTimer = setTimeout(async () => {
    const my = ++hitSeq
    try {
      const r = await searchVendors(text)
      if (my === hitSeq) { hits.value = r; hitsFor.value = text }
    } catch {
      if (my === hitSeq) { hits.value = []; hitsFor.value = text }
    }
  }, 180)
})

async function pickVendor(v: VendorRow) {
  vendor.value = v
  q.value = ''
  hits.value = []
  hitsFor.value = ''
  await nextTick()
  if (active.value && active.value !== 'farmer') focusStep(active.value)
}
function clearVendor() {
  vendor.value = null
  nextTick(() => qEl.value?.focus())
}
function pickRule(id: number) {
  ruleId.value = id
  ruleQ.value = ''
  nextTick(() => { if (active.value) focusStep(active.value) })
}
function pickWarehouse(id: number) {
  warehouseId.value = id
  nextTick(() => boxTable.value?.focus())
}

// 启用的收购规则；规则页改过后回到这里重新拉
async function loadRules() {
  try {
    rules.value = await listRules({ kind: 'BUY', enabled: true })
    if (ruleId.value != null && !rules.value.some((r) => r.id === ruleId.value)) ruleId.value = null
  } catch (e) {
    loadErr.value = errorText(e)
  }
}

// ---- 当日基准价（按收购日期）----
let priceSeq = 0
async function loadSpecs() {
  const my = ++priceSeq
  try {
    const day = await getPrices(date.value)
    if (my !== priceSeq) return
    specs.value = (day.items ?? []).map((i) => ({ id: i.specId!, name: i.specName ?? '', price: i.price ?? null }))
  } catch (e) {
    if (my === priceSeq) loadErr.value = errorText(e)
  }
}
watch(date, loadSpecs)

// ---- 计价预览：选齐农户、商品、规格后随日期、箱数变化重算 ----
let quoteTimer: ReturnType<typeof setTimeout> | undefined
let quoteSeq = 0
function requote() {
  clearTimeout(quoteTimer)
  if (!vendor.value || ruleId.value == null) { result.value = null; return }
  quoteTimer = setTimeout(async () => {
    const my = ++quoteSeq
    try {
      const r = await quote({ ruleId: ruleId.value!, date: date.value, boxNetJin: boxes.value })
      if (my !== quoteSeq) return
      result.value = r
      quoteErr.value = ''
    } catch (e) {
      if (my === quoteSeq) { result.value = null; quoteErr.value = errorText(e) }
    }
  }, 150)
}
watch([vendor, ruleId, date, boxes, specs], requote, { deep: false })

// ---- 保存 ----
async function save() {
  saving.value = true
  saveErr.value = ''
  try {
    if (!batch || batch.date !== date.value) batch = await getBatch(date.value)
    const r = await submitOrder({
      batchNo: batch.batchNo, vendorId: vendor.value!.id, ruleId: ruleId.value!, warehouseId: warehouseId.value!,
      boxes: boxes.value.map((n, i) => ({ seq: i + 1, netJin: n })),
    })
    clearDraft()
    meta.refresh().catch(() => {})
    toast('已保存为待审核')
    router.push('/orders/' + r.id)
  } catch (e) {
    saveErr.value = errorText(e)
  } finally {
    saving.value = false
  }
}

// ---- 去基准价页再回来：已填的内容放在本标签页的会话存储里（只在带 resume 回来时读，保存或新开一张时清掉）----
const DRAFT = 'new-order-draft'
function saveDraft() {
  try {
    sessionStorage.setItem(DRAFT, JSON.stringify({
      vendor: vendor.value, ruleId: ruleId.value, warehouseId: warehouseId.value,
      date: date.value, boxes: boxes.value, batch,
    }))
  } catch { /* 存不了就当没有草稿 */ }
}
function clearDraft() {
  try { sessionStorage.removeItem(DRAFT) } catch { /* 同上 */ }
}
async function restoreDraft() {
  try {
    const raw = sessionStorage.getItem(DRAFT)
    if (!raw) return
    const d = JSON.parse(raw)
    vendor.value = d.vendor ?? null
    ruleId.value = d.ruleId ?? null
    warehouseId.value = d.warehouseId ?? null
    date.value = d.date ?? today()
    boxes.value = Array.isArray(d.boxes) ? d.boxes : []
    batch = d.batch ?? null
  } catch { /* 草稿坏了就从头来 */ }
}

onMounted(async () => {
  const resume = route.query.resume === '1'
  if (!resume) clearDraft()
  try {
    warehouses.value = await listWarehouses()
  } catch (e) {
    loadErr.value = errorText(e)
  }
  getOptions()
    .then((items) => (blockNoPrice.value = items.find((i) => i.key === 'submit.without-price')?.value !== 'allow'))
    .catch(() => {})
  if (resume) await restoreDraft()
  await Promise.all([loadRules(), loadSpecs()])
  requote()
  if (resume && active.value) nextTick(() => focusStep(active.value!))
  else qEl.value?.focus()
})
onBeforeUnmount(() => {
  clearTimeout(hitTimer)
  clearTimeout(quoteTimer)
})
</script>

<template>
  <button class="crumb" @click="router.push('/orders')">‹ 采购入库单</button>
  <div class="top"><div><h1>新建入库单</h1></div></div>

  <div v-if="loadErr" class="card empty">加载失败：{{ loadErr }}</div>
  <div v-else class="nw-grid">
    <div class="nw-steps">
      <!-- 1 农户 -->
      <section id="st-farmer" class="card nw-step" :class="stepState('farmer')">
        <div class="lbl"><i>{{ done.farmer ? '✓' : 1 }}</i>农户</div>
        <div>
          <div v-if="vendor" class="setrow">
            <b class="nw-big">{{ vendor.name }}</b>
            <span class="muted num">{{ vendor.phones[0] ? fmtPhone(vendor.phones[0]) : '' }}</span>
            <button class="btn sm ghost" @click="clearVendor">换一位</button>
          </div>
          <div v-else class="nw-combo">
            <input
              ref="qEl"
              v-model="q"
              class="in"
              placeholder="农户姓名或手机尾号"
              autocomplete="off"
              aria-label="农户姓名或手机尾号"
              @keydown.enter.prevent="hits.length && pickVendor(hits[0])"
            />
            <div v-if="hits.length" class="pick">
              <button v-for="h in hits" :key="h.id" @click="pickVendor(h)">
                <span>{{ h.name }}</span><span class="muted num">{{ h.phones.map(fmtPhone).join('　') }}</span>
              </button>
            </div>
            <div v-else-if="q.trim() && hitsFor === q.trim()" class="pick"><button disabled>没有匹配的农户</button></div>
          </div>
        </div>
      </section>

      <!-- 2 定价规则：按名称选，商品、规格、包装箱、每箱损耗都在规则里 -->
      <section id="st-rule" class="card nw-step" :class="stepState('rule')">
        <div class="lbl"><i>{{ done.rule ? '✓' : 2 }}</i>定价规则</div>
        <div>
          <div v-if="rule" class="setrow">
            <b class="nw-big">{{ rule.name }}</b>
            <button class="btn sm ghost" @click="ruleId = null">换一条</button>
          </div>
          <div v-if="rule" class="muted nw-pack">
            {{ packText(rule) }} · 每斤尾差 {{ rule.coef }} 元
          </div>
          <template v-else-if="vendor">
            <input v-if="rules.length > 6" v-model="ruleQ" class="in" placeholder="规则名称、商品或规格" autocomplete="off" aria-label="筛选定价规则" />
            <div v-if="shownRules.length" class="pick">
              <button v-for="r in shownRules" :key="r.id" @click="pickRule(r.id)">
                <span><b>{{ r.name }}</b><span class="muted nw-pack">{{ packText(r) }}</span></span>
                <small v-if="priceOf(r.specId) == null" class="tag warn">无基准价</small>
              </button>
            </div>
            <div v-else-if="rules.length" class="pick"><button disabled>没有匹配的规则</button></div>
            <RouterLink v-else class="btn todo" to="/rules">还没有启用的收购规则，去新建</RouterLink>
          </template>
        </div>
      </section>

      <!-- 4 仓库与日期 -->
      <section id="st-warehouse" class="card nw-step" :class="stepState('warehouse')">
        <div class="lbl"><i>{{ done.warehouse ? '✓' : 3 }}</i>仓库与日期</div>
        <div class="setrow">
          <div class="seg">
            <button v-for="w in warehouses" :key="w.id" :aria-pressed="warehouseId === w.id" :disabled="ruleId == null" @click="pickWarehouse(w.id)">{{ w.name }}</button>
          </div>
          <DateField v-if="ruleId != null" v-model="date" />
        </div>
      </section>

      <!-- 5 逐箱净重 -->
      <section id="st-boxes" class="card nw-step" :class="stepState('boxes')">
        <div class="lbl"><i>{{ done.boxes ? '✓' : 4 }}</i>逐箱净重</div>
        <div>
          <template v-if="warehouseId != null">
            <div v-if="boxes.length" class="nw-sum">
              <b class="num">{{ boxes.length }} 箱 · {{ jin(totalJin) }}斤<template v-if="result && result.boxCount"> · 均重 {{ num(result.avgJin ?? 0) }}斤</template></b>
            </div>
            <div class="twrap"><BoxEntryTable ref="boxTable" v-model="boxes" /></div>
          </template>
        </div>
      </section>
    </div>

    <aside class="card nw-side">
      <span class="k">金额</span>
      <template v-if="result && result.unitPrice != null && boxes.length">
        <div class="nw-amt"><Num :value="money(result.amount ?? 0)" unit="元" /></div>
        <span class="nw-calc">{{ num(result.unitPrice) }} 元/箱 × {{ result.boxCount }} 箱</span>
        <p class="nw-formula">{{ formula }}</p>
      </template>
      <template v-else-if="result && noBase">
        <div class="nw-amt" style="color: var(--ink-3)">—</div>
        <div class="nw-fixline">
          <span class="tag warn">无 {{ dot(date) }} 基准价</span>
          <button class="btn sm fix" @click="fixPrice">录入</button>
        </div>
      </template>
      <div v-else class="nw-amt" style="color: var(--ink-3)">—</div>
      <p v-if="quoteErr" class="inline-err" role="alert">{{ quoteErr }}</p>

      <div class="act">
        <button class="btn lg" :class="primary.kind" :disabled="primary.disabled" @click="primary.go">{{ primary.text }}</button>
        <p v-if="saveErr" class="inline-err" role="alert" tabindex="0" style="margin: 0">{{ saveErr }}</p>
        <p v-if="boxes.length && warehouseId != null && !active" class="muted" style="margin: 0; font-size: 13px">
          {{ warehouseName }} · {{ dot(date) }} 收购
        </p>
      </div>
    </aside>
  </div>
</template>

<style>
.nw-grid { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 18px; align-items: start; }
.nw-steps { display: grid; gap: 14px; min-width: 0; }
.nw-step { display: grid; grid-template-columns: 120px minmax(0, 1fr); align-items: start; gap: 16px; padding: 16px 20px; }
.nw-step .lbl { padding-top: 8px; font-weight: 700; display: flex; align-items: center; gap: 8px; }
.nw-step .lbl i { width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; font-style: normal; font-size: 13px; border: 2px solid #a3aba6; color: var(--ink-3); }
.nw-step.done .lbl i, .nw-step.active .lbl i { background: var(--accent); border-color: var(--accent); color: #fff; }
.nw-step.active { border-color: var(--accent); box-shadow: inset 0 0 0 1px var(--accent); }
.nw-step.todo { opacity: .55; }
.nw-big { font-size: 17px; }
.nw-combo { position: relative; max-width: 420px; }
.nw-combo .pick { position: absolute; left: 0; right: 0; top: 46px; z-index: 5; box-shadow: 0 10px 24px rgba(0,0,0,.1); }
.pick { margin-top: 6px; border: 1px solid var(--line); border-radius: 12px; max-height: 260px; overflow-y: auto; background: var(--surface); }
.pick button { display: flex; justify-content: space-between; gap: 10px; width: 100%; min-height: 40px; padding: 6px 12px; border: 0; border-bottom: 1px solid var(--line); background: var(--surface); text-align: left; }
.pick button:last-child { border-bottom: 0; }
.pick button .num { flex: none; white-space: nowrap; }
.pick button:hover:not(:disabled) { background: var(--sunken); }
.nw-pack { display: block; margin-top: 4px; font-size: 13px; font-weight: 500; }
.pick button > span:first-child { min-width: 0; }
.nw-sum { margin-bottom: 10px; }
.nw-side { position: sticky; top: 24px; padding: 20px; }
.nw-side .k { font-size: 15px; font-weight: 600; color: var(--ink-2); }
.nw-amt { margin: 4px 0 2px; font-size: 40px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.nw-calc { font-size: 15px; color: var(--ink-2); font-variant-numeric: tabular-nums; }
.nw-formula { margin: 8px 0 0; font-size: 13px; color: var(--ink-2); font-variant-numeric: tabular-nums; }
.nw-fixline { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 8px; }
.nw-side .act { display: grid; gap: 8px; margin-top: 18px; }
.nw-side .act .btn { width: 100%; white-space: normal; text-align: center; }
/* 提交失败的原因可能很长：框内滚动，不把按钮顶出窗口 */
.nw-side .act .inline-err { max-height: 9em; overflow-y: auto; }
@media (max-width: 1100px) {
  .nw-grid { grid-template-columns: minmax(0, 1fr); }
  .nw-side { position: static; order: -1; }
  /* 单列时金额卡在最上面，逐箱录入在下面：保存按钮固定在窗口底边，录上百箱也够得着 */
  .nw-side .act { position: fixed; left: var(--side); right: 0; bottom: 0; z-index: 5; margin: 0; padding: 12px 20px; justify-items: end; background: var(--surface); border-top: 1px solid var(--line); box-shadow: 0 -8px 16px -8px rgba(0,0,0,.14); }
  .nw-side .act .btn { width: min(100%, 420px); }
  .nw-side .act p { max-width: 100%; text-align: right; }
  .nw-side .act .inline-err { max-height: 4.6em; }
}
@media (max-width: 720px) {
  .nw-step { grid-template-columns: minmax(0, 1fr); gap: 8px; }
}
</style>
