import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { LoginResult } from '../api/types'

const KEY = 'egg.session'

// 登录信息存在浏览器本地；隐私窗口等读写不了时就只在本次页面里有效
function load(): Partial<LoginResult> {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? '{}')
  } catch {
    return {}
  }
}

function save(v: Partial<LoginResult> | null) {
  try {
    if (v) localStorage.setItem(KEY, JSON.stringify(v))
    else localStorage.removeItem(KEY)
  } catch {
    // 存不了就算了
  }
}

// 登录人。退出登录只清本地，不调后端（开发规格 7.2）
export const useSession = defineStore('session', () => {
  const saved = load()
  const token = ref(saved.token ?? '')
  const name = ref(saved.name ?? '')
  const loggedIn = computed(() => !!token.value)

  function set(r: LoginResult) {
    token.value = r.token ?? ''
    name.value = r.name ?? ''
    save({ token: token.value, name: name.value })
  }
  function clear() {
    token.value = ''
    name.value = ''
    save(null)
  }
  return { token, name, loggedIn, set, clear }
})
