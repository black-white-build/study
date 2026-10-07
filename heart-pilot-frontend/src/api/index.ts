import axios, { AxiosRequestConfig } from 'axios'

export const API_BASE_URL: string =
  import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? '/api' : 'http://localhost:8123/api')
const request = axios.create({ baseURL: API_BASE_URL, timeout: 90000 })

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('hp_token')
  if (token) (config.headers as Record<string, string>).Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  (response) => response,
  (error: any) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('hp_token')
      localStorage.removeItem('hp_user')
      if (!location.pathname.startsWith('/login')) location.assign('/login')
    }
    return Promise.reject(error)
  }
)

export interface ApiClient {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  patch<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  blob(url: string, config?: AxiosRequestConfig): Promise<Blob>
  download(url: string, filename: string): Promise<void>
}

export const api: ApiClient = {
  get: (url, config) => request.get(url, config).then((response) => response.data),
  post: (url, data, config) => request.post(url, data, config).then((response) => response.data),
  put: (url, data, config) => request.put(url, data, config).then((response) => response.data),
  patch: (url, data, config) => request.patch(url, data, config).then((response) => response.data),
  delete: (url, config) => request.delete(url, config).then((response) => response.data),
  blob: (url, config) =>
    request.get(url, { ...config, responseType: 'blob' }).then((response) => response.data),
  async download(url, filename) {
    const response = await request.get(url, { responseType: 'blob' })
    const href = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = href
    anchor.download = filename
    anchor.click()
    URL.revokeObjectURL(href)
  }
}

export interface SSEStreamState {
  received: boolean
  completed: boolean
}
export interface SSEHandlers {
  delta?: (data: any) => void
  done?: (data: any) => void
  close?: (state: SSEStreamState) => void
  error?: (data: any) => void
  transportError?: (error: Error, state: SSEStreamState) => void
  all?: (event: string, data: any) => void
  [event: string]: any
}
export interface SSEStreamHandle extends AbortController {
  completed: Promise<void>
}

export async function streamSSE(
  url: string,
  data: unknown,
  handlers: SSEHandlers = {}
): Promise<SSEStreamHandle> {
  const controller = new AbortController() as SSEStreamHandle
  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${url}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('hp_token') || ''}`
      },
      body: data == null ? undefined : JSON.stringify(data),
      signal: controller.signal
    })
  } catch {
    throw new Error('无法连接后端服务，请确认 8123 端口已启动')
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new Error(error.message || '请求失败')
  }

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let received = false
  let completed = false

  const dispatch = (block: string) => {
    let event = 'message'
    let payload = ''
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      if (line.startsWith('data:')) payload += line.slice(5).trim()
    }
    if (!payload) return
    received = true
    if (event === 'done') completed = true
    let parsed: any = payload
    try {
      parsed = JSON.parse(payload)
    } catch {
      /* 非 JSON 负载原样透传 */
    }
    handlers[event]?.(parsed)
    handlers.all?.(event, parsed)
  }

  controller.completed = (async () => {
    try {
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const blocks = buffer.split(/\r?\n\r?\n/)
        buffer = blocks.pop() || ''
        blocks.forEach(dispatch)
      }
      if (buffer.trim()) dispatch(buffer)
      handlers.close?.({ received, completed })
    } catch (error) {
      if (error instanceof Error && error.name !== 'AbortError')
        handlers.transportError?.(error, { received, completed })
    }
  })()

  return controller
}

export default request
