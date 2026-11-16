<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppShell from './ui/AppShell.vue'
import ToastHost from './ui/ToastHost.vue'

const route = useRoute()
</script>

<template>
  <!-- 第一次跳转还没定下来时 matched 为空，先什么都不画，免得没登录就带着外框发请求 -->
  <RouterView v-if="route.meta.public" />
  <AppShell v-else-if="route.matched.length"><RouterView /></AppShell>
  <ToastHost />
</template>
