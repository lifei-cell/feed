import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { clearSession, setSession, store } from '../store'
import AppShell from './AppShell.vue'
import UserAvatar from './UserAvatar.vue'

function adminToken() {
  const payload = btoa(JSON.stringify({ roles: ['ADMIN'] }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

describe('shell components', () => {
  beforeEach(() => {
    clearSession()
    store.user = { id: 1, username: 'alice', nickname: 'Alice' }
    store.unreadCount = 0
  })

  it('renders initials and falls back when an avatar fails', async () => {
    const initials = mount(UserAvatar, { props: { profile: { nickname: 'Li Fei' }, size: 50 } })
    expect(initials.text()).toBe('LI')
    expect(initials.get('.avatar').attributes('style')).toContain('50px')

    const image = mount(UserAvatar, {
      props: { profile: { nickname: 'Alice', avatarUrl: 'https://example.com/avatar.png' } },
    })
    await image.get('img').trigger('error')
    expect(image.find('img').exists()).toBe(false)
    expect(image.text()).toBe('AL')
  })

  it('shows admin navigation, caps badges and emits navigation', async () => {
    setSession({ accessToken: adminToken(), refreshToken: 'refresh' })
    store.unreadCount = 120
    const wrapper = mount(AppShell, {
      props: { route: 'feed' },
      slots: { default: '<p>content</p>' },
    })

    expect(wrapper.text()).toContain('运维')
    expect(wrapper.text()).toContain('99+')
    const relationship = wrapper.findAll('.side-nav button').find((item) => item.text().includes('关系'))
    await relationship.trigger('click')
    expect(wrapper.emitted('navigate')).toContainEqual(['people'])
  })
})
