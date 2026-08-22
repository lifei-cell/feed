import { reactive } from 'vue'
import { endpoints, session } from './api'

export const store = reactive({
  ready: false,
  user: null,
  claims: {},
  unreadCount: 0,
  toast: null,
  profileCache: new Map(),
  mediaUrls: new Map(),
})

let toastTimer

export function notify(message, tone = 'success') {
  store.toast = { message, tone, id: Date.now() }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { store.toast = null }, 3600)
}

export function setSession(access) {
  session.setTokens(access)
  store.claims = session.claims()
}

export function clearSession() {
  session.clear()
  store.user = null
  store.claims = {}
  store.unreadCount = 0
  store.profileCache.clear()
  for (const entry of store.mediaUrls.values()) {
    if (entry.objectUrl) URL.revokeObjectURL(entry.url)
  }
  store.mediaUrls.clear()
}

export async function bootstrapSession() {
  store.claims = session.claims()
  try {
    if (!session.token) {
      setSession(await endpoints.refresh())
    }
    store.user = await endpoints.me()
    store.profileCache.set(store.user.id, store.user)
    await refreshUnread()
  } catch {
    clearSession()
  } finally {
    store.ready = true
  }
}

export async function refreshUnread() {
  if (!store.user) return
  try {
    const page = await endpoints.notifications(true, null, 1)
    store.unreadCount = page.unreadCount
  } catch {
    // Notification badge is non-critical; page actions still surface errors.
  }
}

export async function getProfile(userId) {
  if (store.profileCache.has(userId)) return store.profileCache.get(userId)
  const profile = await endpoints.user(userId)
  store.profileCache.set(userId, profile)
  return profile
}

export async function getMediaUrl(mediaId, variant = 'ORIGINAL') {
  const key = `${mediaId}:${variant}`
  const cached = store.mediaUrls.get(key)
  if (cached && (!cached.expiresAt || cached.expiresAt - Date.now() > 30_000)) return cached.url
  if (cached?.objectUrl) URL.revokeObjectURL(cached.url)

  const access = await endpoints.mediaAccess(mediaId, variant)
  if (!access.url.startsWith('/api/')) {
    store.mediaUrls.set(key, {
      url: access.url, objectUrl: false,
      expiresAt: access.expiresAt ? Date.parse(access.expiresAt) : null,
    })
    return access.url
  }
  const blob = await endpoints.mediaBlob(mediaId, variant)
  const url = URL.createObjectURL(blob)
  store.mediaUrls.set(key, { url, objectUrl: true, expiresAt: null })
  return url
}

export function isAdmin() {
  return Array.isArray(store.claims.roles) && store.claims.roles.includes('ADMIN')
}
