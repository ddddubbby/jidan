<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listAccounts, resetPassword, setAccountEnabled, type Account, type WithPassword } from '../../api/accounts'
import { errorText } from '../../api/http'
import ConfirmDialog from '../../ui/ConfirmDialog.vue'
import { fmtPhone } from '../../ui/phone'
import { toast } from '../../ui/toast'
import AccountDrawer from './AccountDrawer.vue'

// 原型 pageAccounts：磅秤员账号。初始密码与重置后的新密码由后端生成，只在这一次响应里出现，
// 这里用一个只有“完成”的提示框显示，可复制。
const accounts = ref<Account[]>([])
const loaded = ref(false)
const error = ref('')
const drawer = ref(false)

const resetting = ref<Account>()
const resetOpen = ref(false)
const shown = ref<{ title: string; name: string; phone: string; password: string }>()
const pwOpen = ref(false)
const copyState = ref<'' | 'ok' | 'fail'>('')

async function load() {
  try {
    accounts.value = await listAccounts()
    error.value = ''
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loaded.value = true
  }
}
onMounted(load)

function showPassword(title: string, a: Account | undefined, r: WithPassword) {
  shown.value = { title, name: a?.name ?? '', phone: a?.phone ?? '', password: r.password ?? '' }
  copyState.value = ''
  pwOpen.value = true
}

async function onCreated(r: WithPassword) {
  await load()
  showPassword(`已新增${r.account?.name ?? ''}`, r.account, r)
}

function askReset(a: Account) {
  resetting.value = a
  resetOpen.value = true
}

async function doReset() {
  const a = resetting.value
  if (!a?.id) return
  try {
    const r = await resetPassword(a.id)
    showPassword(`已重置${a.name}的密码`, a, r)
  } catch (e) {
    toast(errorText(e))
  }
}

async function toggle(a: Account) {
  if (!a.id) return
  try {
    const next = await setAccountEnabled(a.id, !a.enabled)
    Object.assign(a, next)
    toast(next.enabled ? `已启用${a.name}` : `已停用${a.name}，其 APP 登录失效`)
  } catch (e) {
    toast(errorText(e))
  }
}

// 部署在 http 的内网地址时没有 navigator.clipboard，退回选中文字再复制
async function copy() {
  const text = shown.value?.password ?? ''
  try {
    await navigator.clipboard.writeText(text)
    copyState.value = 'ok'
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try {
      copyState.value = document.execCommand('copy') ? 'ok' : 'fail'
    } catch {
      copyState.value = 'fail'
    }
    ta.remove()
  }
}

const when = (t?: string) => (t ? t.replace('T', ' ').slice(0, 16) : '—')
</script>

<template>
  <div class="top narrow">
    <div><h1>磅秤员账号</h1></div>
    <div class="acts"><button class="btn dark" @click="drawer = true">新增磅秤员</button></div>
  </div>

  <div v-if="error" class="result err narrow" role="alert">{{ error }}</div>

  <table class="t narrow">
    <thead>
      <tr><th>姓名</th><th>手机号（登录名）</th><th>最近登录</th><th>状态</th><th class="r"></th></tr>
    </thead>
    <tbody>
      <tr v-for="a in accounts" :key="a.id">
        <td><b>{{ a.name }}</b></td>
        <td class="num">{{ fmtPhone(a.phone ?? '') }}</td>
        <td class="num muted">{{ when(a.lastLoginAt) }}</td>
        <td><span class="pill" :class="{ ok: a.enabled }">{{ a.enabled ? '启用' : '已停用' }}</span></td>
        <td class="r nb">
          <button class="btn sm ghost" @click="askReset(a)">重置密码</button>
          <button class="btn sm ghost" @click="toggle(a)">{{ a.enabled ? '停用' : '启用' }}</button>
        </td>
      </tr>
      <tr v-if="loaded && !accounts.length">
        <td colspan="5" class="empty">
          还没有磅秤员账号
          <div style="margin-top: 12px"><button class="btn primary" @click="drawer = true">新增磅秤员</button></div>
        </td>
      </tr>
    </tbody>
  </table>

  <AccountDrawer v-model:open="drawer" :accounts="accounts" @created="onCreated" />

  <ConfirmDialog v-model:open="resetOpen" :title="`重置${resetting?.name ?? ''}的密码？`" confirm-text="重置密码" tone="dark" @confirm="doReset">
    <p class="muted" style="margin: 10px 0 0">{{ resetting?.name }}正在用的 APP 会退出登录，旧密码不能再用</p>
  </ConfirmDialog>

  <ConfirmDialog v-model:open="pwOpen" :title="shown?.title ?? ''" confirm-text="完成" hide-cancel>
    <p class="muted" style="margin: 6px 0 0">{{ shown?.name }} · <span class="num">{{ fmtPhone(shown?.phone ?? '') }}</span></p>
    <div class="pwshow">
      <b data-testid="initial-password">{{ shown?.password }}</b>
      <button class="btn sm" @click="copy">{{ copyState === 'ok' ? '✓ 已复制' : copyState === 'fail' ? '复制失败，请手动抄下' : '复制' }}</button>
    </div>
    <p class="muted" style="margin: 10px 0 0; font-size: 13px">只显示这一次</p>
  </ConfirmDialog>
</template>

<style>
.pwshow { display: flex; align-items: center; gap: 12px; margin: 12px 0 0; flex-wrap: wrap; }
.pwshow b { font-size: 34px; letter-spacing: 0.12em; font-variant-numeric: tabular-nums; }
</style>
