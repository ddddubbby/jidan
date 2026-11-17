<script setup lang="ts">
// 单独成格的“数字 + 单位”（46 kg、960 元、181 元/箱）：单位缩小一号、浅一档，紧跟数字，不留空格（附录 E，2026-11-17 F4-2）。
// 数字怎么取整、怎么分千位由调用处决定，这里原样显示；没有值时只显示“—”，不带单位。
// 一句话里的单位（“8 箱 × 120 元”、算式）不用它，照常写在文字里。
defineProps<{ value?: string | number | null; unit?: string }>()
</script>

<template>
  <span class="nv">{{ value ?? '—' }}<span v-if="unit && value != null && value !== '—'" class="nu">{{ unit }}</span></span>
</template>
