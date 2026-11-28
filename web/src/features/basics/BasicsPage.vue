<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getRules, getSpecs, getTares, patchSpec, patchTare } from '../../api/basics'
import { errorText } from '../../api/http'
import type { Spec, TareBox } from '../../api/types'
import { toast } from '../../ui/toast'
import BasicNewDrawer from './BasicNewDrawer.vue'
import CellEdit from './CellEdit.vue'

// 规格与皮重（WEB-02、WEB-03，原型 pageBasics）：名称与数值点开就地改，开关点一下就生效，都不弹确认（v7 4.5-4）。
const specs = ref<Spec[]>([])
const tares = ref<TareBox[]>([])
const ruleCount = ref<Record<number, number>>({})
const loading = ref(true)
const loadErr = ref('')
const adding = ref<'spec' | 'tare' | null>(null)
const addOpen = ref(false)

async function load() {
  try {
    ;[specs.value, tares.value] = await Promise.all([getSpecs(), getTares()])
    loadErr.value = ''
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
  // “规则 N 条”只是参考，取不到就留空，不挡着改规格
  try {
    const counts: Record<number, number> = {}
    for (const r of await getRules()) if (r.specId != null) counts[r.specId] = (counts[r.specId] ?? 0) + 1
    ruleCount.value = counts
  } catch {
    ruleCount.value = {}
  }
}
onMounted(load)

function openAdd(kind: 'spec' | 'tare') {
  adding.value = kind
  addOpen.value = true
}

const replace = <T extends { id: number }>(list: T[], v: T) => list.splice(0, list.length, ...list.map((x) => (x.id === v.id ? v : x)))

// 每个保存函数在失败时抛出，CellEdit 把原因写在浮层里；开关失败给提示条
async function saveSpec(s: Spec, f: Parameters<typeof patchSpec>[1]) {
  replace(specs.value, await patchSpec(s.id, f))
  toast('已保存')
}
async function saveTare(t: TareBox, f: Parameters<typeof patchTare>[1]) {
  replace(tares.value, await patchTare(t.id, f))
  toast('已保存')
}
async function flip(run: () => Promise<void>) {
  try {
    await run()
  } catch (e) {
    toast(errorText(e))
  }
}
</script>

<template>
  <div class="top narrow"><div><h1>规格与皮重</h1></div></div>
  <div v-if="loadErr" class="result err narrow">
    <b>加载失败</b>{{ loadErr }} <button class="btn sm" @click="load">重试</button>
  </div>
  <template v-else-if="!loading">
    <div class="top narrow sect first"><h2>包装规格</h2><div class="acts"><button class="btn" @click="openAdd('spec')">新增规格</button></div></div>
    <table class="t narrow">
      <thead><tr><th>规格名称</th><th class="r">每箱枚数</th><th class="r">标准净含量 W0</th><th>规则</th><th>启用</th></tr></thead>
      <tbody>
        <tr v-for="s in specs" :key="s.id">
          <td>
            <CellEdit label="规格名称" kind="text" :raw="s.name" :save="(v) => saveSpec(s, { name: v })"><b>{{ s.name }}</b></CellEdit>
          </td>
          <td class="r">
            <CellEdit label="每箱枚数" kind="int" :raw="String(s.eggCount)" :save="(v) => saveSpec(s, { eggCount: Number(v) })">{{ s.eggCount }}</CellEdit>
          </td>
          <td class="r">
            <CellEdit label="标准净含量" kind="dec" :raw="String(s.stdNetJin)" :save="(v) => saveSpec(s, { stdNetJin: Number(v) })">{{ s.stdNetJin }}斤</CellEdit>
          </td>
          <td class="muted nb">{{ ruleCount[s.id] ?? 0 }} 条</td>
          <td>
            <button class="switch" role="switch" :aria-checked="s.enabled" :aria-label="'启用 ' + s.name" @click="flip(() => saveSpec(s, { enabled: !s.enabled }))"></button>
          </td>
        </tr>
        <tr v-if="!specs.length"><td colspan="5" class="empty">还没有规格</td></tr>
      </tbody>
    </table>

    <div class="top narrow sect"><h2>包装箱皮重</h2><div class="acts"><button class="btn" @click="openAdd('tare')">新增包装箱</button></div></div>
    <table class="t narrow">
      <thead><tr><th>包装箱</th><th class="r">皮重</th><th>启用</th><th>APP 热键</th></tr></thead>
      <tbody>
        <tr v-for="t in tares" :key="t.id">
          <td>
            <CellEdit label="包装箱名称" kind="text" :raw="t.name" :save="(v) => saveTare(t, { name: v })"><b>{{ t.name }}</b></CellEdit>
          </td>
          <td class="r">
            <CellEdit label="皮重" kind="dec" :raw="String(t.tareJin)" :save="(v) => saveTare(t, { tareJin: Number(v) })">{{ t.tareJin }}斤</CellEdit>
          </td>
          <td>
            <button class="switch" role="switch" :aria-checked="t.enabled" :aria-label="'启用 ' + t.name" @click="flip(() => saveTare(t, { enabled: !t.enabled }))"></button>
          </td>
          <td>
            <button v-if="t.enabled" class="switch" role="switch" :aria-checked="t.hotkey" :aria-label="t.name + ' 出现在 APP 皮重选项'" @click="flip(() => saveTare(t, { hotkey: !t.hotkey }))"></button>
            <span v-else class="muted">—</span>
          </td>
        </tr>
        <tr v-if="!tares.length"><td colspan="4" class="empty">还没有包装箱</td></tr>
      </tbody>
    </table>
  </template>

  <BasicNewDrawer v-if="adding" v-model:open="addOpen" :kind="adding" @created="load" />
</template>

<style>
.top.sect { margin: 28px 0 10px; }
.top.sect.first { margin-top: 0; }
.top.sect h2 { font-size: 19px; }
.result { margin-top: 14px; padding: 12px 14px; border-radius: 12px; font-size: 15px; }
.result.ok { background: var(--ok-bg); color: var(--ok-ink); }
.result.err { background: var(--err-bg); color: var(--err-ink); }
.result b { display: block; }
.t td .ie b { font-weight: 600; }
</style>
