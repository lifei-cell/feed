import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, session } from './api'

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('API client', () => {
  beforeEach(() => {
    session.clear()
  })

  it('stores both tokens and decodes access-token claims', () => {
    const payload = btoa(JSON.stringify({ sub: '7', roles: ['ADMIN'] }))
      .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')

    session.setTokens({ accessToken: `header.${payload}.signature`, refreshToken: 'refresh-1' })

    expect(session.token).toContain(payload)
    expect(session.refreshToken).toBe('refresh-1')
    expect(session.claims()).toEqual({ sub: '7', roles: ['ADMIN'] })
  })

  it('serializes JSON and attaches the bearer token', async () => {
    session.token = 'access-1'
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ id: 'post-1' }))

    await expect(api('/api/posts', { method: 'POST', body: { content: 'hello' } }))
      .resolves.toEqual({ id: 'post-1' })

    const [, options] = fetchMock.mock.calls[0]
    expect(options.headers.get('Authorization')).toBe('Bearer access-1')
    expect(options.headers.get('Content-Type')).toBe('application/json')
    expect(options.body).toBe('{"content":"hello"}')
  })

  it('merges concurrent refreshes and retries both requests once', async () => {
    session.setTokens({ accessToken: 'expired', refreshToken: 'refresh-1' })
    let releaseRefresh
    const pendingRefresh = new Promise((resolve) => { releaseRefresh = resolve })
    const attempts = new Map()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (path) => {
      if (path === '/api/auth/refresh') return pendingRefresh
      const count = (attempts.get(path) || 0) + 1
      attempts.set(path, count)
      return count === 1 ? jsonResponse({ detail: 'expired' }, 401) : jsonResponse({ path })
    })

    const feed = api('/api/feed')
    const profile = api('/api/users/me')
    await vi.waitFor(() => {
      expect(fetchMock.mock.calls.filter(([path]) => path === '/api/auth/refresh')).toHaveLength(1)
    })
    releaseRefresh(jsonResponse({ accessToken: 'fresh', refreshToken: 'refresh-2' }))

    await expect(Promise.all([feed, profile])).resolves.toEqual([
      { path: '/api/feed' },
      { path: '/api/users/me' },
    ])
    expect(session.token).toBe('fresh')
    expect(session.refreshToken).toBe('refresh-2')
  })

  it('normalizes network failures', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('offline'))

    await expect(api('/api/feed')).rejects.toEqual(expect.objectContaining({
      name: 'ApiError', status: 0,
    }))
    await expect(api('/api/feed')).rejects.toBeInstanceOf(ApiError)
  })
})
