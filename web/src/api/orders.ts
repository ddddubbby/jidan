import { http } from './http'
import type { components } from './types.gen'

type S = components['schemas']
export type OrderRow = S['OrderRow']
export type OrderPage = S['PageResultOrderRow']

// 列表页签（开发规格 5.2、8.2）：failed 只含带回传失败原因的，unsent 是全部已审核未回传的（push.on-approve = manual 时的“待回传”）
export type OrderTab = 'pending' | 'failed' | 'unsent' | 'sent' | 'all'

export const listOrders = (tab: OrderTab, q: string, page: number, pageSize: number) =>
  http.get<OrderPage>('/orders', { tab, q: q.trim() || undefined, page, pageSize })

/** 某天收购的待审核单按规格名计数（基准价页状态列的“N 张待审核在等”）；列表接口一页最多 100 条，翻页取全 */
export async function pendingBySpec(date: string): Promise<Record<string, number>> {
  const out: Record<string, number> = {}
  for (let page = 1; ; page++) {
    const r = await listOrders('pending', '', page, 100)
    for (const o of r.items ?? []) if (o.bizDate === date && o.specName) out[o.specName] = (out[o.specName] ?? 0) + 1
    if (page * 100 >= (r.total ?? 0)) return out
  }
}
