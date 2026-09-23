/**
 * 点赞 / 收藏的**状态缓存**（L1 2026-09-20 口径）。
 *
 * ==========================================================================
 * 为什么需要它：写接口**不返回 data**
 * ==========================================================================
 * `POST /api/posts/{id}/like` 的响应体是 `{"code":0,"message":"ok"}` ——
 * **既没有新状态、也没有新计数**。而状态数据在**读接口**里：
 * `PostSummaryVO.liked/.collected`（列表 / Feed / 我的收藏 / 用户帖子列表）
 * 与 `PostDetailVO.liked/.collected`（详情）。语义：**相对于当前请求者**，
 * **未登录一律 false**（且仍返回 200）。
 *
 * ==========================================================================
 * 三条规则（L1 定的，顺序都不能反）
 * ==========================================================================
 * 1. **读接口是唯一事实来源**：任何一次读返回后，用接口值**覆盖**本地值
 *    （`applyFromApi`）。所以列表刷新、切页、从详情返回首页……都会把状态拉回真值；
 * 2. **点击后乐观更新**（`markLiked` / `markCollected`）：立即翻转，让用户马上看到变化；
 *    失败由调用方回滚（本 store 不管请求）；
 * 3. **未登录一律 false**：即使缓存里还留着上次登录的状态，也不能显示成"已点赞" ——
 *    否则退出登录后图标还是点亮的，与接口语义矛盾（验收判据 ④）。
 *
 * ⚠️ **计数不放在这里**：`likeCount` 的权威值是接口返回的，
 *    乐观 ±1 只是**临时显示**，由组件的本地态承担、并在下一次读接口回来时同步
 *    （见 `PostCard.vue` 里的 watch）。把计数也塞进这个 store 反而容易漂。
 */

import { ref } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth'

/** 一帖的互动状态（只存"是不是我点的"，不存计数） */
export interface PostInteractionState {
  liked: boolean
  collected: boolean
}

export const useInteractionStore = defineStore('interaction', () => {
  const auth = useAuthStore()

  /** `postId -> 状态`。**每次读接口回来都会覆盖对应项** */
  const cache = ref<Record<number, PostInteractionState>>({})

  /**
   * 用读接口的值覆盖本地（**这是唯一的事实来源**）。
   * 列表/详情每渲染一次都会调它，所以"跨页面一致"是自然结果，不需要专门同步。
   */
  function applyFromApi(postId: number, patch: Partial<PostInteractionState>): void {
    if (!postId) return
    const prev = cache.value[postId] ?? { liked: false, collected: false }
    /*
     * ⚠️ **只覆盖接口真的给了的字段** —— "未知"与 "false" 不是一回事。
     * 例：`CollectionItemVO` 里有 `collected` 语义但**没有 `liked` 字段**，
     * 如果不加区分地把 `liked: false` 覆盖进去，就会把"我确实点过赞"抹掉
     * （图标从实心红变回线框，而接口从没说过我没点）。
     */
    const nextState: PostInteractionState = {
      liked: typeof patch.liked === 'boolean' ? patch.liked : prev.liked,
      collected: typeof patch.collected === 'boolean' ? patch.collected : prev.collected,
    }
    if (nextState.liked === prev.liked && nextState.collected === prev.collected) return
    cache.value = { ...cache.value, [postId]: nextState }
  }

  /** 批量覆盖（列表页一条语句更清楚） */
  function applyManyFromApi(items: Array<{ id: number; liked: boolean; collected: boolean }>): void {
    const next = { ...cache.value }
    for (const it of items) {
      if (!it.id) continue
      next[it.id] = { liked: it.liked, collected: it.collected }
    }
    cache.value = next
  }

  /**
   * 是否已点赞。
   * ⚠️ **未登录恒 false**（契约语义），不依赖缓存里可能残留的上次登录态。
   */
  function isPostLiked(postId: number): boolean {
    if (!auth.isLoggedIn) return false
    return cache.value[postId]?.liked ?? false
  }

  /** 是否已收藏。未登录恒 false（同上） */
  function isPostCollected(postId: number): boolean {
    if (!auth.isLoggedIn) return false
    return cache.value[postId]?.collected ?? false
  }

  /** 乐观翻转点赞态（点击时调用；失败由调用方再翻回来） */
  function markPostLiked(postId: number, liked: boolean): void {
    if (!postId) return
    const prev = cache.value[postId] ?? { liked: false, collected: false }
    cache.value = { ...cache.value, [postId]: { ...prev, liked } }
  }

  /** 乐观翻转收藏态 */
  function markPostCollected(postId: number, collected: boolean): void {
    if (!postId) return
    const prev = cache.value[postId] ?? { liked: false, collected: false }
    cache.value = { ...cache.value, [postId]: { ...prev, collected } }
  }

  /* ---------------------------------------------------------------------------
   * 评论点赞：契约里**没有**评论的 `liked` 字段（`CommentItemVO` / `CommentReplyVO` 都没有）
   * → 只能维持"会话内记住自己点过"的老做法，并明确标注它**刷新即丢**。
   * 这是已知缺口，与帖子的点赞不是一回事（帖子已有服务端状态）。
   * ------------------------------------------------------------------------- */

  /** 本次会话里我点过赞的评论 id（**刷新即丢**，因为契约没给评论的 liked） */
  const likedCommentIds = ref<Set<number>>(new Set())

  function isCommentLiked(commentId: number): boolean {
    if (!auth.isLoggedIn) return false
    return likedCommentIds.value.has(commentId)
  }

  function markCommentLiked(commentId: number, liked: boolean): void {
    const next = new Set(likedCommentIds.value)
    if (liked) next.add(commentId)
    else next.delete(commentId)
    likedCommentIds.value = next
  }

  return {
    cache,
    applyFromApi,
    applyManyFromApi,
    isPostLiked,
    isPostCollected,
    markPostLiked,
    markPostCollected,
    isCommentLiked,
    markCommentLiked,
  }
})
