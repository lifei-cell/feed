import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { endpoints } from '../api'
import { clearSession, store } from '../store'
import FeedView from './FeedView.vue'

const PostCardStub = {
  props: ['post'],
  template: '<article class="post-card-stub">{{ post.content }}</article>',
}

describe('FeedView', () => {
  beforeEach(() => {
    clearSession()
    store.user = { id: 1, username: 'alice', nickname: 'Alice' }
    vi.spyOn(endpoints, 'friends').mockResolvedValue([
      { id: 2, username: 'bob', nickname: 'Bob' },
    ])
    vi.spyOn(endpoints, 'user').mockImplementation(async (id) => ({
      id, username: `user-${id}`, nickname: `User ${id}`,
    }))
  })

  it('loads both feed pages and preserves the composite cursor', async () => {
    const feed = vi.spyOn(endpoints, 'feed')
      .mockResolvedValueOnce(page([{ id: 'p1', authorId: 2, content: 'first post' }], 'cursor-2', true))
      .mockResolvedValueOnce(page([{ id: 'p2', authorId: 3, content: 'second post' }], null, false))

    const wrapper = mount(FeedView, { global: { stubs: { PostCard: PostCardStub } } })
    await flushPromises()

    expect(wrapper.text()).toContain('first post')
    await wrapper.get('.load-more').trigger('click')
    await flushPromises()

    expect(feed).toHaveBeenNthCalledWith(1, null, 10)
    expect(feed).toHaveBeenNthCalledWith(2, 'cursor-2', 10)
    expect(wrapper.text()).toContain('second post')
    wrapper.unmount()
  })

  it('publishes with selected visibility and refreshes the feed', async () => {
    vi.spyOn(endpoints, 'feed')
      .mockResolvedValueOnce(page([], null, false))
      .mockResolvedValueOnce(page([{ id: 'p3', authorId: 1, content: 'private update' }], null, false))
    const publish = vi.spyOn(endpoints, 'publish').mockResolvedValue({ id: 'p3' })
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')

    const wrapper = mount(FeedView, { global: { stubs: { PostCard: PostCardStub } } })
    await flushPromises()
    await wrapper.get('[aria-label="动态内容"]').setValue('  private update  ')
    await wrapper.get('[aria-label="可见范围"]').setValue('ONLY_ME')
    await wrapper.get('.publish-button').trigger('click')
    await flushPromises()

    expect(publish).toHaveBeenCalledWith({
      content: 'private update', visibility: 'ONLY_ME', targetUserIds: [], mediaIds: [],
    }, '11111111-1111-4111-8111-111111111111')
    expect(wrapper.text()).toContain('private update')
    expect(store.toast?.message).toBe('动态已发布，正在扩散给好友')
    wrapper.unmount()
  })
})

function page(items, nextCursor, hasMore) {
  return { items, nextCursor, hasMore, socialByPostId: {} }
}
