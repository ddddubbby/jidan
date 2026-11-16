import { ref } from 'vue'

// 提示条：一条一条显示，新的顶掉旧的（原型 toast）。短提示 2.2 秒；报错很长时按字数多留一会儿，最多 8 秒，免得没读完就消失
export const toastText = ref('')
export const toastOn = ref(false)
/** 每来一条新提示加一，提示条据此重新放到最上层 */
export const toastSeq = ref(0)
let timer: ReturnType<typeof setTimeout> | undefined

export function toast(text: string) {
  toastText.value = text
  toastSeq.value++
  toastOn.value = true
  clearTimeout(timer)
  timer = setTimeout(() => (toastOn.value = false), Math.min(8000, Math.max(2200, 1200 + text.length * 70)))
}
