<script setup lang="ts">
import { ref, watch } from 'vue'
import { toastOn, toastSeq, toastText } from './toast'

// 提示条放进浏览器的“顶层”（popover），抽屉、确认框开着时它也盖在遮罩上面；
// 否则抽屉里保存失败的报错会被遮罩压住，看不见。浏览器不支持 popover 时退回普通固定定位。
const el = ref<HTMLElement | null>(null)
let hideTimer: ReturnType<typeof setTimeout> | undefined
const canPop = () => !!el.value && typeof el.value.showPopover === 'function'
const isOpen = () => canPop() && el.value!.matches(':popover-open')

// 每条新提示都（重新）放到最上层：它出现之后才打开的抽屉会盖住它
watch(toastSeq, () => {
  clearTimeout(hideTimer)
  if (!canPop()) return
  if (isOpen()) el.value!.hidePopover()
  el.value!.showPopover()
})
watch(toastOn, (on) => {
  if (on) return
  clearTimeout(hideTimer)
  hideTimer = setTimeout(() => isOpen() && el.value!.hidePopover(), 260) // 等淡出播完
})
</script>

<template>
  <div ref="el" class="toast" popover="manual" :class="{ on: toastOn }" role="status">{{ toastText }}</div>
</template>

<style>
.toast { position: fixed; inset: auto auto 28px 50%; margin: 0; border: 0; width: max-content; height: auto; z-index: 2000; max-width: min(560px, calc(100vw - 32px)); transform: translate(-50%, 16px); opacity: 0; padding: 10px 16px; border-radius: 12px; background: var(--ink); color: #fff; font-weight: 600; overflow-wrap: anywhere; max-height: 40vh; overflow: hidden; transition: transform 200ms var(--ease-out), opacity 200ms ease; pointer-events: none; }
.toast.on { transform: translate(-50%, 0); opacity: 1; }
@starting-style { .toast.on { transform: translate(-50%, 16px); opacity: 0; } }
@media (prefers-reduced-motion: reduce) { .toast { transition: none; } }
</style>
