import { http } from './http'
import type { components } from './types.gen'

// Token 安全级别“高”（ticket 方式）：appTicket 最近收到的时间、现在能不能向好生意发请求（P8-5）
export type ChanjetStatus = components['schemas']['ChanjetStatus']
export const getChanjetStatus = () => http.get<ChanjetStatus>('/chanjet/status')

/** “3 分钟前”这类说法；没收到过返回空串 */
export function agoText(iso?: string, now = Date.now()): string {
  if (!iso) return ''
  const min = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 60000))
  if (min < 1) return '刚刚'
  if (min < 60) return min + ' 分钟前'
  const h = Math.floor(min / 60)
  return h < 24 ? h + ' 小时前' : Math.floor(h / 24) + ' 天前'
}
