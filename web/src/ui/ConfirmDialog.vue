<script setup lang="ts">
import {
  AlertDialog, AlertDialogBackdrop, AlertDialogClose, AlertDialogContent, AlertDialogDescription, AlertDialogTitle,
} from '@ui-sdk/vue/alert-dialog'

// 确认框（原型 dialogHtml）。只给不可撤销的操作用：审核回传、重置密码（v7 4.5-4）。
// 点确认按钮才触发 confirm；取消、Esc 只关闭。点遮罩不关，免得误关掉只显示一次的内容。
// 正文放默认插槽；hideCancel 用于只有“完成”一个按钮的提示（如初始密码）。
withDefaults(defineProps<{ title: string; confirmText: string; tone?: 'primary' | 'dark'; hideCancel?: boolean }>(), { tone: 'primary' })
const open = defineModel<boolean>('open', { default: false })
const emit = defineEmits<{ confirm: [] }>()

function onChange(next: boolean, details: { returnValue?: string }) {
  if (!next && details.returnValue === 'confirm') emit('confirm')
}
</script>

<template>
  <AlertDialog v-model:open="open" @open-change="onChange">
    <AlertDialogBackdrop class="scrim dscrim" />
    <AlertDialogContent class="dialog" initial-focus="[data-confirm]">
      <AlertDialogTitle>{{ title }}</AlertDialogTitle>
      <AlertDialogDescription as="div" class="dbody"><slot /></AlertDialogDescription>
      <div class="acts">
        <AlertDialogClose v-if="!hideCancel" class="btn">取消</AlertDialogClose>
        <AlertDialogClose class="btn" :class="tone" value="confirm" data-confirm>{{ confirmText }}</AlertDialogClose>
      </div>
    </AlertDialogContent>
  </AlertDialog>
</template>

<style>
.dscrim { z-index: auto; }
.dialog { position: fixed; left: 50%; top: 50%; width: min(460px, calc(100vw - 32px)); max-width: none; margin: 0; padding: 24px; border: 0; border-radius: 18px; background: var(--surface); color: var(--ink); box-shadow: 0 24px 60px rgba(0,0,0,.22); transform: translate(-50%, -50%); opacity: 1; transition: transform 180ms var(--ease-out), opacity 180ms ease; }
.dialog[data-starting-style], .dialog[data-ending-style] { transform: translate(-50%, -50%) scale(0.97); opacity: 0; }
.dialog[data-ending-style] { transition-duration: 150ms; }
.dialog::backdrop { background: none; }
.dialog h2 { font-size: 19px; }
.dialog .dbody { margin: 0; }
.dialog .amt { margin: 12px 0 2px; font-size: 40px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.dialog .amt small { font-size: 17px; font-weight: 600; color: var(--ink-2); letter-spacing: 0; }
.dialog .acts { display: flex; justify-content: flex-end; gap: 10px; margin-top: 22px; }
@media (prefers-reduced-motion: reduce) { .dialog { transition: none !important; } }
</style>
