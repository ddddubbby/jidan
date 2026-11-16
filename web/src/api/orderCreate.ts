import { http } from './http'
import type { components } from './types.gen'
import type { RuleView } from './rules'

// 新建入库单（WEB-06）用到的接口：取批次号、计价预览、提交，以及选农户、商品、仓库、绑规则
type S = components['schemas']
export type QuoteResult = S['Result']
export type Submitted = S['Submitted']
export type VendorRow = { id: number; name: string; phones: string[] }
export type NamedRow = { id: number; name: string }

type ArchiveList = { items?: { id?: number; name?: string; phones?: string[] }[] }

/** 农户检索：名称或任一手机号匹配 */
export const searchVendors = (q: string) =>
  http.get<ArchiveList>('/archive/vendors', { q, pageSize: 8 }).then((r) =>
    (r.items ?? []).map((x) => ({ id: x.id!, name: x.name ?? '', phones: x.phones ?? [] })),
  )

const names = (type: 'products' | 'warehouses') =>
  http.get<ArchiveList>('/archive/' + type, { pageSize: 100 }).then((r) => (r.items ?? []).map((x) => ({ id: x.id!, name: x.name ?? '' })))
export const listProducts = () => names('products')
export const listWarehouses = () => names('warehouses')

/** 该农户已绑定的规则；只留启用的收购规则 */
export const vendorBuyRules = (vendorId: number) =>
  http.get<RuleView[]>(`/farmers/${vendorId}/rules`).then((l) => l.filter((r) => r.kind === 'BUY' && r.enabled))

/** 把农户在某商品规格上的收购规则设为指定规则 */
export const bindVendorRule = (vendorId: number, productId: number, specId: number, ruleId: number) =>
  http.put<RuleView[]>(`/farmers/${vendorId}/rule-binding`, { productId, specId, ruleId })

/** Web 新建单取批次号要带上收购日期（开发规格 8.2） */
export const getBatch = (date: string) => http.post<{ batchNo: string; date: string }>('/batches', { date })

export interface QuoteReq {
  vendorId: number; productId: number; specId: number; date: string; boxNetKg: number[]
  /** 试算：换成这条规则 / 把系数换成这个值（单据里的定价规则抽屉用） */
  trialRuleId?: number; trialCoef?: number
}
export const quote = (r: QuoteReq) => http.post<QuoteResult>('/orders/quote', r)

export interface SubmitReq {
  batchNo: string; vendorId: number; productId: number; specId: number; warehouseId: number
  boxes: { seq: number; netKg: number }[]
}
export const submitOrder = (r: SubmitReq) => http.post<Submitted>('/orders', r)
