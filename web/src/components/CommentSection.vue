<template>
  <!--
    评论区（详情页内）。契约：`GET /api/posts/{id}/comments`（主楼分页 + 前若干条楼中楼预览）、
    `GET /api/comments/{rootId}/replies`（某主楼的全部楼中楼）、
    `POST /api/comments`（发评论）、`DELETE /api/comments/{id}`（删自己的）。
  -->
  <view class="card" data-testid="comment-section">
    <!--
      ========================================================================
      评论区**默认收起，点击才展开**（需求方 2026-09-17 定）。
      ========================================================================
      收起时**不发请求** —— 要显示的条数由父页面从详情响应里给（`commentCount`），
      所以"看一眼帖子"不会白白拉一次评论列表。
      展开状态由父页面持有（`v-model:open`），这样详情页的「评论」按钮也能把它打开。
    -->
    <view v-if="!open" class="collapsed">
      <view class="collapsed__btn" data-testid="comment-open" @click="openIt">
        <HyIcon type="comment" size="sm" />
        <text class="collapsed__text">
          {{ commentCount > 0 ? `查看 ${commentCount} 条评论` : '写评论' }}
        </text>
      </view>
    </view>

    <template v-else>
      <view class="head">
        <text class="head__title">评论</text>
        <text class="head__count" data-testid="comment-total">{{ total }}</text>
        <text class="head__collapse" data-testid="comment-collapse" @click="close">收起</text>
      </view>

    <!-- ==================== 发表框 ==================== -->
    <view class="editor">
      <!-- 正在回复谁：不给这个提示的话，用户会不知道自己是在发主楼还是在回某人 -->
      <view v-if="replyTo" class="editor__target" data-testid="comment-reply-target">
        <text class="editor__target-text">正在回复 {{ replyTo.name }}</text>
        <text class="editor__cancel" data-testid="comment-cancel-reply" @click="cancelReply">取消</text>
      </view>

      <view class="editor__box">
        <textarea
          v-model="draft"
          class="editor__input"
          :maxlength="1000"
          :placeholder="replyTo ? `回复 ${replyTo.name}…` : '说点什么…'"
          placeholder-class="editor__placeholder"
          data-testid="comment-input"
        />
      </view>

      <view class="editor__foot">
        <!--
          ⚠️ 这个计数器不只是装饰：它由 `draft` 驱动，因此是**组件状态**的可观察代理。
          写 E2E 时用它判断"v-model 真的同步了" ——
          `uni-textarea` 在 H5 端是"外壳 + 内层原生 textarea"，
          程序化 `fill()`（直接设 DOM 值 + dispatch input）**偶尔不会**被它同步进组件状态，
          于是 DOM 里有字、组件里是空，提交时只会得到"说点什么再发表"这种误导性的结果。
        -->
        <text class="editor__counter" data-testid="comment-counter">{{ draft.length }}/1000</text>
        <view class="editor__btn" data-testid="comment-submit" @click="submit">
          <text class="editor__btn-text">{{ submitting ? '发送中…' : '发表' }}</text>
        </view>
      </view>

      <text v-if="error" class="editor__error" data-testid="comment-error">{{ error }}</text>
    </view>

    <ListState
      :loading="loading && comments.length === 0"
      :error="loadError"
      :empty="comments.length === 0"
      empty-text="还没有评论，来说第一句"
      loading-text="正在加载评论…"
      testid-base="comment-state"
      @retry="reload"
    />

    <!-- ==================== 主楼列表 ==================== -->
    <view v-for="c in comments" :key="c.id" class="item" data-testid="comment-item">
      <!-- 头像与昵称都可点 → 个人主页（需求方 2026-09-18 定：「都点」） -->
      <Avatar
        :url="c.authorAvatarUrl"
        :nickname="c.authorName"
        :size="36"
        :data-testid="`comment-avatar-${c.id}`"
        @click="openUser(c.authorId)"
      />
      <view class="item__body">
        <view class="item__head">
          <text
            class="item__name"
            :data-testid="`comment-author-${c.id}`"
            @click="openUser(c.authorId)"
          >
            {{ c.authorName }}
          </text>
          <text class="item__time">{{ c.timeText }}</text>
        </view>
        <text class="item__content" data-testid="comment-content">{{ c.content }}</text>

        <view class="actions">
          <view
            class="act"
            :class="{ 'act--on': interaction.isCommentLiked(c.id) }"
            :data-testid="`comment-like-${c.id}`"
            @click="toggleLike(c)"
          >
            <HyIcon type="heart" size="sm" />
            <text class="act__text">{{ c.likeCount }}</text>
          </view>
          <text class="act act--plain" :data-testid="`comment-reply-${c.id}`" @click="startReply(c)">
            回复
          </text>
          <!-- 举报评论（M5）：targetType=2、targetId=这条评论的 id -->
          <text
            class="act act--plain"
            :data-testid="`comment-report-${c.id}`"
            @click="openReport(c.id)"
          >
            举报
          </text>
          <text
            v-if="isMine(c.authorId)"
            class="act act--danger"
            :data-testid="`comment-delete-${c.id}`"
            @click="remove(c)"
          >
            删除
          </text>
        </view>

        <!-- ==================== 楼中楼 ==================== -->
        <view v-if="c.replies.length || c.replyCount > 0" class="replies">
          <view v-for="rp in c.replies" :key="rp.id" class="reply" data-testid="reply-item">
            <view class="reply__head">
              <text class="reply__name">{{ rp.authorName }}</text>
              <!--
                `replyToNickname` 非空 = 这条是在回复某条楼中楼（数据上仍挂在该主楼下，
                由 `reply_to_user_id` 记录被回复者，见《技术方案》§6.6 的归并语义）。
              -->
              <text v-if="rp.replyToNickname" class="reply__to">回复 {{ rp.replyToNickname }}</text>
              <text class="reply__time">{{ rp.timeText }}</text>
            </view>
            <text class="reply__content">{{ rp.content }}</text>
            <view class="actions actions--tight">
              <view
                class="act"
                :class="{ 'act--on': interaction.isCommentLiked(rp.id) }"
                :data-testid="`reply-like-${rp.id}`"
                @click="toggleReplyLike(rp)"
              >
                <HyIcon type="heart" size="xs" />
                <text class="act__text">{{ rp.likeCount }}</text>
              </view>
              <text class="act act--plain" :data-testid="`reply-reply-${rp.id}`" @click="startReplyToReply(c, rp)">
                回复
              </text>
              <text
                v-if="isMine(rp.authorId)"
                class="act act--danger"
                :data-testid="`reply-delete-${rp.id}`"
                @click="removeReply(c, rp)"
              >
                删除
              </text>
            </view>
          </view>

          <!--
            `replies` 是**预览**，`replyCount` 才是总数 —— 两者不等就说明还有没显示的，
            给一个明确的入口去取（而不是让用户以为只有这几条）。
          -->
          <text
            v-if="c.replyCount > c.replies.length"
            class="replies__more"
            :data-testid="`comment-more-replies-${c.id}`"
            @click="loadAllReplies(c)"
          >
            查看全部 {{ c.replyCount }} 条回复
          </text>
        </view>
      </view>
    </view>

    <!-- ==================== 分页 ==================== -->
    <view v-if="comments.length && hasMore" class="more">
      <view class="more__btn" data-testid="comment-load-more" @click="loadMore">
        <text class="more__btn-text">{{ loadingMore ? '加载中…' : '加载更多评论' }}</text>
      </view>
    </view>
    <text v-else-if="comments.length" class="more__end" data-testid="comment-list-end">
      没有更多评论了
    </text>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 评论区。
 *
 * ==========================================================================
 * 三条口径，写错任何一条都会在界面上出问题
 * ==========================================================================
 * 1. **`replies` 是预览、`replyCount` 是总数**。契约 `CommentItemVO` 同时给了这两个字段，
 *    把它们当成一回事会出现"明明说 8 条回复却只显示 2 条"。要全部就得调
 *    `GET /api/comments/{rootId}/replies`。
 * 2. **回复任何一层，`parentId` 都填「主楼的 id」**（P1-3 归并语义）——
 *    楼中楼的 `parent_id`/`root_id` 恒等于所属主楼的 id。填楼中楼的 id 会被后端
 *    唯一写入口的"目标必须是主楼"校验拒掉。所以 `replyTo.rootId` 记的是**主楼 id**，
 *    哪怕用户点的是某条楼中楼下的「回复」。
 * 3. **点赞状态来自会话内存**（`stores/interaction.ts`），不是服务端 ——
 *    契约里没有 `isLiked`。所以界面**不谎称**它是权威状态：计数用服务端给的，
 *    激活态只在本次会话内有效（已作为 CR 登记）。
 *
 * ⚠️ 发表成功后**重新拉第 1 页**而不是乐观插入：
 *    发主楼时接口回的是 `CommentReplyVO`（形状与主楼不同，缺 `replies`/`replyCount`），
 *    自己拼一个"像主楼的对象"容易在字段上出错；重拉一次只有一次请求，换来状态一定正确。
 */
import { computed, ref, watch } from 'vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import ListState from '@/components/ListState.vue'
import ReportSheet from '@/components/ReportSheet.vue'
import { createComment, deleteComment, fetchComments, fetchReplies } from '@/api/comments'
import { likeComment } from '@/api/interaction'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import { toCommentView, toReplyView, type CommentView, type ReplyView } from '@/utils/postView'
import { useAuthStore } from '@/stores/auth'
import { useInteractionStore } from '@/stores/interaction'

const props = withDefaults(
  defineProps<{
    /** 帖子 id */
    postId: number
    /**
     * 展开状态（由父页面持有）。
     * 收起时**不请求评论列表** —— 头部要显示的条数从 `commentCount` 来，
     * 所以"只看一眼帖子"不会白拉一次评论。
     */
    open?: boolean
    /** 详情响应里的评论总数。收起状态用它显示「查看 N 条评论」 */
    commentCount?: number
  }>(),
  { open: false, commentCount: 0 }
)

const emit = defineEmits<{
  /** 评论总数变化（详情页据此更新互动栏的计数，**不重新拉详情** —— 那会让浏览量 +1） */
  countChange: [delta: number]
  /** 展开/收起 */
  'update:open': [value: boolean]
}>()

function openIt(): void {
  emit('update:open', true)
}

function close(): void {
  emit('update:open', false)
}

const auth = useAuthStore()
const interaction = useInteractionStore()

const comments = ref<CommentView[]>([])
const total = ref(0)

const loading = ref(false)
const loadingMore = ref(false)
const loadError = ref('')
const hasMore = ref(false)
const page = ref(1)

const draft = ref('')
const submitting = ref(false)
const error = ref('')

/** 正在回复的目标。`rootId` 恒为**主楼 id**（见文件头第 2 条） */
const replyTo = ref<{ rootId: number; name: string } | null>(null)

/** 举报弹层：eportTargetId > 0 表示打开，举报的是那条**评论**（targetType=2） */
const reportTargetId = ref(0)

/** 打开举报弹层 */
function openReport(commentId: number): void {
  reportTargetId.value = commentId
}

/** 哪些主楼已经"展开全部楼中楼"（避免重复请求） */
const expanded = ref<Set<number>>(new Set())

/* ---------------------------------------------------------------------------
 * 加载
 * ------------------------------------------------------------------------- */

async function load(targetPage: number, append: boolean): Promise<void> {
  if (append) loadingMore.value = true
  else loading.value = true
  loadError.value = ''
  try {
    const res = await fetchComments(props.postId, targetPage)
    const list = res.list.map(toCommentView)
    comments.value = append ? comments.value.concat(list) : list
    total.value = res.total
    hasMore.value = res.list.length >= PAGE_SIZE_MAX
    page.value = targetPage
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '评论加载失败，请稍后重试'
    if (!append) {
      comments.value = []
      hasMore.value = false
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

/** 重新加载第 1 页（进入、发表后、删除后都走这里） */
function reload(): void {
  expanded.value = new Set()
  void load(1, false)
}

function loadMore(): void {
  if (loadingMore.value || !hasMore.value) return
  void load(page.value + 1, true)
}

/*
 * `postId` 或 `open` 变化都要考虑：
 * - id 变了（同一次会话里换了帖子）→ 重拉；
 * - `open` 从 false → true → **这时才第一次请求**（收起状态一个请求都不发）。
 */
watch(
  [() => props.postId, () => props.open],
  ([id, open]) => {
    if (id && open) reload()
  },
  { immediate: true }
)

/* ---------------------------------------------------------------------------
 * 楼中楼
 * ------------------------------------------------------------------------- */

/**
 * 取某主楼的**全部**楼中楼并替换掉预览。
 * 成功后记进 `expanded`，避免"查看全部"被反复点。
 */
async function loadAllReplies(c: CommentView): Promise<void> {
  if (expanded.value.has(c.id)) return
  try {
    // 一次取满一页（20）；超过一页的先按第一页展示，并把 hasMore 的语义交给 replyCount
    const res = await fetchReplies(c.id, 1)
    const idx = comments.value.findIndex((x) => x.id === c.id)
    if (idx >= 0) {
      comments.value[idx] = { ...comments.value[idx], replies: res.list.map(toReplyView) }
    }
    const next = new Set(expanded.value)
    next.add(c.id)
    expanded.value = next
  } catch (e) {
    uni.showToast({
      title: e instanceof ApiError ? e.message : '回复加载失败，请稍后重试',
      icon: 'none',
    })
  }
}

/* ---------------------------------------------------------------------------
 * 发表 / 回复
 * ------------------------------------------------------------------------- */

function startReply(c: CommentView): void {
  replyTo.value = { rootId: c.id, name: c.authorName }
}

function startReplyToReply(c: CommentView, rp: ReplyView): void {
  // ⚠️ 被回复者显示的是**楼中楼作者**，但 parentId 仍然填**主楼 id**（归并语义）
  replyTo.value = { rootId: c.id, name: rp.authorName }
}

function cancelReply(): void {
  replyTo.value = null
}

/**
 * 点评论作者 → 个人主页。
 * ⚠️ `authorId` 为 0（契约没给作者）时不跳 —— 否则会跳到 `?id=0` 这种坏链接。
 */
function openUser(authorId: number): void {
  if (authorId > 0) uni.navigateTo({ url: `/pages/user/index?id=${authorId}` })
}

async function submit(): Promise<void> {
  error.value = ''
  const content = draft.value.trim()
  if (!content) {
    error.value = '说点什么再发表'
    return
  }

  // 未登录：发评论需要登录（后端会拒 401），这里先引导，别让用户白写一段话
  if (!auth.isLoggedIn) {
    uni.showToast({ title: '请先登录后再评论', icon: 'none' })
    setTimeout(() => uni.navigateTo({ url: '/pages/auth/index?mode=login' }), 700)
    return
  }

  submitting.value = true
  try {
    await createComment({
      postId: props.postId,
      // 主楼不传 parentId；楼中楼传**主楼 id**
      ...(replyTo.value ? { parentId: replyTo.value.rootId } : {}),
      content,
    })
    const wasReply = Boolean(replyTo.value)
    draft.value = ''
    replyTo.value = null
    // 楼中楼不计入主楼总数（commentCount 是主楼/评论总数由后端定义），
    // 这里只在"新主楼"时把计数 +1，避免把数字改错
    if (!wasReply) emit('countChange', 1)
    uni.showToast({ title: wasReply ? '回复成功' : '评论成功', icon: 'none' })
    reload()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '发表失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 点赞 / 删除
 * ------------------------------------------------------------------------- */

/** 是否为本人的内容（用于显示删除入口） */
function isMine(authorId: number): boolean {
  return Boolean(auth.user?.id) && auth.user?.id === authorId
}

/**
 * 评论点赞/取消。
 *
 * 乐观更新 + 失败回滚：端点返回 `ApiResponseVoid`（没有新计数），所以本地先 ±1；
 * 失败就把计数和激活态一起退回去 —— **不能只退一半**（否则计数与实际不一致）。
 */
async function toggleLike(c: CommentView): Promise<void> {
  if (!auth.isLoggedIn) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return
  }
  const on = !interaction.isCommentLiked(c.id)
  const idx = comments.value.findIndex((x) => x.id === c.id)
  if (idx < 0) return

  interaction.markCommentLiked(c.id, on)
  comments.value[idx] = { ...comments.value[idx], likeCount: Math.max(0, c.likeCount + (on ? 1 : -1)) }
  try {
    await likeComment(c.id, on)
  } catch (e) {
    interaction.markCommentLiked(c.id, !on)
    comments.value[idx] = { ...comments.value[idx], likeCount: c.likeCount }
    uni.showToast({ title: e instanceof ApiError ? e.message : '操作失败，请稍后重试', icon: 'none' })
  }
}

async function toggleReplyLike(rp: ReplyView): Promise<void> {
  if (!auth.isLoggedIn) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return
  }
  const on = !interaction.isCommentLiked(rp.id)
  interaction.markCommentLiked(rp.id, on)
  try {
    await likeComment(rp.id, on)
    // 楼中楼的计数只影响这一处，直接原地改（不重拉整页）
    for (const c of comments.value) {
      const i = c.replies.findIndex((x) => x.id === rp.id)
      if (i >= 0) c.replies[i] = { ...c.replies[i], likeCount: Math.max(0, rp.likeCount + (on ? 1 : -1)) }
    }
  } catch (e) {
    interaction.markCommentLiked(rp.id, !on)
    uni.showToast({ title: e instanceof ApiError ? e.message : '操作失败，请稍后重试', icon: 'none' })
  }
}

async function remove(c: CommentView): Promise<void> {
  const res = await uni.showModal({ title: '删除评论', content: '删除后不可恢复，确定吗？' })
  if (!res.confirm) return
  try {
    await deleteComment(c.id)
    // 主楼删除会连带它的楼中楼，但后端对"删了几条"没有约定 —— 所以**不猜**，直接重拉
    reload()
    emit('countChange', -1)
  } catch (e) {
    uni.showToast({ title: e instanceof ApiError ? e.message : '删除失败，请稍后重试', icon: 'none' })
  }
}

async function removeReply(c: CommentView, rp: ReplyView): Promise<void> {
  const res = await uni.showModal({ title: '删除回复', content: '删除后不可恢复，确定吗？' })
  if (!res.confirm) return
  try {
    await deleteComment(rp.id)
    const idx = comments.value.findIndex((x) => x.id === c.id)
    if (idx >= 0) {
      comments.value[idx] = {
        ...comments.value[idx],
        replies: comments.value[idx].replies.filter((x) => x.id !== rp.id),
        replyCount: Math.max(0, comments.value[idx].replyCount - 1),
      }
    }
  } catch (e) {
    uni.showToast({ title: e instanceof ApiError ? e.message : '删除失败，请稍后重试', icon: 'none' })
  }
}

/** 供模板判断用 */
const isEmpty = computed(() => comments.value.length === 0)
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

/* ---------- 收起状态 ---------- */
.collapsed {
  display: flex;

  &__btn {
    height: 40px;
    padding: 0 18px;
    display: flex;
    align-items: center;
    background-color: $hy-bg-page;
    border: 1px solid $hy-border-color;
    border-radius: $hy-radius-pill;

    &:active {
      background-color: $hy-bg-hover;
    }
  }

  &__text {
    margin-left: 6px;
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }
}

.head {
  display: flex;
  align-items: baseline;
  margin-bottom: 14px;

  &__title {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__count {
    margin-left: 8px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__collapse {
    margin-left: auto;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 发表框 ---------- */
.editor {
  margin-bottom: 18px;

  &__target {
    display: flex;
    align-items: center;
    margin-bottom: 6px;
    padding: 4px 10px;
    background-color: $hy-color-primary-light;
    border-radius: $hy-radius-sm;
  }

  &__target-text {
    font-size: $hy-font-xs;
    color: $hy-color-primary;
  }

  &__cancel {
    margin-left: 10px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__box {
    padding: 10px 12px;
    background-color: $hy-bg-page;
    border: 1px solid $hy-border-input;
    border-radius: $hy-radius-sm;
  }

  &__input {
    width: 100%;
    height: 72px;
    font-size: $hy-font-md;
    line-height: 1.7;
    color: $hy-text-primary;
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }

  &__foot {
    margin-top: 8px;
    display: flex;
    align-items: center;
  }

  &__counter {
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }

  &__btn {
    margin-left: auto;
    height: 32px;
    padding: 0 20px;
    display: flex;
    align-items: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-sm;
    color: $hy-text-inverse;
  }

  &__error {
    display: block;
    margin-top: 8px;
    font-size: $hy-font-xs;
    color: $hy-color-danger;
  }
}

/* ---------- 主楼 ---------- */
.item {
  display: flex;
  padding: 12px 0;
  border-top: 1px solid $hy-border-color;

  &__body {
    flex: 1;
    min-width: 0;
    margin-left: 10px;
  }

  &__head {
    display: flex;
    align-items: baseline;
  }

  &__name {
    font-size: $hy-font-sm;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__time {
    margin-left: 8px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__content {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-md;
    line-height: 1.7;
    color: $hy-text-regular;
    /* 用户输入里的换行保留下来（与帖子正文同一处理） */
    white-space: pre-wrap;
    word-break: break-word;
  }
}

/* ---------- 操作行 ---------- */
.actions {
  margin-top: 8px;
  display: flex;
  align-items: center;

  &--tight {
    margin-top: 4px;
  }
}

.act {
  display: flex;
  align-items: center;
  margin-right: 16px;
  font-size: $hy-font-xs;
  color: $hy-text-secondary;

  &__text {
    margin-left: 4px;
    font-size: $hy-font-xs;
    color: inherit;
  }

  /* 已点赞：图标与计数一起用主色，避免"只有图标变色"这种半吊子状态 */
  &--on {
    color: $hy-color-danger;

    :deep(.hy-icon) {
      color: $hy-color-danger;
    }
  }

  &--plain {
    color: $hy-text-secondary;
  }

  &--danger {
    color: $hy-color-danger;
  }
}

/* ---------- 楼中楼 ---------- */
.replies {
  margin-top: 10px;
  padding: 8px 12px;
  background-color: $hy-bg-page;
  border-radius: $hy-radius-sm;

  &__more {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-xs;
    color: $hy-color-primary;
  }
}

.reply {
  padding: 6px 0;

  &__head {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
  }

  &__name {
    font-size: $hy-font-xs;
    font-weight: 600;
    color: $hy-text-regular;
  }

  &__to {
    margin-left: 6px;
    font-size: $hy-font-xs;
    color: $hy-color-primary;
  }

  &__time {
    margin-left: 8px;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }

  &__content {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-sm;
    line-height: 1.6;
    color: $hy-text-regular;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

/* ---------- 分页 ---------- */
.more {
  padding-top: 14px;
  display: flex;
  justify-content: center;

  &__btn {
    height: 32px;
    padding: 0 22px;
    display: flex;
    align-items: center;
    border: 1px solid $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }

  &__end {
    display: block;
    padding-top: 14px;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
    text-align: center;
  }
}
</style>
