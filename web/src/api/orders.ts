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

// ---- 单据详情与审核（P5-2；接口见开发规格 5.2“单据（Web）”）
export type OrderDetail = S['OrderDetail']
export type OrderRecords = S['Records']
export type OrderFlow = S['Flow']
export type NextPending = S['NextPending']
type UpdateForm = S['UpdateForm']

export const getOrder = (id: number) => http.get<OrderDetail>('/orders/' + id)
export const getRecords = (id: number) => http.get<OrderRecords>('/orders/' + id + '/records')
export const nextPending = (after: number) => http.get<NextPending>('/orders/next-pending', { after })

// 审核、回传、退回、标记、作废都返回同一份 Flow；回传失败不是接口错误，而是状态仍为 APPROVED 并带 pushError
export const approveOrder = (id: number) => http.post<OrderFlow>(`/orders/${id}/approve`)
export const pushOrder = (id: number) => http.post<OrderFlow>(`/orders/${id}/push`)
export const unapproveOrder = (id: number) => http.post<OrderFlow>(`/orders/${id}/unapprove`)
export const markPushed = (id: number, hsId: string) => http.post<OrderFlow>(`/orders/${id}/mark-pushed`, hsId ? { hsId } : {})
export const voidOrder = (id: number) => http.post<OrderFlow>(`/orders/${id}/void`)

export type HeadPatch = Partial<Pick<UpdateForm, 'bizDate' | 'vendorId' | 'productId' | 'specId' | 'warehouseId'>>

/**
 * 改表头：PUT 是整单覆盖（5.2），所以先取当前流水原样带回，只换要改的表头字段。
 * 流水的每个字段（手录记号、秤原读数、毛重、称重组号等）都照原样回传，这里不改任何一箱。
 */
export async function saveHead(d: OrderDetail, patch: HeadPatch) {
  const rec = await getRecords(d.id!)
  const boxes = (rec.boxes ?? []).map((b) => ({ ...b, seq: b.seq!, netKg: b.netKg! }))
  const body: UpdateForm = {
    bizDate: d.bizDate!, vendorId: d.vendor!.id!, productId: d.product!.id!, specId: d.spec!.id!, warehouseId: d.warehouse!.id!,
    ...patch,
    boxes,
  }
  return http.put<OrderDetail>('/orders/' + d.id, body)
}

/** 表头里的商品、仓库、农户候选：取自好生意档案（农户的 q 也匹配姓名与手机号） */
export const listArchive = (type: 'products' | 'warehouses' | 'vendors', q = '', pageSize = 100) =>
  http.get<{ items?: { id?: number; name?: string; phones?: string[] }[] }>('/archive/' + type, { q, pageSize }).then((r) => r.items ?? [])
