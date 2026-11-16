<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getSpecs } from '../../api/basics'
import { errorText } from '../../api/http'
import { listArchive, type HeadPatch, type OrderDetail } from '../../api/orders'
import DateField from '../../ui/DateField.vue'
import InlineEdit from '../../ui/InlineEdit.vue'
import { dot } from '../../ui/date'
import { fmtPhone } from '../../ui/phone'

// 单据表头与商品行（WEB-07、WEB-08，原型 pageOrder 的 info 与商品行表格）。
// 待审核时农户、仓库、收购日期、商品、规格都是就地编辑（v7 4.5-2）：点开在值下方的浮层里选，选中即保存；
// 保存由页面做（整单覆盖，见 api/orders.ts 的 saveHead），失败时页面给提示，这里的浮层保持打开。
// 审核后（含回传失败）整块锁定，没有下划线。
const props = defineProps<{ d: OrderDetail; editable: boolean; save: (p: HeadPatch) => Promise<boolean> }>()
const emit = defineEmits<{ bindRule: [] }>()

type Opt = { id: number; name: string; phones?: string[] }
const warehouses = ref<Opt[]>([])
const products = ref<Opt[]>([])
const specs = ref<Opt[]>([])
const loadErr = ref('')

async function ensure(kind: 'warehouse' | 'product' | 'spec') {
  loadErr.value = ''
  try {
    if (kind === 'warehouse') warehouses.value = (await listArchive('warehouses')) as Opt[]
    else if (kind === 'product') products.value = (await listArchive('products')) as Opt[]
    else specs.value = (await getSpecs()).filter((s) => s.enabled)
  } catch (e) {
    loadErr.value = errorText(e)
  }
}

async function pick(patch: HeadPatch, close: () => void) {
  if (await props.save(patch)) close()
}

// 农户：输入姓名或手机尾号，停一下再查
const farmerQ = ref('')
const hits = ref<Opt[]>([])
let timer: ReturnType<typeof setTimeout> | undefined
let seq = 0
watch(farmerQ, (q) => {
  clearTimeout(timer)
  if (!q.trim()) {
    hits.value = []
    return
  }
  timer = setTimeout(async () => {
    const my = ++seq
    try {
      const r = (await listArchive('vendors', q.trim(), 20)) as Opt[]
      if (my === seq) hits.value = r
    } catch (e) {
      loadErr.value = errorText(e)
    }
  }, 200)
})
onBeforeUnmount(() => clearTimeout(timer))
function onFarmer(open: boolean) {
  if (!open) return
  farmerQ.value = ''
  hits.value = []
  loadErr.value = ''
}

// 日期：DateField 选中或手输生效后立刻保存
const draftDate = ref('')
function onDate(open: boolean) {
  if (open) draftDate.value = props.d.bizDate ?? ''
}
async function commitDate(iso: string, close: () => void) {
  if (iso === props.d.bizDate) return close()
  await pick({ bizDate: iso }, close)
}
</script>

<template>
  <div class="card info">
    <div class="cell">
      <span class="k">农户（供应商）</span>
      <span class="v">
        <InlineEdit label="农户" :locked="!editable" @update:open="onFarmer">
          {{ d.vendor?.name }}
          <template #editor="{ close }">
            <input v-model="farmerQ" class="in" placeholder="农户姓名或手机尾号" autocomplete="off" aria-label="搜索农户" />
            <div v-if="hits.length" class="list">
              <button v-for="h in hits" :key="h.id" @click="pick({ vendorId: h.id }, close)">
                <span>{{ h.name }}</span><span class="muted num">{{ h.phones?.[0] ? fmtPhone(h.phones[0]) : '' }}</span>
              </button>
            </div>
            <div v-else-if="farmerQ.trim()" class="muted pnote">没有匹配的农户</div>
            <div v-if="loadErr" class="inline-err pnote">{{ loadErr }}</div>
          </template>
        </InlineEdit>
      </span>
    </div>
    <div class="cell"><span class="k">手机号</span><span class="v num small">{{ d.vendor?.phones?.[0] ? fmtPhone(d.vendor.phones[0]) : '—' }}</span></div>
    <div class="cell">
      <span class="k">收购仓库</span>
      <span class="v">
        <InlineEdit label="收购仓库" :locked="!editable" @update:open="(o: boolean) => o && ensure('warehouse')">
          {{ d.warehouse?.name }}
          <template #editor="{ close }">
            <div class="seg sm">
              <button v-for="w in warehouses" :key="w.id" :aria-pressed="w.id === d.warehouse?.id" @click="pick({ warehouseId: w.id }, close)">{{ w.name }}</button>
            </div>
            <div v-if="loadErr" class="inline-err pnote">{{ loadErr }}</div>
          </template>
        </InlineEdit>
      </span>
    </div>
    <div class="cell">
      <span class="k">收购日期</span>
      <span class="v num">
        <InlineEdit label="收购日期" :locked="!editable" @update:open="onDate">
          {{ dot(d.bizDate ?? '') }}
          <template #editor="{ close }">
            <DateField :model-value="draftDate" @update:model-value="(v: string) => { draftDate = v; commitDate(v, close) }" />
          </template>
        </InlineEdit>
      </span>
    </div>
    <div class="cell"><span class="k">好生意供应商编码</span><span class="v num">{{ d.vendor?.hsCode || '—' }}</span></div>
    <div class="cell"><span class="k">均重取整</span><span class="v">{{ ({ HALF: '四舍五入', FLOOR: '抹零', CEIL: '进位' } as Record<string, string>)[d.pricing?.roundMode ?? ''] ?? '—' }}</span></div>
    <div class="cell"><span class="k">磅秤员</span><span class="v">{{ d.operator?.name }}</span></div>
    <div class="cell"><span class="k">好生意单据</span><span class="v small num" :title="d.hsId">{{ d.hsId || '—' }}</span></div>
  </div>

  <h2 class="sec-title">商品行</h2>
  <div class="twrap">
    <table class="t">
      <thead>
        <tr><th>商品名称</th><th>规格</th><th>单位</th><th class="r">数量</th><th>均重单位</th><th class="r">均重</th><th class="r">收购价格</th><th class="r">金额</th></tr>
      </thead>
      <tbody>
        <tr>
          <td>
            <InlineEdit label="商品" :locked="!editable" @update:open="(o: boolean) => o && ensure('product')">
              {{ d.product?.name }}
              <template #editor="{ close }">
                <div class="seg sm">
                  <button v-for="p in products" :key="p.id" :aria-pressed="p.id === d.product?.id" @click="pick({ productId: p.id }, close)">{{ p.name }}</button>
                </div>
                <div v-if="loadErr" class="inline-err pnote">{{ loadErr }}</div>
              </template>
            </InlineEdit>
          </td>
          <td>
            <InlineEdit label="规格" :locked="!editable" @update:open="(o: boolean) => o && ensure('spec')">
              <span class="nb">{{ d.spec?.name }}</span>
              <template #editor="{ close }">
                <div class="seg sm">
                  <button v-for="s in specs" :key="s.id" :aria-pressed="s.id === d.spec?.id" @click="pick({ specId: s.id }, close)">{{ s.name }}</button>
                </div>
                <div v-if="loadErr" class="inline-err pnote">{{ loadErr }}</div>
              </template>
            </InlineEdit>
          </td>
          <td>箱</td>
          <td class="r">{{ d.pricing?.boxCount }}</td>
          <td>kg</td>
          <td class="r">{{ d.pricing?.avgKg }}</td>
          <td class="r">
            <template v-if="d.pricing?.unitPrice != null">{{ d.pricing.unitPrice }}</template>
            <!-- 缺项：直接给能补上的那个按钮，不写“请先…” -->
            <button v-else-if="d.pricing?.issues?.includes('NO_RULE') && !d.pricing?.issues?.includes('NO_BASE_PRICE')" class="btn fix sm" @click="emit('bindRule')">绑定规则</button>
            <span v-else class="tag warn">缺基准价</span>
          </td>
          <td class="r"><b>{{ d.pricing?.amount != null ? d.pricing.amount.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : '—' }}</b></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style>
.dgrid { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 18px; align-items: start; }
.info { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.info > .cell { position: relative; padding: 14px 18px; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); min-width: 0; text-align: left; }
.info > .cell:nth-child(4n) { border-right: 0; }
.info > .cell:nth-last-child(-n+4) { border-bottom: 0; }
.cell .k { display: block; font-size: 13px; color: var(--ink-2); }
.cell .v { display: block; margin-top: 2px; font-size: 17px; font-weight: 600; overflow-wrap: anywhere; }
.cell .v.small { font-size: 15px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sec-title { margin: 22px 0 10px; font-size: 17px; font-weight: 700; }
.pnote { margin-top: 6px; font-size: 13px; }
@media (max-width: 1100px) {
  .dgrid { grid-template-columns: minmax(0, 1fr); }
  .info { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .info > .cell:nth-child(4n) { border-right: 1px solid var(--line); }
  .info > .cell:nth-child(2n) { border-right: 0; }
  .info > .cell:nth-last-child(-n+4) { border-bottom: 1px solid var(--line); }
  .info > .cell:nth-last-child(-n+2) { border-bottom: 0; }
}
</style>
