<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPrices, savePrice } from '../../api/basics'
import { errorText } from '../../api/http'
import { pendingBySpec } from '../../api/orders'
import { useMeta } from '../../stores/meta'
import { dot, shiftDay, today } from '../../ui/date'
import { toast } from '../../ui/toast'

// 每日基准价（WEB-04，原型 pagePrices）：回车或离开输入框即保存该行，回车后跳到下一个空格。
// 从单据、新建单跳来时带 back（回去的站内路径）与 backLabel，顶上给一个“回到…”；带 spec 时直接停在那一格。
interface Row {
  specId: number
  name: string
  stdNetKg: number
  prev: number | null // 前一天的价
  price: number | null // 已保存的价
  draft: string
  saved: boolean // 本次打开页面后保存过
  err: string
  sending: string // 正在保存的那个值，防止回车与离开输入框各存一次
}

const route = useRoute()
const router = useRouter()
const meta = useMeta()

const date = computed(() => (/^\d{4}-\d{2}-\d{2}$/.test(String(route.query.date)) ? String(route.query.date) : today()))
const isToday = computed(() => date.value >= today())
const rows = ref<Row[]>([])
const loading = ref(true)
const loadErr = ref('')
const waiting = ref<Record<string, number>>({}) // 规格名 → 这一天收购、等着这个价的待审核单张数
const inputs = ref<Record<number, HTMLInputElement | null>>({})

// 只认站内路径，免得被带到别的网站
const back = computed(() => {
  const b = String(route.query.back ?? '')
  return b.startsWith('/') && !b.startsWith('//') ? { path: b, label: String(route.query.backLabel ?? '上一页') } : null
})

const missing = computed(() => rows.value.filter((r) => r.price == null))
const canCopy = computed(() => missing.value.some((r) => r.prev != null))

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    const day = await getPrices(date.value)
    rows.value = (day.items ?? []).map((i) => ({
      specId: i.specId!,
      name: i.specName ?? '',
      stdNetKg: i.stdNetKg ?? 0,
      prev: i.prevPrice ?? null,
      price: i.price ?? null,
      draft: i.price == null ? '' : String(i.price),
      saved: false,
      err: '',
      sending: '',
    }))
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
  // 取不到只是少一句提示，不影响录价
  pendingBySpec(date.value).then((w) => (waiting.value = w), () => (waiting.value = {}))
  await nextTick()
  const sid = Number(route.query.spec)
  focusAt(rows.value.find((r) => r.specId === sid) ?? missing.value[0])
}
watch(date, load, { immediate: true })

function focusAt(r?: Row) {
  if (!r) return
  const el = inputs.value[r.specId]
  el?.focus()
  el?.select()
}

function go(delta: number) {
  router.replace({ query: { ...route.query, date: shiftDay(date.value, delta), spec: undefined } })
}

const valid = (v: string) => /^\d{1,6}(\.\d{1,2})?$/.test(v) && Number(v) > 0

/** 存一行；空的什么都不做，没改的不重复存。返回是否合法（不合法时行里标红） */
async function save(r: Row): Promise<boolean> {
  const v = r.draft.trim()
  if (v === '') {
    r.err = ''
    return true
  }
  if (!valid(v)) {
    r.err = '请填大于 0 的数，最多两位小数'
    return false
  }
  r.err = ''
  if (r.price === Number(v) || r.sending === v) return true
  r.sending = v
  try {
    const item = await savePrice(date.value, r.specId, Number(v))
    r.price = item.price ?? Number(v)
    r.draft = String(r.price)
    r.saved = true
    if (date.value === today()) meta.refresh().catch(() => {}) // 导航上的琥珀点立刻跟上
  } catch (e) {
    r.err = errorText(e)
    toast(r.err)
  } finally {
    r.sending = ''
  }
  return true
}

/** 回车：存当前行，焦点去下一个空格（先往后找，到底了回头找），没有空格了就收起焦点 */
async function enter(r: Row) {
  const i = rows.value.indexOf(r)
  const next = [...rows.value.slice(i + 1), ...rows.value.slice(0, i)].find((x) => x.draft.trim() === '')
  const ok = await save(r)
  if (!ok) return
  if (next) focusAt(next)
  else inputs.value[r.specId]?.blur()
}

async function copyPrev() {
  const todo = missing.value.filter((r) => r.prev != null)
  for (const r of todo) {
    r.draft = String(r.prev)
    await save(r)
  }
  const failed = todo.filter((r) => r.err).length
  if (failed === 0) toast(`已沿用前一天：${todo.length} 个规格`)
  else toast(`已沿用 ${todo.length - failed} 个规格，${failed} 个没存上，见各行提示`)
}

function backTo() {
  if (back.value) router.push(back.value.path)
}
</script>

<template>
  <button v-if="back" class="btn backbtn" @click="backTo">‹ 回到{{ back.label }}</button>
  <div class="top narrow">
    <div><h1>每日基准价</h1></div>
    <div class="acts">
      <button class="btn ghost" @click="go(-1)">‹ 前一天</button>
      <span class="num pdate">{{ dot(date) }}{{ date === today() ? ' · 今天' : '' }}</span>
      <button class="btn ghost" :disabled="isToday" @click="go(1)">后一天 ›</button>
    </div>
  </div>

  <div v-if="loadErr" class="result err narrow">
    <b>加载失败</b>{{ loadErr }} <button class="btn sm" @click="load">重试</button>
  </div>
  <template v-else-if="!loading">
    <div v-if="missing.length" class="strip">
      <div class="sitem">
        {{ missing.length }} 个规格未录
        <button v-if="canCopy" class="btn sm fix" @click="copyPrev">全部沿用前一天</button>
      </div>
    </div>

    <table class="t narrow">
      <thead>
        <tr><th>规格</th><th class="r">标准净含量</th><th class="r">前一天</th><th style="width: 180px">基准价（元/箱）</th><th>状态</th></tr>
      </thead>
      <tbody>
        <template v-for="r in rows" :key="r.specId">
        <tr :class="{ warnrow: r.price == null, errfirst: !!r.err }">
          <td class="spname"><b>{{ r.name }}</b></td>
          <td class="r">{{ r.stdNetKg }} kg</td>
          <td class="r">{{ r.prev ?? '—' }}</td>
          <td>
            <input
              :ref="(el) => (inputs[r.specId] = el as HTMLInputElement | null)"
              v-model="r.draft"
              class="in numin"
              :class="{ bad: r.err }"
              inputmode="decimal"
              autocomplete="off"
              :aria-label="r.name + ' 基准价'"
              @keydown.enter.prevent="enter(r)"
              @blur="save(r)"
            />
          </td>
          <td class="nb">
            <span v-if="r.err" class="inline-err">没存上</span>
            <span v-else-if="r.price != null" class="muted okmark">✓ {{ r.saved ? '已保存' : '已录' }}</span>
            <template v-else>
              <span class="tag warn">未录</span>
              <span v-if="waiting[r.name]" class="muted waitnote">{{ waiting[r.name] }} 张待审核在等</span>
            </template>
          </td>
        </tr>
        <!-- 报错可能很长：单独占一行显示全文，不挤在窄格子里 -->
        <tr v-if="r.err" class="errrow"><td colspan="5"><span class="inline-err" role="alert">{{ r.err }}</span></td></tr>
        </template>
        <tr v-if="!rows.length"><td colspan="5" class="empty">没有启用的规格，先到“规格与皮重”里新增</td></tr>
      </tbody>
    </table>
  </template>
</template>

<style>
.pdate { font-weight: 600; min-width: 112px; text-align: center; }
.okmark { font-weight: 600; }
.t tr.errfirst td { border-bottom: 0; }
.t tr.errrow td { padding-top: 0; background: var(--err-bg); }
.spname { min-width: 9em; overflow-wrap: anywhere; }
.waitnote { margin-left: 8px; font-size: 13px; }
.backbtn { margin: -6px 0 10px; }
.result { margin-top: 14px; padding: 12px 14px; border-radius: 12px; font-size: 15px; }
.result.ok { background: var(--ok-bg); color: var(--ok-ink); }
.result.err { background: var(--err-bg); color: var(--err-ink); }
.result b { display: block; }
</style>
