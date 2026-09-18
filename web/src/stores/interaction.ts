/**
 * 互动状态（**会话内存态**）。
 *
 * ==========================================================================
 * ⚠️ 这个 store 存在的原因是一个**契约缺口**，不是设计选择
 * ==========================================================================
 * `POST /api/posts/{id}/like` 这类互动端点返回的都是 `ApiResponseVoid`（**无 data**），
 * 而 `PostDetailVO` / `CommentItemVO` / `CommentReplyVO` **都没有** `isLiked` /
 * `isCollected` 之类的字段。于是：
 *
 * - **服务端不会告诉前端"我有没有点过赞"**，接口也不返回新的计数；
 * - 结果：刷新一次页面，点赞按钮就回到"未点赞"的样子（而计数是真的 +1 了）。
 *
 * 对比：`UserProfileVO` **有 `isFollowing`**，所以**关注态是可以正确渲染的** ——
 * 只有点赞/收藏这一类缺字段。
 *
 * 因此这里保存**本次会话里用户自己点过的**集合，让按钮在刷新前保持正确。
 * 它是**临时层**：契约补上 `isLiked` / `isCollected` 之后，这个 store 就该退役
 * （已作为 CR 登记进交付报告，前端不自己造契约字段）。
 *
 * ⚠️ 两条边界必须清楚：
 * 1. **刷新即丢**。这不是 bug，是本层能力的上限 —— 所以界面**不谎称**它是权威状态
 *    （见 `CommentSection` / 详情页里"点赞数取自服务端"的处理）；
 * 2. **点错了也不会造成数据错误**：这些端点都是**幂等**的（《技术方案》§8.1），
 *    重复 POST 不会重复计数。这也是敢做乐观更新的前提。
 */

import { ref } from 'vue'
import { defineStore } from 'pinia'

export const useInteractionStore = defineStore('interaction', () => {
  /** 本次会话里我点过赞的帖子 id */
  const likedPosts = ref<Set<number>>(new Set())
  /** 本次会话里我收藏过的帖子 id */
  const collectedPosts = ref<Set<number>>(new Set())
  /** 本次会话里我点过赞的评论 id */
  const likedComments = ref<Set<number>>(new Set())

  /**
   * 记一笔/撤销一笔。
   *
   * 为什么整体替换 Set 而不是 `.add()`/`.delete()` 原地改：
   * Vue 对 `ref(new Set())` 的集合变更**能**追踪，但整体替换是最稳的写法 ——
   * 三端（H5/小程序/App）与将来 Vue 版本下的行为都一致，不依赖集合代理的实现细节。
   */
  function setFlag(target: typeof likedPosts, id: number, on: boolean): void {
    const next = new Set(target.value)
    if (on) next.add(id)
    else next.delete(id)
    target.value = next
  }

  const isPostLiked = (id: number) => likedPosts.value.has(id)
  const isPostCollected = (id: number) => collectedPosts.value.has(id)
  const isCommentLiked = (id: number) => likedComments.value.has(id)

  const markPostLiked = (id: number, on: boolean) => setFlag(likedPosts, id, on)
  const markPostCollected = (id: number, on: boolean) => setFlag(collectedPosts, id, on)
  const markCommentLiked = (id: number, on: boolean) => setFlag(likedComments, id, on)

  return {
    likedPosts,
    collectedPosts,
    likedComments,
    isPostLiked,
    isPostCollected,
    isCommentLiked,
    markPostLiked,
    markPostCollected,
    markCommentLiked,
  }
})
