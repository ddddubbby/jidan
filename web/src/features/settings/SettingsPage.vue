<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { errorText } from '../../api/http'
import { getPendingCount, getSettings, putSettings, type OptionView, type Values } from '../../api/options'
import { toast } from '../../ui/toast'
import '../../styles/settings.css'
import ChoiceOpts from './ChoiceOpts.vue'

// 原型 pageSettings：全公司统一的业务设置。定义与默认值都来自 GET /settings（后端注册表），
// 页面只负责排版；改动先放在草稿里，点“保存设置”才生效，只影响之后新建的单据和未审核的单据。
// 不含“合并称重识别范围”（v7 WEB-09：阈值在高级选项页）。
const items = ref<OptionView[]>([])
const saved = ref<Record<string, string>>({})
const draft = ref<Record<string, string>>({})
const loaded = ref(false)
const error = ref('')
const busy = ref(false)
const pending = ref<number | null>(null)

const def = (key: string) => items.value.find((i) => i.key === key)
const text = (v: string | undefined) => v ?? ''

function apply(list: OptionView[]) {
  items.value = list
  saved.value = Object.fromEntries(list.map((i) => [i.key ?? '', text(i.value)]))
  draft.value = { ...saved.value }
}

async function load() {
  try {
    apply(await getSettings())
    error.value = ''
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loaded.value = true
  }
  // 待审核张数只用来写在保存按钮上；取不到不影响设置本身
  getPendingCount().then((n) => (pending.value = n), () => {})
}
onMounted(load)

const changed = computed(() => Object.keys(draft.value).filter((k) => draft.value[k] !== saved.value[k]))
const dirty = computed(() => changed.value.length > 0)
const roundChanged = computed(() => changed.value.includes('round.mode'))

// 数值框只在页面层确认“是个数、在范围内”，让按钮说清还缺什么；规整与最终校验仍在后端
const problem = computed<{ key: string; msg: string } | null>(() => {
  for (const k of changed.value) {
    const d = def(k)
    if (!d || d.type === 'ENUM') continue
    const v = draft.value[k].trim()
    const n = Number(v)
    const range = () => `${d.name}要在 ${d.min ?? '不限'} 到 ${d.max ?? '不限'} 之间`
    if (!v || !Number.isFinite(n)) return { key: k, msg: `填写${d.name}` }
    if (d.type === 'INT' && !Number.isInteger(n)) return { key: k, msg: `${d.name}要填整数` }
    if ((d.min != null && n < Number(d.min)) || (d.max != null && n > Number(d.max))) return { key: k, msg: range() }
  }
  return null
})

const saveText = computed(() => {
  if (!roundChanged.value) return '保存设置'
  if (pending.value == null) return '保存设置 · 待审核单按新规则重算'
  return pending.value ? `保存设置 · ${pending.value} 张待审核按新规则重算` : '保存设置 · 没有待审核单需要重算'
})

function focusField(key: string) {
  document.getElementById('set-' + key)?.focus()
}

function revert() {
  draft.value = { ...saved.value }
}

async function save() {
  busy.value = true
  try {
    const values: Values = {}
    for (const k of changed.value) values[k] = draft.value[k].trim()
    apply(await putSettings(values))
    toast('已保存')
  } catch (e) {
    toast(errorText(e))
  } finally {
    busy.value = false
  }
}

// 取整规则卡片上的示例（只是说明文字，不参与计算）
const roundDesc = { half: '92.1 → 92 斤 · 92.5 → 93 斤', floor: '92.1 → 92 斤 · 92.5 → 92 斤', ceil: '92.1 → 93 斤 · 92.5 → 93 斤' }
const timingDesc = { 'per-box': '记一箱打一张', 'after-confirm': '确认计量完毕后按箱序号依次打' }
const contentDesc = { minimal: '净重、批次、批次序号、二维码', full: '商品、规格、净重、农户、日期、仓库、批次、批次序号、二维码，共 9 项' }
</script>

<template>
  <div class="top"><div><h1>系统设置</h1><p>全公司统一；只影响之后的单据和待审核单据</p></div></div>

  <div v-if="error" class="result err" role="alert" style="max-width: 880px">
    {{ error }} <button class="btn sm" @click="load">重试</button>
  </div>

  <div v-if="loaded && !error" class="setgrid">
    <section v-if="def('round.mode')" class="card setcard">
      <h3>均重取整</h3>
      <ChoiceOpts v-model="draft['round.mode']" :def="def('round.mode')!" :desc="roundDesc" />
    </section>

    <section v-if="def('print.timing')" class="card setcard">
      <h3>标签打印方式</h3>
      <ChoiceOpts v-model="draft['print.timing']" :def="def('print.timing')!" :desc="timingDesc" />
    </section>

    <section v-if="def('label.content')" class="card setcard">
      <h3>标签内容</h3>
      <ChoiceOpts v-model="draft['label.content']" :def="def('label.content')!" :desc="contentDesc" />
      <p class="muted" style="margin: 8px 0 0; font-size: 13px">两种都不印均重</p>
    </section>

    <section v-if="def('device.scale-model') && def('device.printer-model')" class="card setcard">
      <h3>设备型号</h3>
      <div class="setrow" style="align-items: flex-start; gap: 24px">
        <div style="flex: 1; min-width: 240px">
          <p class="muted" style="margin: 0 0 6px; font-size: 13px; font-weight: 600">电子秤</p>
          <ChoiceOpts v-model="draft['device.scale-model']" :def="def('device.scale-model')!" label="电子秤型号" />
        </div>
        <div style="flex: 1; min-width: 240px">
          <p class="muted" style="margin: 0 0 6px; font-size: 13px; font-weight: 600">标签打印机</p>
          <ChoiceOpts v-model="draft['device.printer-model']" :def="def('device.printer-model')!" label="打印机型号" />
        </div>
      </div>
      <p class="muted" style="margin: 8px 0 0; font-size: 13px">APP 按所选型号连接对应的设备</p>
    </section>

    <section v-if="def('invoice.type')" class="card setcard">
      <h3>回传好生意</h3>
      <div class="setrow">
        <div class="seg" role="group" aria-label="开票类型">
          <button v-for="c in def('invoice.type')!.choices" :key="c.value" type="button" :aria-pressed="draft['invoice.type'] === c.value" @click="draft['invoice.type'] = c.value ?? ''">{{ c.label }}</button>
        </div>
        <span class="muted" style="margin-left: 12px">税率</span>
        <input id="set-invoice.tax-rate" v-model="draft['invoice.tax-rate']" class="in numin" inputmode="decimal" style="width: 72px; height: 38px" aria-label="税率（%）" />
        <span class="muted">%</span>
      </div>
    </section>

    <section v-if="def('label.width-mm')" class="card setcard">
      <h3>标签纸</h3>
      <div class="setrow">
        <span class="muted">宽</span>
        <input id="set-label.width-mm" v-model="draft['label.width-mm']" class="in numin" inputmode="decimal" style="width: 72px; height: 38px" aria-label="标签纸宽（mm）" />
        <span class="muted">高</span>
        <input id="set-label.height-mm" v-model="draft['label.height-mm']" class="in numin" inputmode="decimal" style="width: 72px; height: 38px" aria-label="标签纸高（mm）" />
        <span class="muted">间隙</span>
        <input id="set-label.gap-mm" v-model="draft['label.gap-mm']" class="in numin" inputmode="decimal" style="width: 64px; height: 38px" aria-label="标签纸间隙（mm）" />
        <span class="muted">mm</span>
      </div>
    </section>
  </div>

  <div class="savebar">
    <template v-if="dirty">
      <button class="btn" :disabled="busy" @click="revert">还原</button>
      <button v-if="busy" class="btn primary" disabled><span class="spin"></span> 正在保存</button>
      <button v-else-if="problem" class="btn fix" @click="focusField(problem.key)">{{ problem.msg }}</button>
      <button v-else class="btn primary" @click="save">{{ saveText }}</button>
    </template>
  </div>
</template>
