import type { components } from './types.gen'

// 生成类型的短名；字段以 api/openapi.json 为准（npm run gen:types 重新生成）
type S = components['schemas']
export type LoginResult = S['LoginResult']
export type Meta = S['Meta']
export type BasePriceDay = S['Day']
