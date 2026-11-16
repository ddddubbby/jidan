import { ref } from 'vue'

// 提示条：一条一条显示 2.2 秒，新的顶掉旧的（原型 toast）
export const toastText = ref('')
export const toastOn = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

export function toast(text: string) {
  toastText.value = text
  toastOn.value = true
  clearTimeout(timer)
  timer = setTimeout(() => (toastOn.value = false), 2200)
}
