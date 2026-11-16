import { http } from './http'
import type { BasePriceDay, PriceItem, RuleView, Spec, SpecCreate, SpecPatch, TareBox, TareCreate, TarePatch } from './types'

// 规格与皮重（WEB-02、WEB-03）：改一个数、点一下开关都是 PUT 只带要改的字段
export const getSpecs = () => http.get<Spec[]>('/specs')
export const createSpec = (f: SpecCreate) => http.post<Spec>('/specs', f)
export const patchSpec = (id: number, f: SpecPatch) => http.put<Spec>('/specs/' + id, f)

export const getTares = () => http.get<TareBox[]>('/tares')
export const createTare = (f: TareCreate) => http.post<TareBox>('/tares', f)
export const patchTare = (id: number, f: TarePatch) => http.put<TareBox>('/tares/' + id, f)

// 每日基准价（WEB-04）
export const getPrices = (date: string) => http.get<BasePriceDay>('/base-prices', { date })
export const savePrice = (date: string, specId: number, price: number) =>
  http.put<PriceItem>('/base-prices', { date, specId, price })

// 规格页“规则 N 条”要的，只数不改
export const getRules = () => http.get<RuleView[]>('/rules')
