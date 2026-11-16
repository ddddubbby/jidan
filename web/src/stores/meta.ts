import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMeta, getOrderCounts, getTodayPrices } from '../api/nav'

// 导航上的提示：数字只表示待审核；有回传失败另加红点；今日基准价没录全另加琥珀点（v7 4.5-3，原型 navBadge）。
// 琥珀点取自 GET /base-prices（各启用规格当日价为空即未录全）。
// 待审核数与回传失败取自 GET /orders/counts（P2-3）。
export const useMeta = defineStore('meta', () => {
  const book = ref('')
  const pending = ref(0)
  const hasFailed = ref(false)
  const pricesMissing = ref(false)

  async function refresh() {
    const [m, day, counts] = await Promise.all([getMeta(), getTodayPrices(), getOrderCounts()])
    book.value = m.bookName ?? ''
    pending.value = counts.pending ?? 0
    hasFailed.value = (counts.failed ?? 0) > 0
    pricesMissing.value = (day.items ?? []).some((i) => i.price == null)
  }
  return { book, pending, hasFailed, pricesMissing, refresh }
})
