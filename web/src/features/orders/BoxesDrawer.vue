<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { errorText } from '../../api/http'
import { getRecords, saveBoxes, type BoxRec, type OrderDetail, type OrderRecords } from '../../api/orders'
import Drawer from '../../ui/Drawer.vue'
import Num from '../../ui/Num.vue'
import { toast } from '../../ui/toast'

// 逐箱流水抽屉（原型 drawerBoxes，WEB-07）：同重归类、只看手录、改净重、删除（待审核单才能改）。
// 删箱不重排其余箱的序号：箱贴在收蛋时已打印，二维码是 批次号#序号（v7 5.4），重排会让已贴的箱贴与流水错位。
// 加箱取现有最大序号加 1。
// 每次改动都整单覆盖保存（PUT /orders/{id}，开发规格 5.2），保存后 emit changed 让页面重取单据。
// 手录与原读数由这里带上（附录 C 第 16 条）：第一次改一箱时把当时的净重记作秤原读数，已是手录的箱再改，原读数不变。
const props = defineProps<{ order: OrderDetail }>()
const emit = defineEmits<{ changed: [] }>()
const open = defineModel<boolean>('open', { default: false })

const rec = ref<OrderRecords>()
const loadErr = ref('')
const busy = ref(false)
const onlyManual = ref(false)
// 打开抽屉那一刻的箱数、均重、单价、金额：改动后与现值并排，旧值划掉
const base = ref<{ n?: number; avg?: number; price?: number; amount?: number }>({})

const editing = ref(-1) // 正在改的箱序号
const editVal = ref('')
const editBad = ref(false)
let editEl: HTMLInputElement | null = null
const setEdit = (el: unknown) => { if (el) editEl = el as HTMLInputElement }
const addEl = ref<HTMLInputElement | null>(null)
const add = ref('')
const addErr = ref('')

const editable = computed(() => props.order.status === 'PENDING')
// 按箱序号排序显示：删箱不重排，序号允许有空号
const boxes = computed(() => [...(rec.value?.boxes ?? [])].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0)))
// 下一箱的序号：现有最大序号加 1（不是箱数加 1，删过箱后两者不同）
const nextSeq = computed(() => boxes.value.reduce((m, b) => Math.max(m, b.seq ?? 0), 0) + 1)
const nMan = computed(() => boxes.value.filter((b) => b.manual).length)
const rows = computed(() => (onlyManual.value ? boxes.value.filter((b) => b.manual) : boxes.value))
const groups = computed(() => rec.value?.groups ?? [])
const p = computed(() => props.order.pricing)

const jin = (n?: number | null) => String(Math.round((n ?? 0) * 100) / 100)
const money = (n?: number) => (n == null ? '—' : n.toLocaleString('zh-CN', { maximumFractionDigits: 2 }))
const validJin = (v: string) => /^\d{1,3}(\.\d{1,2})?$/.test(v.trim()) && parseFloat(v) > 0
const round2 = (v: string) => Math.round(parseFloat(v) * 100) / 100

const tareText = computed(() => {
  const b = boxes.value[0]
  return b ? ` · 每箱皮重 ${jin(b.tareJin)} 斤${b.tareName ? `（${b.tareName}）` : ''}` : ''
})

async function loadRec() {
  try {
    rec.value = await getRecords(props.order.id!)
    loadErr.value = ''
  } catch (e) {
    loadErr.value = errorText(e)
  }
  if (!nMan.value) onlyManual.value = false
}

watch(open, (o) => {
  if (!o) return
  const pr = props.order.pricing
  base.value = { n: pr?.boxCount, avg: pr?.avgJin, price: pr?.unitPrice, amount: pr?.amount }
  onlyManual.value = false
  editing.value = -1
  add.value = ''
  addErr.value = ''
  rec.value = undefined
  loadRec()
})

async function save(next: BoxRec[]) {
  if (busy.value) return false
  busy.value = true
  try {
    await saveBoxes(props.order, next)
    await loadRec()
    emit('changed')
    return true
  } catch (e) {
    toast(errorText(e))
    await loadRec()
    emit('changed') // 比如单据刚被审核：页面重取后抽屉变成只读
    return false
  } finally {
    busy.value = false
  }
}

function startEdit(b: BoxRec) {
  editing.value = b.seq!
  editVal.value = jin(b.netJin)
  editBad.value = false
  nextTick(() => {
    editEl?.focus()
    editEl?.select()
  })
}
async function saveEdit() {
  if (!validJin(editVal.value)) {
    editBad.value = true
    editEl?.focus()
    return
  }
  const seq = editing.value
  const v = round2(editVal.value)
  editing.value = -1
  const b = boxes.value.find((x) => x.seq === seq)
  if (!b || b.netJin === v) return
  await save(boxes.value.map((x) => (x.seq === seq ? { ...x, netJin: v, manual: true, rawJin: x.manual ? x.rawJin : x.netJin } : x)))
}
const cancelEdit = () => (editing.value = -1)

async function remove(seq: number) {
  editing.value = -1
  await save(boxes.value.filter((x) => x.seq !== seq))
}

// 加一箱：一箱一回车；粘进来的多个数一次加完。手工加的箱带手录记号，没有秤原读数
async function addBoxes() {
  const parts = add.value.split(/[\s,，、;；]+/).filter(Boolean)
  if (!parts.length) return
  const bad = parts.find((x) => !validJin(x))
  if (bad) {
    addErr.value = `“${bad}”不是有效的净重`
    return
  }
  const first = boxes.value[0]
  const n = nextSeq.value - 1
  const extra: BoxRec[] = parts.map((x, i) => ({
    seq: n + i + 1, netJin: round2(x), tareJin: first?.tareJin, tareName: first?.tareName, groupK: 1, manual: true, printed: false,
  }))
  if (await save([...boxes.value, ...extra])) {
    add.value = ''
    addErr.value = ''
    nextTick(() => addEl.value?.focus())
  }
}
</script>

<template>
  <Drawer v-model:open="open" :title="`逐箱流水 · ${order.pricing?.boxCount ?? 0} 箱`" :sub="order.batchNo + tareText" :foot-class="editable ? 'stats' : undefined">
    <p v-if="loadErr" class="inline-err" role="alert">加载失败：{{ loadErr }} <button class="btn sm" @click="loadRec">重试</button></p>
    <template v-else-if="rec">
      <div class="groups" aria-label="同重归类">
        <span v-for="g in groups.slice(0, 10)" :key="g.jin">{{ jin(g.jin) }}斤-{{ g.boxes }}箱</span>
        <span v-if="groups.length > 10">另 {{ groups.length - 10 }} 种</span>
      </div>

      <table class="t boxes">
        <thead>
          <tr>
            <th class="seqc">箱序号</th><th>净重</th><th></th>
            <th class="r">
              <button v-if="nMan" class="mfilter" :aria-pressed="onlyManual" @click="onlyManual = !onlyManual">{{ onlyManual ? '看全部' : `只看手录 ${nMan} 箱` }}</button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in rows" :key="b.seq">
            <td class="num seqc">第 {{ b.seq }} 箱</td>
            <td>
              <input
                v-if="editing === b.seq"
                :ref="setEdit"
                v-model="editVal"
                class="in numin edin"
                :class="{ bad: editBad }"
                inputmode="decimal"
                :aria-label="`第 ${b.seq} 箱净重`"
                @keydown.enter.prevent="saveEdit"
                @keydown.esc.stop.prevent="cancelEdit"
                @input="editBad = false"
              />
              <button v-else-if="editable" class="netbtn" :disabled="busy" :aria-label="`改第 ${b.seq} 箱净重`" @click="startEdit(b)"><Num :value="jin(b.netJin)" unit="斤" /></button>
              <b v-else class="num"><Num :value="jin(b.netJin)" unit="斤" /></b>
              <span v-if="b.manual && b.rawJin != null && editing !== b.seq" class="orig">秤读数 {{ jin(b.rawJin) }}斤</span>
            </td>
            <td><span v-if="b.manual" class="tag warn">手录</span></td>
            <td class="r nb">
              <button
                v-if="editable && editing !== b.seq"
                class="btn sm ghost del"
                :disabled="busy || boxes.length <= 1"
                :title="boxes.length <= 1 ? '至少留一箱；整张单不要了请作废' : undefined"
                :aria-label="`删除第 ${b.seq} 箱`"
                @click="remove(b.seq!)"
              >删除</button>
            </td>
          </tr>
          <tr v-if="editable && !onlyManual" class="addrow">
            <td class="num muted seqc">第 {{ nextSeq }} 箱</td>
            <td colspan="3">
              <span class="setrow" style="gap: 8px">
                <input
                  ref="addEl"
                  v-model="add"
                  class="in numin"
                  :class="{ bad: !!addErr }"
                  inputmode="decimal"
                  placeholder="净重，回车添加"
                  autocomplete="off"
                  :disabled="busy"
                  :aria-label="`第 ${nextSeq} 箱净重`"
                  @keydown.enter.prevent="addBoxes"
                  @input="addErr = ''"
                />
                <span class="inline-err" role="alert">{{ addErr }}</span>
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
    <p v-else class="muted">加载中…</p>

    <template v-if="editable" #foot>
      <div><span class="k">箱数</span><span class="v"><s v-if="base.n !== p?.boxCount">{{ base.n }}</s>{{ p?.boxCount }}</span></div>
      <div><span class="k">均重</span><span class="v"><s v-if="base.avg !== p?.avgJin">{{ base.avg }}</s><Num :value="p?.avgJin" unit="斤" /></span></div>
      <div>
        <span class="k">单价</span>
        <span class="v"><s v-if="base.price != null && p?.unitPrice != null && base.price !== p.unitPrice">{{ base.price }}</s>{{ p?.unitPrice ?? '—' }}</span>
      </div>
      <div>
        <span class="k">金额</span>
        <span class="v"><s v-if="base.amount != null && p?.amount != null && base.amount !== p.amount">{{ money(base.amount) }}</s>{{ money(p?.amount) }}</span>
      </div>
    </template>
  </Drawer>
</template>

<style>
.boxes .orig { display: block; font-size: 12px; color: var(--ink-2); }
.groups { display: flex; flex-wrap: wrap; gap: 6px; margin: 0 0 14px; }
.groups span { padding: 2px 9px; border-radius: 8px; border: 1px solid var(--line); background: var(--sunken); font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; }
.mfilter { display: inline-flex; align-items: center; gap: 6px; min-height: 28px; padding: 0 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); color: var(--ink-2); font-size: 13px; font-weight: 500; white-space: nowrap; }
.mfilter::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: var(--amber); }
.mfilter:hover { background: var(--sunken); }
.mfilter[aria-pressed="true"] { border-color: var(--ink); background: var(--ink); color: #fff; }
</style>
