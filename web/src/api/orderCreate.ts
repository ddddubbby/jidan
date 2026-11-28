import { http } from './http'
import type { components } from './types.gen'

// 新建入库单（WEB-06）用到的接口：取批次号、计价预览、提交，以及选农户、仓库
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

const names = (type: 'warehouses') =>
  http.get<ArchiveList>('/archive/' + type, { pageSize: 100 }).then((r) => (r.items ?? []).map((x) => ({ id: x.id!, name: x.name ?? '' })))
export const listWarehouses = () => names('warehouses')

/** Web 新建单取批次号要带上收购日期（开发规格 8.2） */
export const getBatch = (date: string) => http.post<{ batchNo: string; date: string }>('/batches', { date })

/** 商品与规格取自规则（附录 E R-1 行） */
export interface QuoteReq {
  ruleId: number; date: string; boxNetJin: number[]
  /** 试算：把系数、每箱损耗换成这个值（单据里的定价规则抽屉用） */
  trialCoef?: number; trialLossJin?: number
}
export const quote = (r: QuoteReq) => http.post<QuoteResult>('/orders/quote', r)

export interface SubmitReq {
  batchNo: string; vendorId: number; ruleId: number; warehouseId: number
  boxes: { seq: number; netJin: number }[]
}
export const submitOrder = (r: SubmitReq) => http.post<Submitted>('/orders', r)
