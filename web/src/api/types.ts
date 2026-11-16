import type { components } from './types.gen'

// 生成类型的短名；字段以 api/openapi.json 为准（npm run gen:types 重新生成）
type S = components['schemas']
export type LoginResult = S['LoginResult']
export type Meta = S['Meta']
export type BasePriceDay = S['Day']
export type Spec = Required<Pick<S['Spec'], 'id' | 'name' | 'eggCount' | 'stdNetKg' | 'enabled'>>
export type TareBox = Required<Pick<S['TareBox'], 'id' | 'name' | 'tareKg' | 'hotkey' | 'enabled'>>
export type SpecPatch = S['SpecPatch']
export type TarePatch = S['TarePatch']
export type SpecCreate = S['SpecCreate']
export type TareCreate = S['TareCreate']
export type RuleView = S['RuleView']
export type PriceItem = S['Item']
