<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMeta } from '../stores/meta'
import { useSession } from '../stores/session'

// 与原型 NAV 一致：'-' 开头是分组标题
const NAV: [string, string][] = [
  ['-', '日常'], ['orders', '采购入库单'], ['prices', '每日基准价'],
  ['-', '配置'], ['rules', '定价规则'], ['basics', '规格与皮重'],
  ['-', '系统'], ['archive', '好生意档案'], ['accounts', '磅秤员账号'], ['settings', '系统设置'],
]

const route = useRoute()
const router = useRouter()
const meta = useMeta()
const session = useSession()

// 详情页、新建页都算在“采购入库单”下
const current = computed(() => String(route.meta.nav ?? ''))

// 换页时重取导航提示，上一页改过的数据（如录完基准价）立刻反映到这里；失败了就保留旧值，不打扰
watch(() => route.fullPath, () => meta.refresh().catch(() => {}), { immediate: true })

function logout() {
  session.clear()
  router.replace('/login')
}
</script>

<template>
  <div class="shell">
    <nav class="side" aria-label="主导航">
      <div class="brand"><span class="mark" aria-hidden="true"></span><span><b>收蛋</b><small>管理端</small></span></div>
      <div class="nav">
        <template v-for="[k, t] in NAV" :key="k === '-' ? t : k">
          <div v-if="k === '-'" class="grp">{{ t }}</div>
          <button v-else :aria-current="current === k ? 'page' : undefined" @click="router.push('/' + k)">
            <span>{{ t }}</span>
            <span v-if="k === 'orders' && (meta.hasFailed || meta.pending)" class="badges">
              <span v-if="meta.hasFailed" class="reddot" title="有回传失败"></span>
              <span v-if="meta.pending" class="cnt">{{ meta.pending }}</span>
            </span>
            <span v-else-if="k === 'prices' && meta.pricesMissing" class="ambdot" title="今日基准价未录齐"></span>
          </button>
        </template>
      </div>
      <div class="me">
        <b>{{ session.name }}</b><span class="book" :title="meta.book">{{ meta.book }}</span>
        <button @click="logout">退出登录</button>
      </div>
    </nav>
    <main class="main" id="main"><slot /></main>
  </div>
</template>

<style>
.shell { display: grid; grid-template-columns: var(--side) minmax(0, 1fr); min-height: 100vh;
  background: linear-gradient(to right, var(--surface) var(--side), var(--line) var(--side), var(--line) calc(var(--side) + 1px), var(--bg) calc(var(--side) + 1px)); }
.side { position: sticky; top: 0; height: 100vh; overflow-y: auto; display: flex; flex-direction: column; padding: 18px 12px; }
.side .me .book { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.brand { display: flex; align-items: center; gap: 10px; padding: 4px 10px 18px; }
.brand .mark { width: 26px; height: 32px; border-radius: 50% 50% 46% 46% / 60% 60% 40% 40%; background: var(--accent); }
.brand b { font-size: 19px; letter-spacing: 0.04em; }
.brand small { display: block; font-size: 13px; color: var(--ink-2); letter-spacing: 0; }
.nav { display: grid; gap: 2px; }
.nav button { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 40px; padding: 0 12px; border: 0; border-radius: 10px; background: none; text-align: left; font-size: 15px; color: var(--ink-2); }
.nav button:hover { background: var(--sunken); color: var(--ink); }
.nav button[aria-current="page"] { background: var(--ink); color: #fff; }
.nav .grp { margin: 14px 12px 4px; font-size: 13px; color: var(--ink-3); }
.nav .badges { display: inline-flex; align-items: center; gap: 6px; }
.nav button[aria-current="page"] .cnt { background: rgba(255,255,255,.18); color: #fff; }
.side .me { margin-top: auto; padding: 12px; border-top: 1px solid var(--line); font-size: 13px; color: var(--ink-2); }
.side .me b { display: block; font-size: 15px; color: var(--ink); }
.side .me button { margin-top: 6px; padding: 0; border: 0; background: none; color: var(--ink-2); font-size: 13px; text-decoration: underline; }

.main { min-width: 0; padding: 24px 32px 80px; overflow-x: clip; }
@media (max-width: 1100px) {
  .main { padding: 20px 20px 80px; }
}
</style>
