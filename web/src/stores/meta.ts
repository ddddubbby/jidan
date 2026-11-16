import { defineStore } from 'pinia'
import { ref } from 'vue'

// 导航上的提示：数字只表示待审核；有回传失败、基准价未录齐另加红点（原型 navBadge）。
// 数据来源接口在 P4-1 接入。
export const useMeta = defineStore('meta', () => {
  const pending = ref(0)
  const hasFailed = ref(false)
  const pricesMissing = ref(false)
  return { pending, hasFailed, pricesMissing }
})
