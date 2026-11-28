<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { errorText } from '../../api/http'
import { quote } from '../../api/orderCreate'
import { getRecords, saveHead, type OrderDetail } from '../../api/orders'
import { listRules, lossText, packText, updateRule, type RuleView } from '../../api/rules'
import Drawer from '../../ui/Drawer.vue'
import { dot } from '../../ui/date'
import { toast } from '../../ui/toast'

// 单据里的定价规则抽屉（原型 drawerOrderRule，WEB-07）：调阅这张单用的规则、换成别的规则、改每斤尾差与每箱损耗。
// 规则不绑农户，换规则就是改这张单的表头（整单覆盖，商品、规格随规则换，附录 E R-1 行）。
// 本单收购价都是后端算的试算结果（POST /orders/quote 的 trialCoef、trialLossJin），这里不自己算价（2.3）。
const props = defineProps<{ order: OrderDetail }>()
const emit = defineEmits<{ changed: [] }>()
const open = defineModel<boolean>('open', { default: false })

const rules = ref<RuleView[]>([]) // 全部收购规则（含停用的，好认出本单当前那条）
const prices = ref<Record<number, number | null>>({}) // 换成某条规则后的本单收购价
const boxJin = ref<number[]>([])
const loadErr = ref('')
const busy = ref(false)
const q = ref('')
const coef = ref<string | null>(null) // 正在改的系数，null 表示没动
const loss = ref<string | null>(null) // 正在改的每箱损耗，null 表示没动
const trial = ref<number | null | undefined>(undefined) // 按改动试算的本单收购价，undefined 表示还在算
let trialSeq = 0
let timer: ReturnType<typeof setTimeout> | undefined

const editable = computed(() => props.order.status === 'PENDING')
const p = computed(() => props.order.pricing)
const current = computed(() => rules.value.find((r) => r.id === p.value?.ruleId))
const others = computed(() => {
  const ts = q.value.trim().split(/\s+/).filter(Boolean)
  return rules.value.filter((r) => r.enabled && r.id !== p.value?.ruleId && ts.every((t) => r.name.includes(t) || packText(r).includes(t)))
})
const coefOk = computed(() => coef.value == null || /^\d{1,4}(\.\d{1,4})?$/.test(coef.value.trim()))
const lossOk = computed(() => loss.value == null || /^-?\d{1,3}(\.\d{1,2})?$/.test(loss.value.trim()))
const newCoef = computed(() => (coef.value == null || !coefOk.value ? undefined : Number(coef.value)))
const newLoss = computed(() => (loss.value == null || !lossOk.value ? undefined : Number(loss.value)))
const dirty = computed(
  () => current.value != null && coefOk.value && lossOk.value
    && ((newCoef.value != null && newCoef.value !== current.value.coef) || (newLoss.value != null && newLoss.value !== current.value.lossJin)),
)
const num = (n?: number | null) => (n == null ? '—' : String(Math.round(n * 10000) / 10000))
const sub = computed(() => `${props.order.vendor?.name ?? ''} · ${props.order.product?.name ?? ''} ${props.order.spec?.name ?? ''}`)

const req = (ruleId: number) => ({ ruleId, date: props.order.bizDate!, boxNetJin: boxJin.value })

async function load() {
  busy.value = true
  try {
    const [list, rec] = await Promise.all([listRules({ kind: 'BUY' }), getRecords(props.order.id!)])
    rules.value = list
    boxJin.value = (rec.boxes ?? []).map((b) => b.netJin!)
    loadErr.value = ''
    // 每条备选规则换上后本单的收购价
    const alt = list.filter((r) => r.enabled && r.id !== p.value?.ruleId)
    const got = await Promise.all(alt.map((r) => quote(req(r.id)).then((x) => x.unitPrice ?? null).catch(() => null)))
    prices.value = Object.fromEntries(alt.map((r, i) => [r.id, got[i]]))
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    busy.value = false
  }
}

watch(open, (o) => {
  if (!o) return
  coef.value = null
  loss.value = null
  q.value = ''
  trial.value = undefined
  rules.value = []
  prices.value = {}
  loadErr.value = ''
  load()
})

// 改系数或损耗：停手 250 毫秒后向后端要这张单按改动后的收购价
watch([coef, loss], () => {
  clearTimeout(timer)
  trial.value = undefined
  if (!editable.value || !dirty.value || !current.value) return
  const seq = ++trialSeq
  const body = { ...req(current.value.id), trialCoef: newCoef.value, trialLossJin: newLoss.value }
  timer = setTimeout(async () => {
    try {
      const r = await quote(body)
      if (seq === trialSeq) trial.value = r.unitPrice ?? null
    } catch {
      if (seq === trialSeq) trial.value = null
    }
  }, 250)
})

function stepCoef(d: number) {
  const cur = Number(coef.value ?? current.value?.coef)
  coef.value = String(Math.max(0, Math.round(((isNaN(cur) ? 0 : cur) + d) * 10000) / 10000))
}
function stepLoss(d: number) {
  const cur = Number(loss.value ?? current.value?.lossJin)
  loss.value = String(Math.round(((isNaN(cur) ? 0 : cur) + d) * 100) / 100)
}

async function run(fn: () => Promise<unknown>, done: string) {
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    toast(done)
    coef.value = null
    loss.value = null
    emit('changed')
  } catch (e) {
    toast(errorText(e))
  } finally {
    busy.value = false
  }
  await load()
}
const choose = (r: RuleView) => run(() => saveHead(props.order, { ruleId: r.id }), `已换成「${r.name}」`)
const saveRule = () => run(() => updateRule(current.value!.id, { coef: newCoef.value, lossJin: newLoss.value }), '规则已保存')
</script>

<template>
  <Drawer
    v-model:open="open"
    :title="editable ? (p?.ruleName ?? '选择收购规则') : '定价规则'"
    :sub="editable ? sub : '已审核，按审核时锁定'"
  >
    <!-- 已审核、已回传、已作废：只读 -->
    <template v-if="!editable">
      <div class="rl-grid">
        <div class="preview"><span class="muted">定价规则</span><b>{{ p?.ruleName ?? '—' }}</b></div>
        <div class="rl-rows">
          <div><span class="muted">每斤尾差</span><span>{{ num(p?.coefficient) }} 元</span></div>
          <div><span class="muted">每箱损耗</span><span>{{ lossText(p?.lossJin) }}</span></div>
          <div><span class="muted">标准净含量</span><span>{{ num(p?.standardNetJin) }} 斤/箱</span></div>
          <div><span class="muted">{{ dot(order.bizDate ?? '') }} 基准价</span><span>{{ num(p?.basePrice) }} 元/箱</span></div>
          <div><span class="muted">均重</span><span>{{ num(p?.avgJin) }} 斤</span></div>
          <div><span class="muted">收购价格</span><span>{{ num(p?.unitPrice) }} 元/箱</span></div>
        </div>
      </div>
    </template>

    <p v-else-if="loadErr" class="inline-err" role="alert">加载失败：{{ loadErr }} <button class="btn sm" @click="load">重试</button></p>

    <div v-else class="rl-grid">
      <template v-if="current">
        <p class="muted rl-note0">{{ packText(current) }}{{ current.enabled ? '' : ' · 已停用' }}</p>
        <div class="rl-two">
          <div class="field">
            <span>每斤尾差（元）</span>
            <div class="stepper">
              <button aria-label="调低尾差" :disabled="busy" @click="stepCoef(-0.1)">−</button>
              <input
                :value="coef ?? current.coef"
                class="in num"
                :class="{ bad: !coefOk }"
                inputmode="decimal"
                autocomplete="off"
                aria-label="每斤尾差"
                @input="coef = ($event.target as HTMLInputElement).value"
              />
              <button aria-label="调高尾差" :disabled="busy" @click="stepCoef(0.1)">+</button>
            </div>
          </div>
          <div class="field">
            <span>每箱损耗（斤，负数为减）</span>
            <div class="stepper">
              <button aria-label="减少损耗" :disabled="busy" @click="stepLoss(-0.5)">−</button>
              <input
                :value="loss ?? current.lossJin"
                class="in num"
                :class="{ bad: !lossOk }"
                inputmode="decimal"
                autocomplete="off"
                aria-label="每箱损耗"
                @input="loss = ($event.target as HTMLInputElement).value"
              />
              <button aria-label="增加损耗" :disabled="busy" @click="stepLoss(0.5)">+</button>
            </div>
          </div>
        </div>
        <div class="preview">
          <span class="muted">本单收购价</span>
          <span v-if="dirty">
            <template v-if="trial === undefined">…</template>
            <template v-else-if="trial == null">—</template>
            <template v-else><template v-if="trial !== p?.unitPrice"><s>{{ num(p?.unitPrice) }}</s> → </template><b>{{ num(trial) }}</b> 元/箱</template>
          </span>
          <span v-else-if="p?.unitPrice != null"><b>{{ num(p.unitPrice) }}</b> 元/箱</span>
          <span v-else class="muted">缺基准价，算不出</span>
        </div>
        <p class="muted rl-note">标准 {{ num(p?.standardNetJin) }} 斤/箱 · {{ dot(order.bizDate ?? '') }} 基准 {{ p?.basePrice != null ? num(p.basePrice) + ' 元/箱' : '未录' }} · 改动保存到规则，用这条规则的待审核单一起重算</p>
      </template>

      <div>
        <span class="lbl2">{{ current ? '换成别的规则' : '选择收购规则' }}</span>
        <input v-if="rules.length > 6" v-model="q" class="in" placeholder="规则、商品、规格或包装箱" autocomplete="off" aria-label="筛选规则" style="margin-top: 6px" />
        <div v-if="others.length" class="rl-pick">
          <button v-for="r in others" :key="r.id" :disabled="busy" @click="choose(r)">
            <span><b>{{ r.name }}</b><span class="muted rl-sub">{{ packText(r) }} · 每斤尾差 {{ num(r.coef) }} 元</span></span>
            <span class="num nb"><b v-if="prices[r.id] != null">本单 {{ num(prices[r.id]) }} 元/箱</b></span>
          </button>
        </div>
        <template v-else>
          <p class="muted" style="margin: 6px 0 0">{{ q.trim() ? '没有匹配的规则' : current ? '没有别的可用规则' : '还没有可用的收购规则' }}</p>
          <RouterLink v-if="!current && !q.trim()" class="btn todo" style="margin-top: 10px" to="/rules" @click="open = false">去定价规则页新建</RouterLink>
        </template>
      </div>
    </div>

    <template v-if="editable && dirty" #foot>
      <button class="btn" :disabled="busy" @click="coef = null; loss = null">还原</button>
      <button class="btn primary" :disabled="busy" @click="saveRule">保存到规则</button>
    </template>
  </Drawer>
</template>

<style>
.rl-grid { display: grid; gap: 16px; }
.rl-note { margin: -6px 0 0; font-size: 13px; }
.rl-note0 { margin: 0; font-size: 14px; }
.rl-two { display: flex; flex-wrap: wrap; gap: 16px; }
.rl-rows { display: grid; gap: 2px; }
.rl-rows > div { display: flex; justify-content: space-between; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--line); font-variant-numeric: tabular-nums; }
.rl-sub { display: block; font-size: 13px; }
.rl-pick { margin-top: 6px; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; background: var(--surface); }
.rl-pick button { display: flex; justify-content: space-between; align-items: center; gap: 10px; width: 100%; min-height: 52px; padding: 8px 12px; border: 0; border-bottom: 1px solid var(--line); background: var(--surface); text-align: left; }
.rl-pick button:last-child { border-bottom: 0; }
.rl-pick button:hover:not(:disabled) { background: var(--sunken); }
.preview { display: flex; justify-content: space-between; align-items: baseline; padding: 10px 12px; border-radius: 10px; background: var(--sunken); font-variant-numeric: tabular-nums; }
.preview b { font-size: 19px; }
.preview s { color: var(--ink-3); }
.lbl2 { font-size: 13px; font-weight: 600; color: var(--ink-2); }
</style>
