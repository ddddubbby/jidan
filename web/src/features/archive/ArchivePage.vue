<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getArchive, getArchiveStatus, syncArchive, type ArchivePage, type ArchiveStatus, type ArchiveType } from '../../api/archive'
import { errorText } from '../../api/http'
import { fmtPhone } from '../../ui/phone'
import { toast } from '../../ui/toast'
import '../../styles/settings.css'

// 原型 pageArchive：四类档案分页检索，手动“从好生意同步”。同步失败时保留旧数据并把原因摆在页面上。
const tabs: { key: ArchiveType; label: string; count: keyof ArchiveStatus }[] = [
  { key: 'vendors', label: '供应商（农户）', count: 'vendors' },
  { key: 'products', label: '商品', count: 'products' },
  { key: 'customers', label: '客户', count: 'customers' },
  { key: 'warehouses', label: '仓库', count: 'warehouses' },
]

const tab = ref<ArchiveType>('vendors')
const q = ref('')
const page = ref(1)
const data = ref<ArchivePage>()
const status = ref<ArchiveStatus>()
const loadError = ref('')
const syncError = ref('')
const syncing = ref(false)

let seq = 0
async function load() {
  const mine = ++seq
  try {
    const res = await getArchive(tab.value, q.value.trim(), page.value)
    if (mine === seq) {
      data.value = res
      loadError.value = ''
    }
  } catch (e) {
    if (mine === seq) loadError.value = errorText(e)
  }
}

async function loadStatus() {
  try {
    status.value = await getArchiveStatus()
  } catch (e) {
    loadError.value = errorText(e)
  }
}

let timer: ReturnType<typeof setTimeout> | undefined
watch(q, () => {
  clearTimeout(timer)
  timer = setTimeout(() => {
    page.value = 1
    load()
  }, 250)
})

function pick(k: ArchiveType) {
  if (k === tab.value) return
  tab.value = k
  q.value = ''
  page.value = 1
  data.value = undefined
  load()
}

function go(p: number) {
  page.value = p
  load()
}

async function sync() {
  syncing.value = true
  syncError.value = ''
  try {
    status.value = await syncArchive()
    await load()
    toast('已同步')
  } catch (e) {
    // 后端的失败提示本身就写明“保留了上次的数据”和好生意给的原因
    syncError.value = errorText(e)
  } finally {
    syncing.value = false
  }
}

onMounted(() => {
  loadStatus()
  load()
})

const everSynced = computed(() => !!status.value?.lastSyncedAt)
const lastSync = computed(() => (status.value?.lastSyncedAt ? status.value.lastSyncedAt.replace('T', ' ').slice(0, 16) : ''))
const pages = computed(() => Math.max(1, Math.ceil((data.value?.total ?? 0) / (data.value?.pageSize || 20))))
const isVendors = computed(() => tab.value === 'vendors')
const isProducts = computed(() => tab.value === 'products')
const cols = computed(() => 2 + (isVendors.value || isProducts.value ? 1 : 0))
</script>

<template>
  <div class="top">
    <div><h1>好生意档案</h1></div>
    <div class="acts">
      <span class="muted" style="font-size: 13px">{{ everSynced ? '上次同步 ' + lastSync : '还没有同步过' }}</span>
      <button v-if="syncing" class="btn" disabled><span class="spin"></span> 正在同步</button>
      <button v-else class="btn" :class="everSynced ? 'dark' : 'primary'" @click="sync">从好生意同步</button>
    </div>
  </div>

  <div v-if="syncError" class="result err" role="alert"><b>同步失败</b>{{ syncError }}</div>
  <div v-if="loadError" class="result err" role="alert">{{ loadError }}</div>

  <div class="tabs">
    <button v-for="t in tabs" :key="t.key" :aria-pressed="tab === t.key" @click="pick(t.key)">
      {{ t.label }}<span class="cnt">{{ status?.[t.count] ?? '' }}</span>
    </button>
  </div>
  <div class="bar"><input v-model="q" class="search" placeholder="名称、编码或手机号" aria-label="搜索档案" /></div>

  <table class="t">
    <thead>
      <tr>
        <th>名称</th><th>好生意编码</th>
        <th v-if="isVendors">联系方式</th>
        <th v-if="isProducts">单位</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="r in data?.items ?? []" :key="r.id">
        <td class="name" :title="r.name"><span class="nm">{{ r.name }}</span></td>
        <td class="num">{{ r.hsCode }}</td>
        <td v-if="isVendors" class="num"><template v-for="(p, i) in r.phones ?? []" :key="p"><br v-if="i" />{{ fmtPhone(p) }}</template></td>
        <td v-if="isProducts">{{ r.unitName }}</td>
      </tr>
      <tr v-if="data && !data.items?.length">
        <td :colspan="cols" class="empty">
          <template v-if="q.trim()">没有匹配的档案</template>
          <template v-else-if="!everSynced">
            还没有档案，先同步一次
            <div style="margin-top: 12px"><button class="btn primary" :disabled="syncing" @click="sync">从好生意同步</button></div>
          </template>
          <template v-else>这一类档案是空的</template>
        </td>
      </tr>
    </tbody>
  </table>

  <div v-if="pages > 1" class="pager">
    <button class="btn sm" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
    <span class="muted num">第 {{ page }} / {{ pages }} 页 · 共 {{ data?.total }} 条</span>
    <button class="btn sm" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
  </div>
</template>

<style>
.pager { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 14px; }
</style>
