import { http } from './http'
import type { components } from './types.gen'

type S = components['schemas']
export type ArchiveRow = S['ArchiveRow']
export type ArchiveStatus = S['Status']
export type ArchivePage = S['PageResultArchiveRow']

// 四类档案：路径里的 type 取这四个值（开发规格 5.2）
export type ArchiveType = 'vendors' | 'products' | 'customers' | 'warehouses'

export const getArchive = (type: ArchiveType, q: string, page: number) => http.get<ArchivePage>('/archive/' + type, { q, page })
export const getArchiveStatus = () => http.get<ArchiveStatus>('/archive/status')
export const syncArchive = () => http.post<ArchiveStatus>('/archive/sync')
