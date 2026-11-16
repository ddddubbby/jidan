<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Popover, PopoverContent, PopoverTrigger } from '@ui-sdk/vue/popover'
import { errorText } from '../../api/http'
import { getOptions } from '../../api/options'
import {
  approveOrder, getOrder, markPushed, nextPending, pushOrder, saveHead, unapproveOrder, voidOrder,
  type HeadPatch, type NextPending, type OrderDetail,
} from '../../api/orders'
import { useMeta } from '../../stores/meta'
import ConfirmDialog from '../../ui/ConfirmDialog.vue'
import { dot } from '../../ui/date'
import { toast } from '../../ui/toast'
import BoxesDrawer from './BoxesDrawer.vue'
import OrderInfo from './OrderInfo.vue'
import OrderRuleDrawer from './OrderRuleDrawer.vue'

// 单据详情与审核（WEB-07、WEB-08，原型 pageOrder / editor / nextPendingBtn）。
// 右侧栏的按钮随状态变（开发规格 8.2）：待审核给“审核”与“作废”；已审核未回传给“回传”“退回待审核”“标记为已回传”；
// 缺项时主按钮换成能补上那一项的按钮，点了直达（v7 4.5-1）。审核回传与作废要确认，其余不弹确认（v7 4.5-4）。
const route = useRoute()
const router = useRouter()
const meta = useMeta()

const id = computed(() => Number(route.params.id))
const d = ref<OrderDetail>()
const error = ref('')
const next = ref<NextPending>() // 已回传、已作废的单旁边给“下一张待审核”
const manualPush = ref(false) // push.on-approve = manual：审核只锁定，回传另点
const busy = ref<'' | 'approve' | 'push'>('')

const boxesOpen = ref(false)
const ruleOpen = ref(false)
const approveOpen = ref(false)
const voidOpen = ref(false)
const markOpen = ref(false)
const hsId = ref('')
const nextBtn = ref<HTMLButtonElement>()

const status = computed(() => d.value?.status)
const editable = computed(() => status.value === 'PENDING')
const issues = computed(() => d.value?.pricing?.issues ?? [])
const missM0 = computed(() => editable.value && issues.value.includes('NO_BASE_PRICE'))
const missRule = computed(() => editable.value && !missM0.value && issues.value.includes('NO_RULE'))
const manualCount = computed(() => d.value?.manualCount ?? 0)

const pill = computed<[string, string]>(() => {
  const o = d.value
  if (!o) return ['', '']
  if (o.status === 'PENDING') return ['待审核', '']
  if (o.status === 'PUSHED') return ['已回传', 'ok']
  if (o.status === 'VOID') return ['已作废', '']
  return o.pushError ? ['已审核 · 回传失败', 'err'] : ['已审核 · 待回传', '']
})

const money = (n?: number) => (n == null ? '—' : n.toLocaleString('zh-CN', { maximumFractionDigits: 2 }))
const ROUND: Record<string, string> = { HALF: '四舍五入', FLOOR: '抹零', CEIL: '进位' }
// 算式只是把后端给的各项数字连起来念一遍，不重算
const formula = computed(() => {
  const p = d.value?.pricing
  if (!p || p.unitPrice == null) return ''
  const head = `均重 ${p.rawAvgKg?.toFixed(2)} ${ROUND[p.roundMode ?? ''] ?? ''}为 ${p.avgKg}kg`
  const tail = p.tailKg ?? 0
  if (tail === 0) return `${head} · 正好标准，按基准 ${p.basePrice} 元/箱`
  return `${head} · 基准 ${p.basePrice} ${tail > 0 ? '+ 多' : '− 少'} ${Math.abs(tail)}kg × ${p.coefficient} 元 = ${p.unitPrice} 元/箱`
})

// 返回列表时回到原来的页签、关键词与页码（列表把它们放在地址里）；从别处来的就回列表首页
const backPath = computed(() => {
  const prev = router.options.history.state.back
  return typeof prev === 'string' && /^\/orders(\?|$)/.test(prev) ? prev : '/orders'
})

async function load() {
  try {
    const o = await getOrder(id.value)
    d.value = o
    error.value = ''
    next.value = o.status === 'PUSHED' || o.status === 'VOID' ? await nextPending(o.id!).catch(() => undefined) : undefined
  } catch (e) {
    d.value = undefined
    error.value = errorText(e)
  }
}
const refreshNav = () => meta.refresh().catch(() => {})

watch(id, () => {
  d.value = undefined
  next.value = undefined
  error.value = ''
  load()
})
onMounted(() => {
  load()
  getOptions()
    .then((items) => (manualPush.value = items.find((i) => i.key === 'push.on-approve')?.value === 'manual'))
    .catch(() => {})
})

// ---- 改表头（待审核）
async function saveField(patch: HeadPatch) {
  try {
    d.value = await saveHead(d.value!, patch)
    toast('已保存')
    refreshNav()
    return true
  } catch (e) {
    toast(errorText(e))
    return false
  }
}

// ---- 状态流转：每个动作做完都重取一次详情，页面以后端为准
async function run(label: string, fn: () => Promise<unknown>, done?: string) {
  try {
    await fn()
    if (done) toast(done)
  } catch (e) {
    toast(label + '没成功：' + errorText(e))
  }
  await load()
  refreshNav()
}

async function approveGo() {
  busy.value = 'approve'
  await run('审核', () => approveOrder(id.value))
  busy.value = ''
  await nextTick()
  nextBtn.value?.focus() // 回传成功后焦点直接落在“下一张”上，回车即可连续处理
}
async function pushGo() {
  busy.value = 'push'
  await run('回传', () => pushOrder(id.value))
  busy.value = ''
  await nextTick()
  nextBtn.value?.focus()
}
const unapprove = () => run('退回', () => unapproveOrder(id.value), '已退回待审核，可以修改')
const voidGo = () => run('作废', () => voidOrder(id.value))
async function markGo() {
  markOpen.value = false
  await run('标记', () => markPushed(id.value, hsId.value.trim()), '已标记为已回传')
  hsId.value = ''
}

// ---- 缺项直达
function fixPrice() {
  const o = d.value!
  router.push({ path: '/prices', query: { date: o.bizDate, spec: String(o.spec?.id), back: route.fullPath, backLabel: '单据' } })
}
const goNext = () => router.push('/orders/' + next.value!.id)
</script>

<template>
  <button class="crumb" @click="router.push(backPath)">‹ 采购入库单</button>

  <div v-if="error" class="card empty">加载失败：{{ error }} <button class="btn sm" @click="load">重试</button></div>
  <template v-else-if="d">
    <div class="top">
      <div>
        <h1 class="num">{{ d.code }}</h1>
        <p>批次 {{ d.batchNo }} · {{ d.source === 'WEB' ? '管理端自建' : `APP 提交 · ${d.operator?.name}` }} · {{ dot(d.bizDate ?? '') }} {{ (d.createdAt ?? '').slice(11, 16) }}</p>
      </div>
      <div class="acts"><span class="pill big" :class="pill[1]">{{ pill[0] }}</span></div>
    </div>

    <div class="dgrid">
      <div>
        <OrderInfo :d="d" :editable="editable" :save="saveField" @bind-rule="ruleOpen = true" />
        <div class="underrow">
          <p class="formula">{{ formula }}</p>
          <div class="ops">
            <button class="btn sm" @click="ruleOpen = true">定价规则</button>
            <button class="btn sm" @click="boxesOpen = true">
              逐箱流水 · {{ d.pricing?.boxCount }} 箱<span v-if="manualCount" class="tag warn">手录 {{ manualCount }}</span>
            </button>
          </div>
        </div>
      </div>

      <aside class="card side-panel">
        <span class="k">金额</span>
        <template v-if="d.pricing?.unitPrice != null">
          <div class="amt">{{ money(d.pricing.amount) }}<small> 元</small></div>
          <span class="calc">{{ d.pricing.unitPrice }} 元/箱 × {{ d.pricing.boxCount }} 箱</span>
          <span v-if="d.submittedDiffers && d.submittedAmount != null" class="was">
            APP 提交时 {{ d.submittedPrice ?? '—' }} 元/箱 · {{ money(d.submittedAmount) }} 元
          </span>
        </template>
        <template v-else>
          <div class="amt" style="color: var(--ink-3)">—</div>
          <div class="fixline"><span class="tag warn">{{ issues.includes('NO_BASE_PRICE') ? `无 ${dot(d.bizDate ?? '')} 基准价` : '无收购规则' }}</span></div>
        </template>
        <hr />
        <div class="row"><span class="muted">累计净重</span><span class="v">{{ d.pricing?.totalKg }} kg</span></div>
        <div class="row">
          <span class="muted">均重</span>
          <span class="v">{{ d.pricing?.avgKg }} kg <span class="muted" style="font-weight: 400">（原始 {{ d.pricing?.rawAvgKg?.toFixed(2) }}）</span></span>
        </div>
        <div class="row"><span class="muted">定价规则</span><span class="v" style="max-width: 190px">{{ d.pricing?.ruleName ?? '—' }}</span></div>
        <div v-if="manualCount" class="row"><span class="muted">手录</span><span class="v"><span class="tag warn">{{ manualCount }} 箱</span></span></div>

        <div v-if="status === 'APPROVED' && d.pushError" class="result err">
          <b>回传失败</b><span class="errfull" tabindex="0">{{ d.pushError }}</span>
        </div>
        <div v-else-if="status === 'APPROVED'" class="result wait">
          <b>已审核，等待回传</b>{{ d.approver?.name }} 审核 · 已锁定
        </div>
        <div v-else-if="status === 'PUSHED'" class="result ok">
          <b>已回传好生意</b><span class="num">单据 {{ d.hsId || '—' }}</span><br />{{ d.approver?.name }} 审核 · 已锁定
        </div>
        <div v-else-if="status === 'VOID'" class="result wait"><b>已作废</b>不能修改，批次号不再复用</div>

        <div class="act">
          <!-- 回传（含正在审核）中：占住主按钮的位置 -->
          <button v-if="busy" class="btn primary lg" disabled><span class="spin"></span> {{ busy === 'approve' && manualPush ? '正在审核' : '正在回传' }}</button>

          <template v-else-if="status === 'PENDING'">
            <button v-if="missM0" class="btn fix lg" @click="fixPrice">录入 {{ d.spec?.name }} 基准价</button>
            <button v-else-if="missRule" class="btn fix lg" @click="ruleOpen = true">绑定收购规则</button>
            <button v-else class="btn primary lg" @click="approveOpen = true">{{ manualPush ? '审核' : '审核并回传' }}</button>
            <button class="btn red" @click="voidOpen = true">作废</button>
          </template>

          <template v-else-if="status === 'APPROVED'">
            <button class="btn primary lg" @click="pushGo">{{ d.pushError ? '重新回传' : '回传' }}</button>
            <button class="btn ghost" @click="unapprove">退回待审核</button>
            <Popover v-model:open="markOpen">
              <PopoverTrigger class="btn ghost">标记为已回传</PopoverTrigger>
              <PopoverContent class="pop" side="bottom" align="end" :side-offset="6" aria-label="标记为已回传">
                <label class="field">
                  <span>好生意单号（选填）</span>
                  <input v-model="hsId" class="in" autocomplete="off" aria-label="好生意单号" @keydown.enter.prevent="markGo" />
                </label>
                <div class="markrow"><button class="btn primary sm" @click="markGo">标记为已回传</button></div>
              </PopoverContent>
            </Popover>
          </template>

          <template v-else>
            <button v-if="next?.id" ref="nextBtn" class="btn dark lg nxt" :title="next.vendorName" @click="goNext">下一张待审核 · <span class="nxtname">{{ next.vendorName }}</span></button>
            <button v-else ref="nextBtn" class="btn lg" @click="router.push('/orders')">✓ 待审核已清空，回列表</button>
          </template>
        </div>
      </aside>
    </div>

    <BoxesDrawer v-model:open="boxesOpen" :order="d" @changed="load" />
    <OrderRuleDrawer v-model:open="ruleOpen" :order="d" @changed="load" />

    <ConfirmDialog v-model:open="approveOpen" :title="manualPush ? '审核这张单？' : '审核并回传好生意？'" :confirm-text="manualPush ? '审核' : '审核并回传'" @confirm="approveGo">
      <div class="amt">{{ money(d.pricing?.amount) }}<small> 元</small></div>
      <p class="calc" style="margin: 0">{{ d.vendor?.name }} · {{ d.product?.name }} {{ d.spec?.name }} · {{ d.pricing?.boxCount }} 箱 × {{ d.pricing?.unitPrice }} 元</p>
      <p class="muted" style="margin: 14px 0 0; font-size: 13px">
        {{ manualPush ? `审核后单据锁定；回传到「${meta.book}」要另外点“回传”` : `回传到「${meta.book}」，之后单据锁定` }}
      </p>
    </ConfirmDialog>
    <ConfirmDialog v-model:open="voidOpen" title="作废这张单？" confirm-text="作废" tone="dark" @confirm="voidGo">
      <p class="muted" style="margin: 10px 0 0">作废后不能恢复、不能修改，批次号不再复用；单据留在“全部”页签里。</p>
    </ConfirmDialog>
  </template>
</template>

<style>
.pill.big { font-size: 15px; padding: 4px 12px; }
.underrow { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-top: 10px; }
.underrow .formula { margin: 6px 0 0; min-width: 0; overflow-wrap: anywhere; }
.ops { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; flex: none; }
.ops .tag { margin-left: 6px; }
.formula { font-size: 13px; color: var(--ink-2); font-variant-numeric: tabular-nums; }
.side-panel { position: sticky; top: 24px; padding: 20px; }
.side-panel .k { font-size: 15px; font-weight: 600; color: var(--ink-2); }
.amt { margin: 4px 0 2px; font-size: 40px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.amt small { font-size: 17px; font-weight: 600; color: var(--ink-2); letter-spacing: 0; }
.calc { font-size: 15px; color: var(--ink-2); font-variant-numeric: tabular-nums; }
.was { display: block; margin-top: 2px; font-size: 13px; color: var(--ink-2); font-variant-numeric: tabular-nums; }
.fixline { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 8px; }
.side-panel hr { border: 0; border-top: 1px solid var(--line); margin: 16px 0; }
.side-panel .row { display: flex; justify-content: space-between; gap: 10px; padding: 4px 0; font-size: 15px; }
.side-panel .row .muted { flex: none; white-space: nowrap; }
.side-panel .row .v { font-weight: 600; text-align: right; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.side-panel .act { display: grid; grid-template-columns: minmax(0, 1fr); gap: 8px; margin-top: 18px; }
.side-panel .act .btn { width: 100%; }
.side-panel .result { overflow-wrap: anywhere; }
.side-panel .result.wait { background: var(--sunken); color: var(--ink-2); }
/* 好生意返回的失败原因可能很长：完整显示，太长就在框里滚动 */
.errfull { display: block; max-height: min(180px, 30vh); overflow-y: auto; }
/* 下一张的农户名可能很长：按钮里省略，鼠标停上去看全名 */
.nxt { max-width: 100%; white-space: pre; }
.nxtname { display: block; flex: 0 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.markrow { display: flex; justify-content: flex-end; margin-top: 8px; }
@media (max-width: 1100px) {
  .side-panel { position: static; order: -1; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); column-gap: 24px; }
  .side-panel > hr { display: none; }
  .side-panel > .k, .side-panel > .amt, .side-panel > .calc, .side-panel > .was, .side-panel > .fixline { grid-column: 1; }
  .side-panel > .row, .side-panel > .result { grid-column: 2; }
  /* 单列时卡片在最上面，主按钮贴在视口底边，长报错把它顶下去时也够得着 */
  .side-panel > .act { grid-column: 1 / -1; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; position: sticky; bottom: 0; z-index: 2; margin: 14px -20px -20px; padding: 12px 20px; border-radius: 0 0 16px 16px; background: var(--surface); box-shadow: 0 -8px 12px -8px rgba(0,0,0,.12); }
  .side-panel > .act .btn { width: auto; }
  .side-panel > .act .btn.lg { flex: 1 1 220px; }
}
</style>
