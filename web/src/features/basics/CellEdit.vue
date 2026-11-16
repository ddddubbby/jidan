<script setup lang="ts">
import { ref } from 'vue'
import { errorText } from '../../api/http'
import InlineEdit from '../../ui/InlineEdit.vue'

// 规格与皮重表里的一个可改的值：点开在值下方的浮层里改，回车保存，Esc 放弃（v7 4.5-2）。
// kind：text 名称；int 正整数；dec 大于 0、最多两位小数。校验不过就在浮层里写明，不关。
const props = defineProps<{ label: string; raw: string; kind: 'text' | 'int' | 'dec'; save: (v: string) => Promise<void> }>()

const open = ref(false)
const draft = ref('')
const err = ref('')

const RULES = {
  text: { re: /\S/, msg: '不能为空' },
  int: { re: /^\d+$/, msg: '要填正整数' },
  dec: { re: /^\d{1,6}(\.\d{1,2})?$/, msg: '要大于 0，最多两位小数' },
}

function onOpen(o: boolean) {
  if (!o) return
  draft.value = props.raw
  err.value = ''
}

async function commit(close: () => void) {
  const v = draft.value.trim()
  const rule = RULES[props.kind]
  if (!rule.re.test(v) || (props.kind !== 'text' && !(Number(v) > 0))) {
    err.value = props.label + rule.msg
    return
  }
  if (v === props.raw) return close()
  try {
    await props.save(v)
    close()
  } catch (e) {
    err.value = errorText(e)
  }
}
</script>

<template>
  <InlineEdit v-model:open="open" :label="label" @update:open="onOpen">
    <slot />
    <template #editor="{ close }">
      <div class="celledit">
        <input
          v-model="draft"
          class="in"
          :class="{ numin: kind !== 'text', bad: err }"
          :inputmode="kind === 'dec' ? 'decimal' : kind === 'int' ? 'numeric' : 'text'"
          autocomplete="off"
          :aria-label="label"
          @keydown.enter.prevent="commit(close)"
        />
        <button class="btn primary sm" @click="commit(close)">保存</button>
      </div>
      <div v-if="err" class="inline-err celler">{{ err }}</div>
    </template>
  </InlineEdit>
</template>

<style>
.celledit { display: flex; align-items: center; gap: 8px; }
.celledit .in { width: 180px; height: 38px; }
.celler { margin-top: 6px; max-width: 260px; }
</style>
