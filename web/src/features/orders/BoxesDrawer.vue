<script setup lang="ts">
import type { OrderDetail } from '../../api/orders'
import Drawer from '../../ui/Drawer.vue'

// 逐箱流水抽屉的入口（原型 drawerBoxes，WEB-07）。P5-2 只留好挂载点：详情页的按钮打开它，
// 改动后 emit changed 让页面重取单据。同重归类、只看手录、改净重、删除由 P5-3 填进来。
defineProps<{ order: OrderDetail }>()
defineEmits<{ changed: [] }>()
const open = defineModel<boolean>('open', { default: false })
</script>

<template>
  <Drawer v-model:open="open" :title="`逐箱流水 · ${order.pricing?.boxCount ?? 0} 箱`" :sub="order.batchNo">
    <p class="muted">逐箱流水在 P5-3 实现。</p>
  </Drawer>
</template>
