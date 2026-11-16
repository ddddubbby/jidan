import { ApiError, http } from './http'
import type { components } from './types.gen'

type S = components['schemas']
export type OptionView = S['OptionView']
export type Choice = S['Choice']

// 系统设置（业务设置那一组）与高级选项（设备与接口适配、作业策略）共用一套定义（开发规格 4.4）。
// 修改时 values 里值为 null 的项还原为默认值，没写到的项不动；响应是改完后的完整列表。
export type Values = Record<string, string | null>
type OptionList = { items?: OptionView[] }

export const getSettings = () => http.get<OptionList>('/settings').then((r) => r.items ?? [])
export const putSettings = (values: Values) => http.put<OptionList>('/settings', { values }).then((r) => r.items ?? [])
export const getOptions = () => http.get<OptionList>('/options').then((r) => r.items ?? [])
export const putOptions = (values: Values) => http.put<OptionList>('/options', { values }).then((r) => r.items ?? [])

/**
 * 待审核单张数，给“改取整规则时保存按钮写明影响几张”用。GET /orders/counts 属单据任务（P2-3），
 * 接口还没有时返回 null（按钮如实写“暂无法统计”），不编数字。
 */
export async function getPendingCount(): Promise<number | null> {
  try {
    const r = await http.get<{ pending?: number }>('/orders/counts')
    return typeof r.pending === 'number' ? r.pending : null
  } catch (e) {
    if (e instanceof ApiError && e.status !== 401) return null
    throw e
  }
}

/** 仅模拟模式：让下一次回传被拒绝，或没有答复（开发规格 11.4、5.2）。接口属 P2-4，没有时由调用处提示。 */
export type FailMode = 'reject' | 'no-reply'
export const failNext = (mode: FailMode) => http.post<unknown>('/dev/chanjet/fail-next', { mode })
