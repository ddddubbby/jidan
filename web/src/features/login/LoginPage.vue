<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../../api/auth'
import { errorText } from '../../api/http'
import { useSession } from '../../stores/session'

// 原型 pageLogin：按钮按“还缺什么”依次写成“填写手机号”“填写密码”，点了直达那一格
const route = useRoute()
const router = useRouter()
const session = useSession()

const phone = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)
const phoneEl = ref<HTMLInputElement>()
const pwEl = ref<HTMLInputElement>()

const step = computed(() => (busy.value ? 'busy' : phone.value.length < 11 ? 'phone' : !password.value ? 'pw' : 'go'))

function onPhone() {
  phone.value = phone.value.replace(/\D/g, '').slice(0, 11)
  error.value = ''
}

async function submit() {
  busy.value = true
  error.value = ''
  try {
    session.set(await login(phone.value, password.value))
    // 只回到本站的页面，不接受 //、http:// 这类跳去别处的写法
    const to = String(route.query.redirect ?? '')
    await router.replace(to.startsWith('/') && !to.startsWith('//') ? to : '/')
  } catch (e) {
    error.value = errorText(e)
    password.value = ''
    pwEl.value?.focus()
  } finally {
    busy.value = false
  }
}

function next() {
  if (step.value === 'phone') phoneEl.value?.focus()
  else if (step.value === 'pw') pwEl.value?.focus()
  else if (step.value === 'go') submit()
}
</script>

<template>
  <div class="login">
    <form class="card" @submit.prevent="next">
      <div class="brand"><span class="mark" aria-hidden="true"></span><span><b>收蛋</b><small>管理端</small></span></div>
      <div class="formgrid">
        <label class="field">
          <span>手机号</span>
          <input ref="phoneEl" v-model="phone" class="in num" :class="{ bad: error }" inputmode="numeric" autocomplete="username" autofocus @input="onPhone" />
        </label>
        <label class="field">
          <span>密码</span>
          <input ref="pwEl" v-model="password" class="in" :class="{ bad: error }" type="password" autocomplete="current-password" @input="error = ''" />
        </label>
        <p class="inline-err" role="alert">{{ error }}</p>
        <button v-if="step === 'busy'" type="button" class="btn primary lg" disabled><span class="spin"></span> 登录中</button>
        <button v-else-if="step === 'phone'" type="button" class="btn todo lg" @click="phoneEl?.focus()">填写手机号</button>
        <button v-else-if="step === 'pw'" type="button" class="btn todo lg" @click="pwEl?.focus()">填写密码</button>
        <button v-else type="submit" class="btn primary lg">登录</button>
      </div>
    </form>
  </div>
</template>

<style>
.login { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
.login .card { width: min(400px, 100%); padding: 32px; }
.login .brand { padding: 0 0 24px; }
.login .inline-err { margin: -6px 0 0; min-height: 20px; }
.formgrid { display: grid; gap: 16px; }
.formgrid .btn.lg { width: 100%; }
</style>
