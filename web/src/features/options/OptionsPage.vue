<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, errorText } from '../../api/http'
import { getMeta } from '../../api/nav'
import { failNext, getOptions, putOptions, type FailMode, type OptionView, type Values } from '../../api/options'
import type { Meta } from '../../api/types'
import ConfirmDialog from '../../ui/ConfirmDialog.vue'
import { toast } from '../../ui/toast'
import '../../styles/settings.css'

// 高级选项页（开发规格 4.4、8.2）：按 GET /options 返回的定义自动画出两组选项，加一个选项不用改这里。
// 改一项立即保存（下一次建单生效，不用重启）；与默认值不同的项标出来，可逐项或一次全部还原。
const items = ref<OptionView[]>([])
const meta = ref<Meta>()
const loaded = ref(false)
const error = ref('')
const onlyChanged = ref(false)
const restoreAll = ref(false)

// 数值框的草稿与逐项错误；枚举点一下就保存，不需要草稿
const drafts = ref<Record<string, string>>({})
const errors = ref<Record<string, string>>({})
const busyKey = ref('')

const isDemo = computed(() => meta.value?.chanjetMode !== 'real')
const differs = (o: OptionView) => o.value !== o.defaultValue
const changedKeys = computed(() => items.value.filter(differs).map((o) => o.key ?? ''))
const groups = computed(() => {
  const out: { key: string; name: string; items: OptionView[] }[] = []
  for (const o of items.value) {
    if (onlyChanged.value && !differs(o)) continue
    let g = out.find((x) => x.key === o.group)
    if (!g) out.push((g = { key: o.group ?? '', name: o.groupName ?? '', items: [] }))
    g.items.push(o)
  }
  return out
})

function apply(list: OptionView[]) {
  items.value = list
  drafts.value = Object.fromEntries(list.map((o) => [o.key ?? '', o.value ?? '']))
}

async function load() {
  try {
    const [list, m] = await Promise.all([getOptions(), getMeta().catch(() => undefined)])
    apply(list)
    meta.value = m
    error.value = ''
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loaded.value = true
  }
}
onMounted(load)

async function put(values: Values, key = '') {
  busyKey.value = key
  try {
    apply(await putOptions(values))
    return true
  } catch (e) {
    if (key) errors.value[key] = errorText(e)
    else toast(errorText(e))
    return false
  } finally {
    busyKey.value = ''
  }
}

async function choose(o: OptionView, value: string) {
  const key = o.key ?? ''
  if (value === o.value) return
  errors.value[key] = ''
  if (await put({ [key]: value }, key)) toast('已保存，下一次建单生效')
}

async function commit(o: OptionView) {
  const key = o.key ?? ''
  const v = (drafts.value[key] ?? '').trim()
  if (v === o.value) {
    errors.value[key] = ''
    return
  }
  errors.value[key] = ''
  if (await put({ [key]: v }, key)) toast('已保存，下一次建单生效')
}

function escape(o: OptionView) {
  const key = o.key ?? ''
  drafts.value[key] = o.value ?? ''
  errors.value[key] = ''
}

async function restore(o: OptionView) {
  const key = o.key ?? ''
  errors.value[key] = ''
  if (await put({ [key]: null }, key)) toast(`已还原${o.name}`)
}

async function doRestoreAll() {
  const values: Values = {}
  for (const k of changedKeys.value) values[k] = null
  if (await put(values)) toast('已全部还原为默认值')
}

const labelOf = (o: OptionView, v?: string) => o.choices?.find((c) => c.value === v)?.label ?? v ?? ''

// ---- 模拟好生意的两个失败开关（开发规格 11.4）----
const armed = ref<FailMode | null>(null)
const failMsg = ref('')
async function fail(mode: FailMode) {
  failMsg.value = ''
  try {
    await failNext(mode)
    armed.value = mode
    toast(mode === 'reject' ? '下一次回传会被拒绝' : '下一次回传会没有答复')
  } catch (e) {
    armed.value = null
    failMsg.value = e instanceof ApiError && e.status === 404 ? '当前后端还没有提供失败开关' : errorText(e)
  }
}
</script>

<template>
  <div class="top">
    <div><h1>高级选项</h1><p>联调与现场调整用；改完下一次建单生效，不用重启</p></div>
    <div v-if="changedKeys.length" class="acts">
      <label class="muted opt-only"><input v-model="onlyChanged" type="checkbox" /> 只看改过的</label>
      <button class="btn" @click="restoreAll = true">全部还原为默认值</button>
    </div>
  </div>

  <div v-if="error" class="result err" role="alert" style="max-width: 880px">
    {{ error }} <button class="btn sm" @click="load">重试</button>
  </div>

  <div v-if="loaded && !error" class="setgrid">
    <section v-if="meta" class="card setcard" data-testid="chanjet-card">
      <h3>
        好生意
        <span v-if="isDemo" class="tag warn demo-tag" data-testid="demo-tag">演示模式</span>
        <span v-else class="tag">真实</span>
      </h3>
      <p class="muted" style="margin: 0">
        <template v-if="isDemo">对接的是模拟好生意：档案与回传都是演示数据，不会写入真实账套。</template>
        <template v-else>对接真实好生意。</template>
        账套：{{ meta.bookName }}
      </p>
      <div v-if="isDemo" style="margin-top: 14px">
        <div class="setrow">
          <button class="btn" :aria-pressed="armed === 'reject'" @click="fail('reject')">让下一次回传被拒绝</button>
          <button class="btn" :aria-pressed="armed === 'no-reply'" @click="fail('no-reply')">让下一次回传无答复</button>
        </div>
        <p v-if="failMsg" class="inline-err" role="alert" style="margin: 8px 0 0">{{ failMsg }}</p>
        <p v-else-if="armed" class="muted" style="margin: 8px 0 0; font-size: 13px">
          已设置：{{ armed === 'reject' ? '下一次回传会被好生意拒绝' : '下一次回传会没有答复' }}
        </p>
        <p v-else class="muted" style="margin: 8px 0 0; font-size: 13px">用来走“回传失败 → 重新回传 / 退回待审核”和无答复时的提示</p>
      </div>
    </section>

    <section v-for="g in groups" :key="g.key" class="card setcard">
      <h3>{{ g.name }}</h3>
      <div class="optlist">
        <div v-for="o in g.items" :key="o.key" class="optrow" :class="{ changed: differs(o) }" :data-key="o.key">
          <div class="optinfo">
            <div class="optname">
              <b>{{ o.name }}</b>
              <code class="optkey">{{ o.key }}</code>
              <span v-if="differs(o)" class="tag warn">已改</span>
            </div>
            <p class="muted opthint">{{ o.hint }}</p>
            <p class="muted opthint">默认：{{ labelOf(o, o.defaultValue) }}</p>
          </div>
          <div class="optctl">
            <div v-if="o.type === 'ENUM'" class="seg sm" role="group" :aria-label="o.name">
              <button
                v-for="c in o.choices"
                :key="c.value"
                type="button"
                :aria-pressed="o.value === c.value"
                :disabled="busyKey === o.key"
                @click="choose(o, c.value ?? '')"
              >{{ c.label }}</button>
            </div>
            <input
              v-else
              :id="'opt-' + o.key"
              v-model="drafts[o.key ?? '']"
              class="in numin optnum"
              :class="{ bad: errors[o.key ?? ''] }"
              inputmode="decimal"
              :aria-label="o.name"
              :disabled="busyKey === o.key"
              @keydown.enter.prevent="commit(o)"
              @keydown.esc="escape(o)"
              @blur="commit(o)"
            />
            <p v-if="errors[o.key ?? '']" class="inline-err" role="alert">{{ errors[o.key ?? ''] }}</p>
            <button v-if="differs(o)" class="btn sm ghost" :disabled="busyKey === o.key" @click="restore(o)">还原默认</button>
          </div>
        </div>
        <p v-if="!g.items.length" class="muted">没有改过的选项</p>
      </div>
    </section>
    <p v-if="onlyChanged && !groups.length" class="muted">没有改过的选项</p>
  </div>

  <ConfirmDialog v-model:open="restoreAll" title="全部还原为默认值？" confirm-text="全部还原" tone="dark" @confirm="doRestoreAll">
    <p class="muted" style="margin: 10px 0 0">{{ changedKeys.length }} 项选项会回到 v7 的默认做法，下一次建单生效</p>
  </ConfirmDialog>
</template>

<style>
.setcard .btn[aria-pressed="true"] { border-color: var(--ink); background: var(--sunken); box-shadow: inset 0 0 0 1px var(--ink); }
.opt-only { display: inline-flex; align-items: center; gap: 6px; font-size: 14px; }
.demo-tag { margin-left: 8px; font-size: 13px; vertical-align: 2px; }
.optlist { display: grid; }
.optrow { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 14px 0; border-top: 1px solid var(--line); }
.optrow:first-child { border-top: 0; padding-top: 4px; }
.optinfo { min-width: 0; flex: 1; }
.optname { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.optkey { font-size: 12px; color: var(--ink-3); }
.opthint { margin: 2px 0 0; font-size: 13px; }
.optctl { display: grid; justify-items: end; gap: 6px; flex: none; max-width: 52%; }
.optctl .seg { justify-content: flex-end; }
.optnum { width: 120px; height: 38px; }
@media (max-width: 900px) {
  .optrow { flex-direction: column; gap: 8px; }
  .optctl { justify-items: start; max-width: none; }
  .optctl .seg { justify-content: flex-start; }
}
</style>
