<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { createAccount, type Account, type WithPassword } from '../../api/accounts'
import { errorText } from '../../api/http'
import Drawer from '../../ui/Drawer.vue'

// 原型 drawerAccount：新增磅秤员。保存按钮依次写明还缺什么，点了直达那一格。
const props = defineProps<{ accounts: Account[] }>()
const open = defineModel<boolean>('open', { default: false })
const emit = defineEmits<{ created: [r: WithPassword] }>()

const name = ref('')
const phone = ref('')
const error = ref('')
const busy = ref(false)
const nameEl = ref<HTMLInputElement>()
const phoneEl = ref<HTMLInputElement>()

watch(open, (o) => {
  if (o) {
    name.value = ''
    phone.value = ''
    error.value = ''
  }
})

const step = computed(() => {
  if (busy.value) return 'busy'
  if (!name.value.trim()) return 'name'
  if (!/^1\d{10}$/.test(phone.value)) return 'phone'
  if (props.accounts.some((a) => a.phone === phone.value)) return 'dup'
  return 'go'
})

function onPhone() {
  phone.value = phone.value.replace(/\D/g, '').slice(0, 11)
  error.value = ''
}

async function save() {
  busy.value = true
  error.value = ''
  try {
    const r = await createAccount(name.value.trim(), phone.value)
    open.value = false
    emit('created', r)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Drawer v-model:open="open" title="新增磅秤员">
    <form class="formgrid" @submit.prevent="step === 'go' ? save() : step === 'name' ? nameEl?.focus() : phoneEl?.focus()">
      <label class="field">
        <span>姓名</span>
        <input ref="nameEl" v-model="name" class="in" autocomplete="off" autofocus @input="error = ''" />
      </label>
      <label class="field">
        <span>手机号（登录名）</span>
        <input ref="phoneEl" v-model="phone" class="in num" :class="{ bad: step === 'dup' }" inputmode="numeric" autocomplete="off" @input="onPhone" />
      </label>
      <p v-if="error" class="inline-err" role="alert">{{ error }}</p>
    </form>
    <template #foot>
      <button v-if="step === 'busy'" class="btn primary" disabled><span class="spin"></span> 正在新增</button>
      <button v-else-if="step === 'name'" class="btn todo" @click="nameEl?.focus()">填写姓名</button>
      <button v-else-if="step === 'phone'" class="btn todo" @click="phoneEl?.focus()">填写 11 位手机号</button>
      <button v-else-if="step === 'dup'" class="btn fix" @click="phoneEl?.focus()">这个手机号已有账号</button>
      <button v-else class="btn primary" @click="save">新增并生成密码</button>
    </template>
  </Drawer>
</template>
