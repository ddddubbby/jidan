<script setup lang="ts">
import type { OptionView } from '../../api/options'

// 系统设置页里的“单选卡片”（原型 .opts）：选项的可选值取自注册表，desc 是卡片下的一句补充说明
defineProps<{ def: OptionView; desc?: Record<string, string>; label?: string }>()
const model = defineModel<string>({ required: true })
</script>

<template>
  <div class="opts" :class="{ two: (def.choices?.length ?? 0) <= 2 }" role="radiogroup" :aria-label="label ?? def.name">
    <button
      v-for="c in def.choices"
      :key="c.value"
      type="button"
      class="opt"
      role="radio"
      :aria-checked="model === c.value"
      @click="model = c.value ?? ''"
    >
      <b>{{ c.label }}</b>
      <span v-if="desc?.[c.value ?? '']">{{ desc[c.value ?? ''] }}</span>
    </button>
  </div>
</template>
