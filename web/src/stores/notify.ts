/**
 * 未读数（通知红点）—— **顶栏与消息页共享同一份**。
 *
 * ==========================================================================
 * 为什么必须是个 store，而不是各自请求
 * ==========================================================================
 * 顶栏红点与消息页头部的「N 条未读」必须**永远一致**。
 * 如果两处各发一次 `GET /api/notifications/unread-count`，
 * 就会出现"顶栏显示 3、页面上显示 2"这种自相矛盾的状态 —— 用户只会觉得是 bug。
 *
 * 三条口径：
 * 1. **未登录时是 0 且不发请求**（401 会被显示成"登录已过期"，对未登录的人是错误信息）；
 * 2. **刷新失败不把数清零**：清成 0 等于告诉用户"你没有未读"，
 *    而真相是"没拿到" —— 后者不该伪装成前者（与"收藏数取不到显示 —"同一个道理）。
 *    这里保留上一次的值；界面也不会因为红点消失而误导。
 * 3. **不是本地加减出来的**：标记已读后**重新取一次**。
 *    批量已读的条数由后端决定，本地减一容易与真值分叉。
 */

import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchUnreadCount } from '@/api/notifications'
import { useAuthStore } from './auth'

export const useNotifyStore = defineStore('notify', () => {
  const auth = useAuthStore()

  /** 未读条数。0 = 确实没有未读（见口径 2：取不到时**保留旧值**，不置 0） */
  const unread = ref(0)
  /** 是否成功取到过（用于区分"确实是 0"与"还没取到"） */
  const loaded = ref(false)

  /** 刷新未读数。失败静默（保留旧值），不打扰用户 */
  async function refresh(): Promise<void> {
    if (!auth.isLoggedIn) {
      unread.value = 0
      loaded.value = false
      return
    }
    try {
      unread.value = await fetchUnreadCount()
      loaded.value = true
    } catch {
      // 保留旧值：取不到 ≠ 没有未读（口径 2）
    }
  }

  /** 退出登录时调用：把红点清干净，避免"退出后还挂着别人的未读" */
  function reset(): void {
    unread.value = 0
    loaded.value = false
  }

  return { unread, loaded, refresh, reset }
})
