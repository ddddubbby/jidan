<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText } from '../../api/http'
import { getOptions } from '../../api/options'
import { getOrderCounts } from '../../api/nav'
import { listOrders, type OrderRow, type OrderTab } from '../../api/orders'
import type { OrderCounts } from '../../api/types'
import { getSpecs } from '../../api/basics'
import { dot } from '../../ui/date'
import Num from '../../ui/Num.vue'
import { fmtPhone } from '../../ui/phone'

// 采购入库单列表（WEB-08，原型 pageOrders / orderTable / statusStrip）：四个页签、检索、缺基准价提示条。
// 页签、关键词、页码都放在地址的查询参数里：点进单据再返回、从基准价页录完回来，都停在原处。
const PAGE_SIZE = 20
const TABS: OrderTab[] = ['pending', 'failed', 'sent', 'all']

const route = useRoute()
const router = useRouter()

const tab = computed<OrderTab>(() => {
  const t = String(route.query.tab ?? 'pending') as OrderTab
  return TABS.includes(t) ? t : 'pending'
})
const q = ref(String(route.query.q ?? ''))
const page = computed(() => Math.max(1, Number(route.query.page) || 1))

const counts = ref<OrderCounts>({ pending: 0, failed: 0, unsent: 0, missingBasePrices: [] })
const manualPush = ref(false) // push.on-approve = manual：回传失败页签改叫“待回传”，并含没有失败原因的已审核单
const rows = ref<OrderRow[]>([])
const total = ref(0)
const loaded = ref(false)
const error = ref('')

// 实际向后端要的页签：手动回传时“待回传”要看到全部已审核未回传的单
const apiTab = computed<OrderTab>(() => (tab.value === 'failed' && manualPush.value ? 'unsent' : tab.value))

const tabs = computed(() => [
  { k: 'pending' as const, t: '待审核', n: counts.value.pending ?? 0, red: false },
  {
    k: 'failed' as const,
    t: manualPush.value ? '待回传' : '回传失败',
    n: (manualPush.value ? counts.value.unsent : counts.value.failed) ?? 0,
    red: (counts.value.failed ?? 0) > 0,
  },
  { k: 'sent' as const, t: '已回传', n: 0, red: false },
  { k: 'all' as const, t: '全部', n: 0, red: false },
])
const missing = computed(() => counts.value.missingBasePrices ?? [])
const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

async function loadCounts() {
  try {
    counts.value = await getOrderCounts()
  } catch {
    // 取不到只是少几个数字，列表照常
  }
}

let seq = 0
async function load() {
  const my = ++seq
  try {
    const r = await listOrders(apiTab.value, q.value, page.value, PAGE_SIZE)
    if (my !== seq) return
    rows.value = r.items ?? []
    total.value = r.total ?? 0
    error.value = ''
  } catch (e) {
    if (my === seq) error.value = errorText(e)
  } finally {
    if (my === seq) loaded.value = true
  }
}

function setQuery(patch: Record<string, string | undefined>) {
  router.replace({ path: '/orders', query: { ...route.query, ...patch } })
}
const goTab = (k: OrderTab) => setQuery({ tab: k === 'pending' ? undefined : k, page: undefined })
const goPage = (n: number) => setQuery({ page: n === 1 ? undefined : String(n) })

// 关键词输入停一下再查，查之前回到第一页
let timer: ReturnType<typeof setTimeout> | undefined
watch(q, (v) => {
  clearTimeout(timer)
  timer = setTimeout(() => setQuery({ q: v.trim() || undefined, page: undefined }), 250)
})
onBeforeUnmount(() => clearTimeout(timer))

watch([() => route.query.tab, () => route.query.q, () => route.query.page], load)
watch(manualPush, load)

onMounted(() => {
  load()
  loadCounts()
  getOptions()
    .then((items) => (manualPush.value = items.find((i) => i.key === 'push.on-approve')?.value === 'manual'))
    .catch(() => {})
})

// “录入”直达基准价页对应规格的那一格，录完点“回到采购入库单”回到这里（P4-2 的约定：back、backLabel、spec）
function fixPrice(specId: number) {
  router.push({ path: '/prices', query: { spec: String(specId), back: route.fullPath, backLabel: '采购入库单' } })
}

// 缺项就地补：缺基准价的单，单价格里给“录入基准价”，去的是同一个地方，只是带上这张单的收购日期与规格；
// 缺规则的单给“绑定规则”，去详情页并直接打开绑定规则的抽屉（详情页的主按钮也是这样）。列表行没带规格编号，按规格名对。
const specIds = ref<Record<string, number>>({})
getSpecs()
  .then((l) => (specIds.value = Object.fromEntries(l.filter((s) => s.id != null && s.name).map((s) => [s.name!, s.id!]))))
  .catch(() => {})
const fixOf = (o: OrderRow): 'price' | 'rule' | '' =>
  o.status !== 'PENDING' ? '' : o.issues?.includes('NO_BASE_PRICE') ? 'price' : o.issues?.includes('NO_RULE') ? 'rule' : ''
function fixInRow(o: OrderRow) {
  if (fixOf(o) === 'rule') return router.push({ path: '/orders/' + o.id, query: { bindRule: '1' } })
  const spec = specIds.value[o.specName ?? ''] ?? missing.value.find((m) => m.specName === o.specName)?.specId
  router.push({
    path: '/prices',
    query: { date: o.bizDate, spec: spec == null ? undefined : String(spec), back: route.fullPath, backLabel: '采购入库单' },
  })
}
const firstSentence = (t: string) => t.replace(/^好生意返回：/, '').split(/[，。,]/)[0]
const money = (n: number) => n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const hhmm = (iso?: string) => (iso ?? '').slice(11, 16)

function statusOf(o: OrderRow): [string, string] {
  if (o.status === 'PENDING') return ['待审核', '']
  if (o.status === 'PUSHED') return ['已回传', 'ok']
  if (o.status === 'VOID') return ['已作废', '']
  return o.pushError ? ['已审核 · 回传失败', 'err'] : ['已审核 · 待回传', '']
}

const lastHead = computed(() => (tab.value === 'pending' ? '提示' : tab.value === 'failed' && !manualPush.value ? '失败原因' : '状态'))
const showError = (o: OrderRow) => tab.value === 'failed' && !manualPush.value && !!o.pushError

const open = (o: OrderRow) => router.push('/orders/' + o.id)
const emptyText = computed(() => (q.value.trim() ? '没有匹配的单据' : tab.value === 'pending' ? '✓ 待审核已清空' : '没有单据'))
</script>

<template>
  <div class="top">
    <div><h1>采购入库单</h1></div>
    <div class="acts"><button class="btn dark" @click="router.push('/orders/new')">新建入库单</button></div>
  </div>

  <div v-if="missing.length" class="strip">
    <div class="sitem">
      今日基准价未录：{{ missing.map((m) => m.specName).join('、') }}
      <button class="btn sm" @click="fixPrice(missing[0].specId!)">录入 {{ missing[0].specName }} 基准价</button>
    </div>
  </div>

  <div class="tabs" role="tablist">
    <button v-for="x in tabs" :key="x.k" role="tab" :aria-pressed="tab === x.k" @click="goTab(x.k)">
      {{ x.t }}<span v-if="x.n && x.red" class="reddot"></span><span v-if="x.n" class="cnt">{{ x.n }}</span>
    </button>
  </div>
  <div class="bar">
    <input v-model="q" class="search" placeholder="农户、手机号、单据号或批次" aria-label="搜索单据" />
  </div>

  <div v-if="error" class="card empty">加载失败：{{ error }} <button class="btn sm" @click="load">重试</button></div>
  <div v-else-if="loaded && !rows.length" class="card empty">{{ emptyText }}</div>
  <template v-else-if="loaded">
    <div class="twrap">
      <table class="t" :class="{ ft: tab === 'failed' && !manualPush }">
        <thead>
          <tr>
            <th>单据</th><th>农户</th><th class="narrowhide">商品规格</th><th class="r">箱数</th>
            <th class="r narrowhide hf">均重</th><th class="r hf">单价</th><th class="r">金额</th>
            <th class="narrowhide hf">磅秤员</th><th>{{ lastHead }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="o in rows"
            :key="o.id"
            class="go"
            :class="{ warnrow: o.status === 'PENDING' && o.issues?.length }"
            tabindex="0"
            @click="open(o)"
            @keydown.enter="open(o)"
          >
            <td>
              <span class="num nb">{{ o.code }}</span>
              <span class="sub num">{{ dot(o.bizDate ?? '') }} {{ hhmm(o.createdAt) }} · {{ o.batchNo }}</span>
            </td>
            <td class="name" :title="o.vendorName">
              <span class="nm">{{ o.vendorName }}</span>
              <span class="sub num">{{ o.vendorPhone ? fmtPhone(o.vendorPhone) : '' }}</span>
            </td>
            <td class="narrowhide"><span class="nb">{{ o.productName }} {{ o.specName }}</span></td>
            <td class="r">{{ o.boxCount }}</td>
            <td class="r narrowhide hf"><Num :value="o.avgKg" unit="kg" /></td>
            <td class="r hf">
              <button v-if="fixOf(o) === 'price'" class="fixlink" @click.stop="fixInRow(o)" @keydown.enter.stop>录入基准价</button>
              <button v-else-if="fixOf(o) === 'rule'" class="fixlink" @click.stop="fixInRow(o)" @keydown.enter.stop>绑定规则</button>
              <Num v-else :value="o.price" />
            </td>
            <td class="r">
              <b>{{ o.amount != null ? money(o.amount) : '—' }}</b>
              <span v-if="o.amountChanged && o.submittedAmount != null" class="sub">原 {{ money(o.submittedAmount) }}</span>
            </td>
            <td class="nb narrowhide hf">{{ o.operatorName }}<span v-if="o.source === 'WEB'" class="sub">管理端自建</span></td>
            <td :class="showError(o) ? 'fill' : 'nb'">
              <template v-if="tab === 'pending'">
                <span v-if="o.manualCount" class="tag warn">手录 {{ o.manualCount }} 箱</span>
              </template>
              <span v-else-if="showError(o)" class="errline" :title="o.pushError">{{ firstSentence(o.pushError!) }}</span>
              <span v-else class="pill" :class="statusOf(o)[1]">{{ statusOf(o)[0] }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pager">
      <span>共 {{ total }} 张</span>
      <template v-if="pages > 1">
        <button class="btn sm" :disabled="page <= 1" @click="goPage(page - 1)">‹ 上一页</button>
        <span class="num">{{ page }} / {{ pages }}</span>
        <button class="btn sm" :disabled="page >= pages" @click="goPage(page + 1)">下一页 ›</button>
      </template>
    </div>
  </template>
</template>

<style>
.pager { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 12px; color: var(--ink-2); }
.tag + .tag { margin-left: 4px; }
</style>
