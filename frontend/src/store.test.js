import { beforeEach, describe, expect, it, vi } from 'vitest'
import { endpoints, session } from './api'
import {
  bootstrapSession,
  clearSession,
  getMediaUrl,
  getProfile,
  isAdmin,
  notify,
  refreshUnread,
  setSession,
  store,
} from './store'

function tokenWithClaims(claims) {
  const payload = btoa(JSON.stringify(claims))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

describe('application store', () => {
  beforeEach(() => {
    clearSession()
    store.ready = false
    store.toast = null
  })

  it('sets the session, decodes roles and clears user state', () => {
    setSession({ accessToken: tokenWithClaims({ roles: ['ADMIN'] }), refreshToken: 'refresh' })
    store.user = { id: 7 }
    store.unreadCount = 4

    expect(isAdmin()).toBe(true)
    clearSession()

    expect(session.token).toBeNull()
    expect(store.user).toBeNull()
    expect(store.unreadCount).toBe(0)
  })

  it('bootstraps a valid session and refreshes notification count', async () => {
    session.setTokens({ accessToken: tokenWithClaims({ roles: ['USER'] }), refreshToken: 'refresh' })
    vi.spyOn(endpoints, 'me').mockResolvedValue({ id: 9, nickname: 'Alice' })
    vi.spyOn(endpoints, 'notifications').mockResolvedValue({ unreadCount: 3 })

    await bootstrapSession()

    expect(store.ready).toBe(true)
    expect(store.user.id).toBe(9)
    expect(store.unreadCount).toBe(3)
    expect(isAdmin()).toBe(false)
  })

  it('caches profiles and signed media URLs', async () => {
    vi.spyOn(endpoints, 'user').mockResolvedValue({ id: 12, nickname: 'Bob' })
    vi.spyOn(endpoints, 'mediaAccess').mockResolvedValue({
      url: 'https://media.example/post.jpg',
      expiresAt: new Date(Date.now() + 300_000).toISOString(),
    })

    await expect(getProfile(12)).resolves.toEqual({ id: 12, nickname: 'Bob' })
    await expect(getProfile(12)).resolves.toEqual({ id: 12, nickname: 'Bob' })
    expect(endpoints.user).toHaveBeenCalledTimes(1)

    await expect(getMediaUrl('media-1')).resolves.toBe('https://media.example/post.jpg')
    await expect(getMediaUrl('media-1')).resolves.toBe('https://media.example/post.jpg')
    expect(endpoints.mediaAccess).toHaveBeenCalledTimes(1)
  })

  it('treats notification refresh and toast display as non-critical UI state', async () => {
    store.user = { id: 1 }
    vi.spyOn(endpoints, 'notifications').mockRejectedValue(new Error('offline'))

    await expect(refreshUnread()).resolves.toBeUndefined()
    notify('saved')

    expect(store.toast).toEqual(expect.objectContaining({ message: 'saved', tone: 'success' }))
  })
})
