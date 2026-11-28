import { ApiError, http } from './http'
import type { components } from './types.gen'

// 定价规则相关接口（开发规格 5.2；P1-5 的细节见附录 E）
type S = components['schemas']
export type Kind = 'BUY' | 'SELL'
// 生成类型的字段全是可选的，这里收紧成后端实际一定会给的样子。
// 规则不绑农户或客户，建单时按名称选；一条规则打包了商品、规格、包装箱（皮重）与每箱损耗调整（附录 E R-1 行）。
export interface RuleView {
  id: number; name: string; kind: Kind; productId: number; productName: string; specId: number; specName: string
  stdNetJin: number; tareBoxId: number | null; tareName: string | null; tareJin: number | null
  /** 每箱损耗调整（斤），正数加、负数减 */
  lossJin: number
  coef: number; enabled: boolean
}
export interface Spec { id: number; name: string; enabled: boolean }
export interface ArchiveRow { id: number; hsCode?: string; name: string; phones?: string[] }
export type RuleCreate = S['RuleCreate']
export type RulePatch = S['RulePatch']

export interface RuleQuery { kind?: Kind; productId?: number; specId?: number; enabled?: boolean; q?: string }

/** RULE_CONFLICT 响应里的冲突项：与要保存的规则重名的那一条 */
export interface Conflict { ruleId: number; ruleName: string }

export const listRules = (query: RuleQuery = {}) => http.get<RuleView[]>('/rules', { ...query })
export const createRule = (body: RuleCreate) => http.post<RuleView>('/rules', body)
export const updateRule = (id: number, body: RulePatch) => http.put<RuleView>('/rules/' + id, body)
export const listSpecs = () => http.get<Spec[]>('/specs')
/** 商品、农户（vendors）、客户（customers）的档案检索；农户的 q 也匹配手机号 */
export const searchArchive = (type: 'products' | 'vendors' | 'customers', q = '', pageSize = 100) =>
  http.get<{ items?: ArchiveRow[] }>('/archive/' + type, { q, pageSize }).then((r) => r.items ?? [])

/** 从 409 响应里取冲突项；不是 RULE_CONFLICT 返回 null */
export function conflictsOf(e: unknown): Conflict[] | null {
  if (!(e instanceof ApiError) || e.code !== 'RULE_CONFLICT') return null
  const list = (e.body as { conflicts?: Conflict[] } | undefined)?.conflicts
  return Array.isArray(list) ? list : []
}

export const kindText = (k: string) => (k === 'BUY' ? '收购' : '发货')
export const fmtPhone = (p: string) => (/^\d{11}$/.test(p) ? `${p.slice(0, 3)} ${p.slice(3, 7)} ${p.slice(7)}` : p)
/** 每箱损耗调整的写法：+0.5 斤、−1 斤、0 显示“不调整” */
export const lossText = (v: number | null | undefined) =>
  !v ? '不调整' : `${v > 0 ? '+' : '−'}${Math.abs(v)} 斤`
/** 一行写清规则打包了什么：粉蛋 360枚/箱 · 2号包装箱 4 斤 · 每箱 −1 斤 */
export const packText = (r: RuleView) =>
  `${r.productName} ${r.specName} · ${r.tareName ? `${r.tareName} ${r.tareJin} 斤` : '未设包装箱'}${r.lossJin ? ` · 每箱 ${lossText(r.lossJin)}` : ''}`
