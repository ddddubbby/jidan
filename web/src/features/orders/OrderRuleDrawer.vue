<script setup lang="ts">
import type { OrderDetail } from '../../api/orders'
import Drawer from '../../ui/Drawer.vue'

// 单据里的定价规则抽屉的入口（原型 drawerOrderRule，WEB-07）。P5-2 只留好挂载点：“定价规则”按钮与
// 缺规则时的“绑定收购规则”都打开它，改动后 emit changed 让页面重取单据。调阅、更换、改系数由 P5-3 填进来。
defineProps<{ order: OrderDetail }>()
defineEmits<{ changed: [] }>()
const open = defineModel<boolean>('open', { default: false })
</script>

<template>
  <Drawer v-model:open="open" :title="order.pricing?.ruleName ?? '选择收购规则'" :sub="`${order.vendor?.name} · ${order.product?.name} ${order.spec?.name}`">
    <p class="muted">定价规则在 P5-3 实现。</p>
  </Drawer>
</template>
