<script setup lang="ts">
import { Dialog, DialogBackdrop, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@ui-sdk/vue/dialog'

// 右侧抽屉（原型 drawer）。焦点、Esc、点遮罩关闭取自 ui-sdk 的 Dialog，外观是原型的。
// 用法：<Drawer v-model:open="x" title="…" sub="…">正文<template #foot>按钮</template></Drawer>
defineProps<{ title: string; sub?: string; footClass?: string }>()
const open = defineModel<boolean>('open', { default: false })
</script>

<template>
  <Dialog v-model:open="open">
    <DialogBackdrop class="scrim" />
    <DialogContent class="drawer">
      <div class="hd">
        <div>
          <DialogTitle>{{ title }}</DialogTitle>
          <DialogDescription v-if="sub">{{ sub }}</DialogDescription>
        </div>
        <DialogClose class="btn ghost">关闭</DialogClose>
      </div>
      <div class="bd"><slot /></div>
      <div v-if="$slots.foot" class="ft" :class="footClass"><slot name="foot" /></div>
    </DialogContent>
  </Dialog>
</template>

<style>
.scrim { position: fixed; inset: 0; background: rgba(10,14,16,.32); opacity: 1; transition: opacity 250ms ease; }
.scrim[data-starting-style], .scrim[data-ending-style] { opacity: 0; }
.scrim[data-ending-style] { transition-duration: 200ms; }

/* <dialog> 默认的居中、边框、内边距全部去掉，贴右边铺满高度 */
.drawer { position: fixed; inset: 0 0 0 auto; width: min(560px, 100vw); max-width: none; height: 100vh; max-height: none; margin: 0; padding: 0; border: 0; display: flex; flex-direction: column; background: var(--surface); color: var(--ink); box-shadow: -12px 0 32px rgba(0,0,0,.12); transform: none; transition: transform 250ms var(--ease-drawer); }
.drawer[data-starting-style], .drawer[data-ending-style] { transform: translateX(100%); }
.drawer[data-ending-style] { transition-duration: 200ms; }
.drawer::backdrop { background: none; }
.drawer .hd { flex: none; display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 20px 22px 14px; border-bottom: 1px solid var(--line); }
.drawer .hd h2 { font-size: 19px; line-height: 1.35; overflow-wrap: anywhere; }
.drawer .hd p { margin: 2px 0 0; font-size: 13px; color: var(--ink-2); }
.drawer .bd { flex: 1; overflow-y: auto; padding: 18px 22px 28px; overscroll-behavior: contain; }
.drawer .bd .t th { top: -18px; }
.drawer .ft { flex: none; display: flex; gap: 10px; justify-content: flex-end; align-items: center; padding: 14px 22px; border-top: 1px solid var(--line); background: var(--surface); }
.drawer .ft.stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; background: #fafbf9; }
.ft.stats .k { display: block; font-size: 13px; color: var(--ink-2); }
.ft.stats .v { font-size: 17px; font-weight: 700; font-variant-numeric: tabular-nums; }
.ft.stats .v s { color: var(--ink-3); font-weight: 600; margin-right: 4px; }
@media (prefers-reduced-motion: reduce) { .scrim, .drawer { transition: none !important; } }
</style>
