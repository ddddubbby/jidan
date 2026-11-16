<script setup lang="ts">
import { Popover, PopoverContent, PopoverTrigger } from '@ui-sdk/vue/popover'

// 就地编辑（v7 4.5-2）：可改的值常驻浅灰虚下划线，点开在值下方的浮层里改，不跳页。
// 默认插槽是显示的值；editor 插槽是浮层里的编辑控件，拿到 close 用来改完收起。
// locked 为真时（已锁定的单据）只显示值，没有下划线也点不开。
defineProps<{ label: string; locked?: boolean }>()
const open = defineModel<boolean>('open', { default: false })
</script>

<template>
  <span v-if="locked" class="ie locked"><slot /></span>
  <Popover v-else v-model:open="open">
    <PopoverTrigger class="ie" :aria-label="'修改' + label"><slot /></PopoverTrigger>
    <PopoverContent class="pop" side="bottom" align="start" :side-offset="4" :aria-label="label">
      <slot name="editor" :close="() => (open = false)" />
    </PopoverContent>
  </Popover>
</template>

<style>
.ie { display: inline; padding: 0; border: 0; background: none; font: inherit; color: inherit; text-align: left; }
button.ie { cursor: pointer; text-decoration: underline dashed #b3bab5; text-decoration-thickness: 1px; text-underline-offset: 5px; }
button.ie:hover::after { content: " 改"; font-size: 13px; font-weight: 600; color: var(--ink-3); display: inline-block; margin-left: 4px; }
.pop { width: max-content; max-width: min(440px, calc(100vw - 32px)); margin: 0; padding: 10px; background: var(--surface); color: var(--ink); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 12px 28px rgba(0,0,0,.12); text-align: left; font-weight: 400; }
.pop .in { width: 300px; max-width: 100%; }
.pop .list { margin-top: 6px; max-height: 260px; overflow-y: auto; }
.pop .list button { display: flex; justify-content: space-between; gap: 10px; width: 100%; min-height: 40px; padding: 6px 10px; border: 0; border-radius: 8px; background: none; text-align: left; }
.pop .list button:hover { background: var(--sunken); }
</style>
