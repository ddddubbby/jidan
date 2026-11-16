import { defineStore } from 'pinia'
import { ref } from 'vue'

// 登录人。登录页与 /api/auth 接口在 P4-1 接入，此前一直是未登录
export const useSession = defineStore('session', () => {
  const name = ref('')
  const book = ref('')
  function logout() {
    name.value = ''
    book.value = ''
  }
  return { name, book, logout }
})
