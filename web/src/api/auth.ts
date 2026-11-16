import { http } from './http'
import type { LoginResult } from './types'

export const login = (phone: string, password: string) =>
  http.post<LoginResult>('/auth/login', { phone, password, client: 'WEB' })
