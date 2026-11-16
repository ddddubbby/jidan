import { ApiError, http } from './http'
import type { components } from './types.gen'

// 定价规则相关接口（开发规格 5.2；P1-5 的细节见附录 E）
type S = components['schemas']
export type Kind = 'BUY' | 'SELL'
// 生成类型的字段全是可选的，这里收紧成后端实际一定会给的样子
export interface Target { id: number; name: string; tail4?: string | null }
export interface RuleView {
  id: number; name: string; kind: Kind; productId: number; productName: string; specId: number; specName: string
  coef: number; enabled: boolean; binds: Target[]; bindCount: number
}
export interface Spec { id: number; name: string; enabled: boolean }
export interface ArchiveRow { id: number; hsCode?: string; name: string; phones?: string[] }
export type RuleCreate = S['RuleCreate']
export type RulePatch = S['RulePatch']

export interface RuleQuery { kind?: Kind; productId?: number; specId?: number; q?: string }

/** RULE_CONFLICT 响应里的冲突项：谁已经绑在哪条规则上 */
export interface Conflict { vendorId: number; vendorName: string; ruleId: number; ruleName: string }

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
