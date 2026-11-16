<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { errorText } from '../../api/http'
import { bindVendorRule, quote } from '../../api/orderCreate'
import { getRecords, listOrders, type OrderDetail } from '../../api/orders'
import { listRules, unbindVendorRule, updateRule, type RuleView } from '../../api/rules'
import Drawer from '../../ui/Drawer.vue'
import { dot } from '../../ui/date'
import { toast } from '../../ui/toast'

// 单据里的定价规则抽屉（原型 drawerOrderRule，WEB-07）：调阅这张单用的规则、换成别的规则、改换算系数、解绑。
// 本单收购价都是后端算的试算结果（POST /orders/quote 的 trialRuleId、trialCoef），这里不自己算价（2.3）。
// “缺规则时的绑定”与“换规则”是同一个动作：把这位农户在该商品规格上的规则设为所选规则。
const props = defineProps<{ order: OrderDetail }>()
const emit = defineEmits<{ changed: [] }>()
const open = defineModel<boolean>('open', { default: false })

const rules = ref<RuleView[]>([]) // 同商品规格的收购规则
const prices = ref<Record<number, number | null>>({}) // 换成某条规则后的本单收购价
const boxKg = ref<number[]>([])
const loadErr = ref('')
const busy = ref(false)
const coef = ref<string | null>(null) // 正在改的系数，null 表示没动
const trial = ref<number | null | undefined>(undefined) // 按 coef 试算的本单收购价，undefined 表示还在算
const affected = ref(0)
let trialSeq = 0
let timer: ReturnType<typeof setTimeout> | undefined

const editable = computed(() => props.order.status === 'PENDING')
const p = computed(() => props.order.pricing)
const vendor = computed(() => props.order.vendor!)
const current = computed(() => rules.value.find((r) => r.id === p.value?.ruleId))
const others = computed(() => rules.value.filter((r) => r.enabled && r.id !== p.value?.ruleId))
const coefOk = computed(() => coef.value == null || /^\d{1,4}(\.\d{1,4})?$/.test(coef.value.trim()))
const newCoef = computed(() => (coef.value == null || !coefOk.value ? null : Number(coef.value)))
const dirty = computed(() => newCoef.value != null && current.value != null && newCoef.value !== current.value.coef)
const num = (n?: number | null) => (n == null ? '—' : String(Math.round(n * 10000) / 10000))
const sub = computed(() => `${vendor.value.name} · ${props.order.product?.name} ${props.order.spec?.name}`)

const req = () => ({
  vendorId: vendor.value.id!, productId: props.order.product!.id!, specId: props.order.spec!.id!,
  date: props.order.bizDate!, boxNetKg: boxKg.value,
})

async function load() {
  busy.value = true
  try {
    const o = props.order
    const [list, rec] = await Promise.all([
      listRules({ kind: 'BUY', productId: o.product!.id!, specId: o.spec!.id! }),
      getRecords(o.id!),
    ])
    rules.value = list
    boxKg.value = (rec.boxes ?? []).map((b) => b.netKg!)
    loadErr.value = ''
    // 每条备选规则换上后本单的收购价
    const alt = list.filter((r) => r.enabled && r.id !== o.pricing?.ruleId)
    const got = await Promise.all(alt.map((r) => quote({ ...req(), trialRuleId: r.id }).then((x) => x.unitPrice ?? null).catch(() => null)))
    prices.value = Object.fromEntries(alt.map((r, i) => [r.id, got[i]]))
    countAffected()
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    busy.value = false
  }
}

// 这条规则改了系数，另有几张待审核单（同一批农户、同商品规格）会跟着重算。列表行没有规则号，按名称对
async function countAffected() {
  affected.value = 0
  const r = current.value
  if (!r) return
  const names = new Set(r.binds.map((b) => b.name))
  let n = 0
  for (let page = 1; page <= 10; page++) {
    const res = await listOrders('pending', '', page, 100).catch(() => undefined)
    if (!res) break
    for (const x of res.items ?? []) {
      if (x.id !== props.order.id && x.productName === r.productName && x.specName === r.specName && names.has(x.vendorName ?? '')) n++
    }
    if (page * 100 >= (res.total ?? 0)) break
  }
  affected.value = n
}

watch(open, (o) => {
  if (!o) return
  coef.value = null
  trial.value = undefined
  rules.value = []
  prices.value = {}
  loadErr.value = ''
  load()
})

// 改系数：停手 250 毫秒后向后端要这张单换成新系数后的收购价
watch(coef, () => {
  clearTimeout(timer)
  trial.value = undefined
  if (!editable.value || newCoef.value == null || !current.value) return
  const seq = ++trialSeq
  const c = newCoef.value
  timer = setTimeout(async () => {
    try {
      const r = await quote({ ...req(), trialRuleId: current.value!.id, trialCoef: c })
      if (seq === trialSeq) trial.value = r.unitPrice ?? null
    } catch {
      if (seq === trialSeq) trial.value = null
    }
  }, 250)
})

function step(d: number) {
  const cur = Number(coef.value ?? current.value?.coef)
  coef.value = String(Math.max(0, Math.round(((isNaN(cur) ? 0 : cur) + d) * 10000) / 10000))
}

async function run(fn: () => Promise<unknown>, done: string) {
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    toast(done)
    coef.value = null
    emit('changed')
  } catch (e) {
    toast(errorText(e))
  } finally {
    busy.value = false
  }
  await load()
}
const bind = (r: RuleView) => run(() => bindVendorRule(vendor.value.id!, props.order.product!.id!, props.order.spec!.id!, r.id), '已换绑规则')
const unbind = () => run(() => unbindVendorRule(vendor.value.id!, props.order.product!.id!, props.order.spec!.id!), `已把 ${vendor.value.name} 从这条规则解绑`)
const saveCoef = () => run(() => updateRule(current.value!.id, { coef: newCoef.value! }), '规则已保存')
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
          <div><span class="muted">每 kg 尾差</span><span>{{ num(p?.coefficient) }} 元</span></div>
          <div><span class="muted">标准净含量</span><span>{{ num(p?.standardNetKg) }} kg/箱</span></div>
          <div><span class="muted">{{ dot(order.bizDate ?? '') }} 基准价</span><span>{{ num(p?.basePrice) }} 元/箱</span></div>
          <div><span class="muted">均重</span><span>{{ num(p?.avgKg) }} kg</span></div>
          <div><span class="muted">收购价格</span><span>{{ num(p?.unitPrice) }} 元/箱</span></div>
        </div>
      </div>
    </template>

    <p v-else-if="loadErr" class="inline-err" role="alert">加载失败：{{ loadErr }} <button class="btn sm" @click="load">重试</button></p>

    <div v-else class="rl-grid">
      <template v-if="current">
        <div class="field">
          <span>每 kg 尾差（元）</span>
          <div class="stepper">
            <button aria-label="调低" :disabled="busy" @click="step(-0.1)">−</button>
            <input
              :value="coef ?? current.coef"
              class="in num"
              :class="{ bad: !coefOk }"
              inputmode="decimal"
              autocomplete="off"
              aria-label="每 kg 尾差"
              @input="coef = ($event.target as HTMLInputElement).value"
            />
            <button aria-label="调高" :disabled="busy" @click="step(0.1)">+</button>
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
          <span v-else class="muted">缺{{ p?.issues?.includes('NO_BASE_PRICE') ? '基准价' : '规则' }}，算不出</span>
        </div>
        <p class="muted rl-note">标准 {{ num(p?.standardNetKg) }} kg/箱 · {{ dot(order.bizDate ?? '') }} 基准 {{ p?.basePrice != null ? num(p.basePrice) + ' 元/箱' : '未录' }} · {{ current.bindCount }} 户共用</p>
      </template>

      <div>
        <span class="lbl2">{{ current ? '换成别的规则' : '选择收购规则' }}</span>
        <div v-if="others.length" class="rl-pick">
          <button v-for="r in others" :key="r.id" :disabled="busy" @click="bind(r)">
            <span><b>{{ r.name }}</b><span class="muted rl-sub">每 kg 尾差 {{ num(r.coef) }} 元 · {{ r.bindCount }} 户</span></span>
            <span class="num nb"><b v-if="prices[r.id] != null">本单 {{ num(prices[r.id]) }} 元/箱</b></span>
          </button>
        </div>
        <template v-else>
          <p class="muted" style="margin: 6px 0 0">{{ current ? '没有别的可用规则' : '这个商品规格还没有可用的收购规则' }}</p>
          <RouterLink v-if="!current" class="btn todo" style="margin-top: 10px" to="/rules" @click="open = false">去定价规则页新建</RouterLink>
        </template>
      </div>

      <div v-if="current"><button class="btn red" :disabled="busy" @click="unbind">从这条规则解绑 {{ vendor.name }}</button></div>
    </div>

    <template v-if="editable && dirty" #foot>
      <button class="btn" :disabled="busy" @click="coef = null">还原</button>
      <button class="btn primary" :disabled="busy" @click="saveCoef">
        保存到规则{{ current && current.bindCount > 1 ? ` · ${current.bindCount} 户共用` : '' }}{{ affected ? ` · 另 ${affected} 张待审核随之重算` : '' }}
      </button>
    </template>
  </Drawer>
</template>

<style>
.rl-grid { display: grid; gap: 16px; }
.rl-note { margin: -6px 0 0; font-size: 13px; }
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
.stepper { display: inline-flex; align-items: center; gap: 8px; }
.stepper button { width: 40px; height: 40px; border: 1.5px solid var(--edge); border-radius: 10px; background: var(--surface); font-size: 20px; font-weight: 600; }
.stepper .in { width: 96px; text-align: center; font-size: 17px; font-weight: 700; }
.lbl2 { font-size: 13px; font-weight: 600; color: var(--ink-2); }
</style>
