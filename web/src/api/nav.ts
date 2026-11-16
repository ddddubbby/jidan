import { http } from './http'
import type { BasePriceDay, Meta, OrderCounts } from './types'

// 导航与侧栏用到的只读接口
export const getMeta = () => http.get<Meta>('/meta')
export const getTodayPrices = () => http.get<BasePriceDay>('/base-prices')
export const getOrderCounts = () => http.get<OrderCounts>('/orders/counts')
