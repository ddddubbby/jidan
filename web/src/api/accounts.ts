import { http } from './http'
import type { components } from './types.gen'

type S = components['schemas']
export type Account = S['AccountView']
export type WithPassword = S['WithPassword']

export const listAccounts = () => http.get<Account[]>('/accounts')
export const createAccount = (name: string, phone: string) => http.post<WithPassword>('/accounts', { name, phone })
export const resetPassword = (id: number) => http.post<WithPassword>(`/accounts/${id}/reset-password`)
export const setAccountEnabled = (id: number, enabled: boolean) => http.put<Account>(`/accounts/${id}/enabled`, { enabled })
