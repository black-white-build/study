import { reactive, computed } from 'vue'
import { api } from '../api'

export interface AuthUser {
  id: number
  username: string
  nickname?: string
  role?: string
  emotionStatus?: string
  [key: string]: unknown
}
export interface Session {
  accessToken: string
  user: AuthUser
}
interface AuthState {
  user: AuthUser | null
  token: string | null
  ready: boolean
}

const saved = JSON.parse(localStorage.getItem('hp_user') || 'null') as AuthUser | null
export const authState = reactive<AuthState>({
  user: saved,
  token: localStorage.getItem('hp_token'),
  ready: true
})
export const isAuthenticated = computed(() => Boolean(authState.token))
export const isAdmin = computed(() => authState.user?.role === 'ADMIN')
export function setSession(session: Session) {
  authState.token = session.accessToken
  authState.user = session.user
  localStorage.setItem('hp_token', session.accessToken)
  localStorage.setItem('hp_user', JSON.stringify(session.user))
}
export function logout() {
  authState.token = null
  authState.user = null
  localStorage.removeItem('hp_token')
  localStorage.removeItem('hp_user')
}
export async function refreshMe() {
  if (!authState.token) return
  try {
    authState.user = await api.get<AuthUser>('/users/me')
    localStorage.setItem('hp_user', JSON.stringify(authState.user))
  } catch (error: any) {
    if (error.response?.status === 401) logout()
    throw error
  }
}
