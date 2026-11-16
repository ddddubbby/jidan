import { router } from '../router'
import { useSession } from '../stores/session'

// 后端统一错误格式（开发规格 5.2）：{ code, message }；conflicts 只有 RULE_CONFLICT 带
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public body?: Record<string, unknown>,
  ) {
    super(message)
  }
}

type Query = Record<string, string | number | boolean | undefined | null>

async function request<T>(method: string, path: string, body?: unknown, query?: Query): Promise<T> {
  const session = useSession()
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(query ?? {})) if (v != null && v !== '') qs.set(k, String(v))
  const url = '/api' + path + (qs.size ? '?' + qs : '')

  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (session.token) headers.Authorization = 'Bearer ' + session.token

  let res: Response
  try {
    res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new ApiError(0, 'NETWORK', '连不上服务器，请检查网络后重试')
  }

  const text = await res.text()
  const data = text ? safeJson(text) : undefined
  if (res.ok) return data as T

  const err = (data ?? {}) as { code?: string; message?: string }
  const code = err.code ?? 'INTERNAL'
  // 登录失效：清掉本地登录并回登录页（登录接口自己的 401 是 BAD_CREDENTIALS，不会走到这里）
  if (res.status === 401 && code === 'UNAUTHENTICATED') {
    session.clear()
    const here = router.currentRoute.value
    if (here.path !== '/login') await router.replace({ path: '/login', query: { redirect: here.fullPath } })
  }
  throw new ApiError(res.status, code, err.message ?? '系统出错了，请稍后重试', data as Record<string, unknown>)
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

export const http = {
  get: <T>(path: string, query?: Query) => request<T>('GET', path, undefined, query),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
}

/** 给人看的错误文字：ApiError 的 message 本来就是中文 */
export const errorText = (e: unknown) => (e instanceof Error ? e.message : '系统出错了，请稍后重试')
