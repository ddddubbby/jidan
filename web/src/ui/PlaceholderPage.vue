<script setup lang="ts">
import { useRoute } from 'vue-router'

// 各功能页做出来之前的空页面：只有标题
const route = useRoute()
</script>

<template>
  <div class="top"><div><h1>{{ route.meta.title }}</h1></div></div>
</template>
