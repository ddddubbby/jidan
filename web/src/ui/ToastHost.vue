<script setup lang="ts">
import { toastOn, toastText } from './toast'
</script>

<template>
  <div class="toast" :class="{ on: toastOn }" role="status">{{ toastText }}</div>
</template>

<style>
.toast { position: fixed; left: 50%; bottom: 28px; z-index: 2000; max-width: min(560px, calc(100vw - 32px)); transform: translate(-50%, 16px); opacity: 0; padding: 10px 16px; border-radius: 12px; background: var(--ink); color: #fff; font-weight: 600; transition: transform 200ms var(--ease-out), opacity 200ms ease; pointer-events: none; }
.toast.on { transform: translate(-50%, 0); opacity: 1; }
@media (prefers-reduced-motion: reduce) { .toast { transition: none; } }
</style>
