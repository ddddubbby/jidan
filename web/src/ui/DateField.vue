<script setup lang="ts">
import { ref, watch } from 'vue'
import { dot, parseDot, shiftDay, today } from './date'

// 日期框（原型 dateField）：统一显示 2026.11.15；今天/昨天/前天一点即选，其余手输后回车或离开输入框生效。
// 数据是 YYYY-MM-DD；输入的不是真实日期就标红，不改值。
const model = defineModel<string>({ required: true })
const text = ref(dot(model.value))
const bad = ref(false)
watch(model, (v) => {
  text.value = dot(v)
  bad.value = false
})

const quick: [number, string][] = [[0, '今天'], [-1, '昨天'], [-2, '前天']]

function commit() {
  const iso = parseDot(text.value)
  bad.value = !iso
  if (iso) {
    model.value = iso
    text.value = dot(iso)
  }
}
</script>

<template>
  <span class="datefield">
    <span class="seg sm">
      <button v-for="[k, t] in quick" :key="k" type="button" :aria-pressed="model === shiftDay(today(), k)" @click="model = shiftDay(today(), k)">{{ t }}</button>
    </span>
    <input v-model="text" class="in" :class="{ bad }" aria-label="日期" @keydown.enter.prevent="commit" @blur="commit" />
  </span>
</template>

<style>
.datefield { display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.pop .datefield, .pop .seg { flex-wrap: nowrap; }
.datefield .in { width: 132px; height: 38px; text-align: center; font-variant-numeric: tabular-nums; }
</style>
