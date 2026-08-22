const TOKEN_KEY = 'friend-feed.access-token'
const REFRESH_TOKEN_KEY = 'friend-feed.refresh-token'
let refreshInFlight = null

export class ApiError extends Error {
  constructor(status, message, payload = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
  }
}

export const session = {
  get token() {
    return localStorage.getItem(TOKEN_KEY)
  },
  set token(value) {
    if (value) localStorage.setItem(TOKEN_KEY, value)
    else localStorage.removeItem(TOKEN_KEY)
  },
  get refreshToken() {
    return localStorage.getItem(REFRESH_TOKEN_KEY)
  },
  set refreshToken(value) {
    if (value) localStorage.setItem(REFRESH_TOKEN_KEY, value)
    else localStorage.removeItem(REFRESH_TOKEN_KEY)
  },
  setTokens(value) {
    this.token = value?.accessToken
    this.refreshToken = value?.refreshToken
  },
  claims() {
    const token = this.token
    if (!token) return {}
    try {
      const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
      return JSON.parse(decodeURIComponent(atob(payload).split('').map((char) =>
        `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`).join('')))
    } catch {
      return {}
    }
  },
  clear() {
    this.token = null
    this.refreshToken = null
  },
}

async function refreshSession() {
  if (!session.refreshToken) throw new ApiError(401, '登录已过期，请重新登录')
  if (!refreshInFlight) {
    refreshInFlight = fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    }).then(async (response) => {
      if (!response.ok) throw new ApiError(response.status, '登录已过期，请重新登录')
      const result = await response.json()
      session.setTokens(result)
      return result
    }).finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
}

export async function api(path, options = {}) {
  const headers = new Headers(options.headers || {})
  headers.set('Accept', options.responseType === 'blob' ? '*/*' : 'application/json')
  if (session.token) headers.set('Authorization', `Bearer ${session.token}`)

  let body = options.body
  if (body != null && !(body instanceof FormData) && typeof body !== 'string') {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(body)
  }

  let response
  try {
    response = await fetch(path, { ...options, headers, body })
  } catch {
    throw new ApiError(0, '无法连接服务器，请检查服务是否已启动')
  }

  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/api/auth/')
        && !options._retried && session.refreshToken) {
      try {
        await refreshSession()
        return api(path, { ...options, _retried: true })
      } catch {
        session.clear()
        window.dispatchEvent(new CustomEvent('session-expired'))
        throw new ApiError(401, '登录已过期，请重新登录')
      }
    }
    const contentType = response.headers.get('content-type') || ''
    let payload = null
    try {
      payload = contentType.includes('json') ? await response.json() : await response.text()
    } catch {
      // Keep the normalized fallback when an error response has no readable body.
    }
    const message = payload?.detail || payload?.message || (typeof payload === 'string' && payload)
      || `请求失败（${response.status}）`
    if (response.status === 401 && !path.startsWith('/api/auth/')) {
      session.clear()
      window.dispatchEvent(new CustomEvent('session-expired'))
    }
    throw new ApiError(response.status, message, payload)
  }

  if (response.status === 204) return null
  if (options.responseType === 'blob') return response.blob()
  const contentType = response.headers.get('content-type') || ''
  return contentType.includes('json') ? response.json() : response.text()
}

export const endpoints = {
  login: (body) => api('/api/auth/login', { method: 'POST', body }),
  register: (body) => api('/api/auth/register', { method: 'POST', body }),
  requestRegistrationCode: (body) => api('/api/auth/verification/register/request', { method: 'POST', body }),
  requestPasswordReset: (body) => api('/api/auth/password-reset/request', { method: 'POST', body }),
  confirmPasswordReset: (body) => api('/api/auth/password-reset/confirm', { method: 'POST', body }),

  refresh: (body) => api('/api/auth/refresh', { method: 'POST', body }),
  logout: () => api('/api/auth/logout', { method: 'POST' }),
  revoke: (body) => api('/api/auth/revoke', { method: 'POST', body }),
  me: () => api('/api/users/me'),
  updateMe: (body) => api('/api/users/me', { method: 'PATCH', body }),
  user: (id) => api(`/api/users/${id}`),
  searchUsers: (query, afterId = null, size = 20) => {
    const params = new URLSearchParams({ q: query, size })
    if (afterId != null) params.set('afterId', afterId)
    return api(`/api/users/search?${params}`)
  },
  feed: (cursor = null, size = 10) => {
    const params = new URLSearchParams({ size })
    if (cursor) params.set('cursor', cursor)
    return api(`/api/feed?${params}`)
  },
  post: (id) => api(`/api/posts/${id}`),
  publish: (body, key) => api('/api/posts', {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
  deletePost: (id) => api(`/api/posts/${id}`, { method: 'DELETE' }),
  like: (id) => api(`/api/posts/${id}/like`, { method: 'PUT' }),
  unlike: (id) => api(`/api/posts/${id}/like`, { method: 'DELETE' }),
  comments: (id, afterId = null, size = 50) => {
    const params = new URLSearchParams({ size })
    if (afterId != null) params.set('afterId', afterId)
    return api(`/api/posts/${id}/comments?${params}`)
  },
  comment: (id, content) => api(`/api/posts/${id}/comments`, { method: 'POST', body: { content } }),
  deleteComment: (id) => api(`/api/comments/${id}`, { method: 'DELETE' }),
  uploadProxy: (file) => {
    const data = new FormData()
    data.append('file', file)
    return api('/api/media', { method: 'POST', body: data })
  },
  upload: async (file) => {
    const ticket = await api('/api/media/uploads', {
      method: 'POST', body: { filename: file.name, contentType: file.type, sizeBytes: file.size },
    })
    if (ticket.mode !== 'DIRECT') {
      const data = new FormData()
      data.append('file', file)
      return api('/api/media', { method: 'POST', body: data })
    }
    const headers = new Headers(ticket.headers || {})
    if (!headers.has('Content-Type')) headers.set('Content-Type', file.type)
    let response
    try {
      response = await fetch(ticket.uploadUrl, { method: ticket.method || 'PUT', headers, body: file })
    } catch {
      throw new ApiError(0, '无法连接媒体存储，请稍后重试')
    }
    if (!response.ok) throw new ApiError(response.status, `媒体直传失败（${response.status}）`)
    return api(`/api/media/${ticket.mediaId}/confirm`, { method: 'POST' })
  },
  deleteMedia: (id) => api(`/api/media/${id}`, { method: 'DELETE' }),
  mediaAccess: (id, variant = 'ORIGINAL') => api(`/api/media/${id}/access?variant=${variant}`),
  mediaBlob: (id, variant = 'ORIGINAL') => api(
    `/api/media/${id}/${variant === 'PREVIEW' ? 'preview' : 'content'}`, { responseType: 'blob' },
  ),
  friends: () => api('/api/relationships/friends'),
  blocks: () => api('/api/relationships/blocks'),
  friendRequests: (box = 'INCOMING', status = 'PENDING', beforeId = null, size = 50) => {
    const params = new URLSearchParams({ box, status, size })
    if (beforeId != null) params.set('beforeId', beforeId)
    return api(`/api/relationships/friend-requests?${params}`)
  },
  sendFriendRequest: (recipientId) => api('/api/relationships/friend-requests', {
    method: 'POST', body: { recipientId },
  }),
  acceptFriendRequest: (id) => api(`/api/relationships/friend-requests/${id}/accept`, { method: 'POST' }),
  rejectFriendRequest: (id) => api(`/api/relationships/friend-requests/${id}/reject`, { method: 'POST' }),
  withdrawFriendRequest: (id) => api(`/api/relationships/friend-requests/${id}`, { method: 'DELETE' }),
  removeFriend: (id) => api(`/api/relationships/friends/${id}`, { method: 'DELETE' }),
  block: (id) => api(`/api/relationships/blocks/${id}`, { method: 'PUT' }),
  unblock: (id) => api(`/api/relationships/blocks/${id}`, { method: 'DELETE' }),
  notifications: (unreadOnly = false, beforeId = null, size = 50) => {
    const params = new URLSearchParams({ unreadOnly, size })
    if (beforeId != null) params.set('beforeId', beforeId)
    return api(`/api/notifications?${params}`)
  },
  markNotificationRead: (id) => api(`/api/notifications/${id}/read`, { method: 'PATCH' }),
  markAllNotificationsRead: () => api('/api/notifications/read-all', { method: 'PATCH' }),
  outboxMetrics: () => api('/api/admin/outbox/metrics'),
  replayOutbox: (id) => api(`/api/admin/outbox/${id}/replay`, { method: 'POST' }),
  fanoutPolicy: (authorId) => api(`/api/admin/fanout-policies/${authorId}`),
  setFanoutPolicy: (authorId, body) => api(`/api/admin/fanout-policies/${authorId}`, {
    method: 'PUT', body,
  }),
  switchFanoutPolicy: (authorId, body) => api(`/api/admin/fanout-policies/${authorId}/switch`, {
    method: 'POST', body,
  }),
  resetFanoutPolicy: (authorId) => api(`/api/admin/fanout-policies/${authorId}`, { method: 'DELETE' }),
  fanoutAutomation: () => api('/api/admin/fanout-policies/automation'),
  runFanoutAutomation: () => api('/api/admin/fanout-policies/automation/run', { method: 'POST' }),
  fanoutBackfills: (authorId = null, status = null, size = 20) => {
    const params = new URLSearchParams({ size })
    if (authorId) params.set('authorId', authorId)
    if (status) params.set('status', status)
    return api(`/api/admin/fanout-backfills?${params}`)
  },
  fanoutBackfill: (id) => api(`/api/admin/fanout-backfills/${id}`),
  pauseFanoutBackfill: (id) => api(`/api/admin/fanout-backfills/${id}/pause`, { method: 'POST' }),
  resumeFanoutBackfill: (id) => api(`/api/admin/fanout-backfills/${id}/resume`, { method: 'POST' }),
  retryFanoutBackfill: (id) => api(`/api/admin/fanout-backfills/${id}/retry`, { method: 'POST' }),
  cancelFanoutBackfill: (id) => api(`/api/admin/fanout-backfills/${id}/cancel`, { method: 'POST' }),
  feedShadowMetrics: () => api('/api/admin/feed-shadow/metrics'),
}
