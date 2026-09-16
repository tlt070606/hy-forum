<template>
  <view class="page">
    <!-- ==================== 加载 / 错误 / 不存在 ==================== -->
    <HyState
      :loading="loading"
      :error="error"
      :retryable="!notFound"
      @retry="load"
    />

    <!--
      404 的特殊呈现。
      口径（任务书 §6.2 第 4 页）：**404 要给友好页而不是白屏**。
      所以这里不是简单地把 `error` 文案换成红色，而是给一个带**出口**的页面 ——
      用户的目标不是"知道帖子没了"，而是"接下来去哪"。
    -->
    <view v-if="notFound" class="gone" data-testid="post-gone">
      <text class="gone__icon">🗒️</text>
      <text class="gone__title">帖子不存在或已被删除</text>
      <text class="gone__desc">它可能已被作者删除，或从未发布成功</text>
      <!--
        ⚠️ `wd-button` 在 H5 端渲染成普通 `div`，**没有 `button` 角色** ——
        所以 E2E 不能用 `getByRole('button', { name: '回到首页' })` 定位它
        （本机实测该选择器找不到任何元素）。给它一个显式 testid。
      -->
      <wd-button
        type="primary"
        size="small"
        plain
        data-testid="post-gone-home"
        @click="goHome"
      >
        回到首页
      </wd-button>
    </view>

    <template v-if="post && !loading">
      <!-- ==================== 主内容 ==================== -->
      <view class="card">
        <!-- 待审核横幅：只在**作者自己**看到自己 status=0 的帖子时出现 -->
        <view v-if="showPending" class="pending" data-testid="post-pending">
          <text class="pending__text">
            本帖正在审核中，只有你自己能看到；审核通过后其他用户可见
          </text>
        </view>

        <view class="title-row">
          <text v-if="bool(post.isTop)" class="title-row__tag title-row__tag--top">置顶</text>
          <text v-if="bool(post.isEssence)" class="title-row__tag title-row__tag--essence">精华</text>
          <text class="title-row__text" data-testid="post-title">{{ text(post.title) }}</text>
        </view>

        <view class="meta">
          <image class="meta__avatar" :src="authorAvatar" mode="aspectFill" />
          <view class="meta__info">
            <text class="meta__author" data-testid="post-author">{{ authorName(post.author) }}</text>
            <view class="meta__sub">
              <text v-if="post.boardName" class="meta__board" data-testid="post-board">
                {{ post.boardName }}
              </text>
              <text v-if="post.boardName" class="meta__sep">·</text>
              <text class="meta__time">{{ relativeTime(post.createdAt) }}</text>
              <text class="meta__sep">·</text>
              <!-- 计数一律走 compactCount，避免 10000 位数字把这一行撑爆 -->
              <text class="meta__count">{{ compactCount(post.viewCount) }} 次浏览</text>
            </view>
          </view>
        </view>
      </view>

      <!-- ==================== 正文 ==================== -->
      <!--
        正文用 `text` 而非 `rich-text`：契约里 `content` 是**纯文本**
        （`type: string`，没有声明 HTML/Markdown），用 rich-text 渲染等于
        让后端返回的字符串被当作标记语言解释 —— 那是一条 XSS/样式注入路径。
        换行靠 CSS 的 `white-space: pre-wrap` 保留，效果与纯文本论坛一致。
      -->
      <view v-if="post.content" class="card">
        <text class="content" data-testid="post-content">{{ post.content }}</text>
      </view>

      <!-- ==================== 图片九宫格 ==================== -->
      <!-- 口径 7：只展示后端返回的 `images`，**不自己拼 URL**、不过滤、不重排 -->
      <view v-if="images.length" class="card">
        <view class="grid" data-testid="post-images">
          <image
            v-for="(img, index) in images"
            :key="img.id"
            class="grid__item"
            :src="img.thumbUrl"
            mode="aspectFill"
            :data-testid="`post-image-${index}`"
            @click="preview(index)"
          />
        </view>
        <text class="grid__count">{{ images.length }} 张图片</text>
      </view>

      <!-- ==================== 网盘卡片（资源版块） ==================== -->
      <view v-if="showDisk" class="card disk" data-testid="post-disk">
        <view class="disk__head">
          <text class="disk__type">{{ diskTypeLabel(post.diskType) }}</text>
        </view>

        <text class="disk__url" data-testid="post-disk-url">{{ post.diskUrl }}</text>

        <!-- 提取码：为空时整块不渲染（阿里云盘/夸克无提取码机制，《技术方案》§5.5 第 3 条） -->
        <view v-if="diskCode" class="disk__code-row">
          <text class="disk__code-label">提取码</text>
          <text class="disk__code" data-testid="post-disk-code">{{ diskCode }}</text>
        </view>

        <view class="disk__actions">
          <!--
            一键复制（口径 8）：文案格式由 §5.5 锁定，前端只负责复制。
            复制内容含「来自 Hy论坛」，因此用户粘到别处也能溯源。
          -->
          <wd-button
            type="primary"
            size="small"
            block
            data-testid="post-copy-disk"
            @click="copyDisk"
          >
            {{ diskCode ? '一键复制链接与提取码' : '复制链接' }}
          </wd-button>

          <!--
            打开链接：**小程序端打不开外链**（ADR-0011 C1，个人主体无 web-view 权限），
            `openExternalLink()` 会返回 false，此时改为提示用户去浏览器粘贴。
            这是三端差异的**集中封装点**，页面里不出现任何 #ifdef。
          -->
          <wd-button
            v-if="canOpenLink"
            type="info"
            size="small"
            plain
            block
            data-testid="post-open-disk"
            @click="openDisk"
          >
            在浏览器打开
          </wd-button>
        </view>

        <text v-if="!canOpenLink" class="disk__tip">
          当前环境无法直接打开外部链接，请复制后到浏览器中粘贴打开
        </text>
      </view>

      <!-- ==================== 评论区（占位，M4） ==================== -->
      <!--
        评论区**不做假 UI**：契约里没有任何评论路径（`/api/posts/{id}/comments` 等
        全在《技术方案》§6.6 的 M4 范围，`openapi.json` 14 个路径里没有）。
        按项目既定做法（README §7）：明确说明未交付，**不用假数据冒充已完成**。
      -->
      <view class="card comments" data-testid="post-comments-placeholder">
        <view class="comments__head">
          <text class="comments__title">评论</text>
          <text class="comments__count">{{ compactCount(post.commentCount) }}</text>
        </view>
        <text class="comments__todo">评论功能将在 M4 交付，当前版本暂不支持</text>
      </view>

      <!-- ==================== 作者操作 ==================== -->
      <view v-if="isAuthor" class="card actions" data-testid="post-actions">
        <text class="actions__title">作者操作</text>

        <!--
          编辑：**30 分钟内可改**（契约 `PUT /api/posts/{id}` 的约束）。
          前端这个时间判断**只是 UX**，不是准入判定 ——
          真正的判定在后端（超时返回 403）。两侧不一致时以后端为准，
          所以下面 `onEdit` 里对 403 有专门处理。
          为什么不在前端就一律放开：超时后点进去填完表单才被拒，是最差的体验。
        -->
        <wd-button
          v-if="editable"
          type="info"
          size="small"
          plain
          block
          data-testid="post-edit"
          @click="onEdit"
        >
          编辑
        </wd-button>
        <text v-else class="actions__note" data-testid="post-edit-expired">
          帖子超过 30 分钟，已不可编辑
        </text>

        <wd-button
          class="actions__danger"
          type="error"
          size="small"
          plain
          block
          data-testid="post-delete"
          @click="onDelete"
        >
          删除
        </wd-button>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 帖子详情页。
 *
 * 契约：`GET /api/posts/{id}`（**可选鉴权** —— 作者能看到自己的待审帖）、
 *       `PUT /api/posts/{id}`（改帖）、`DELETE /api/posts/{id}`（删帖）。
 *
 * ==========================================================================
 * 三处容易写错的地方（都在下面就地注释）
 * ==========================================================================
 * 1. **详情接口是可选鉴权的** → 未登录也必须发请求，不能在前端先拦成"请登录"；
 * 2. **404 要友好页，不能白屏**（任务书 §6.2 第 4 页明确要求）；
 * 3. **图片地址只来自后端**（口径 7），前端不拼 OSS 缩略图参数。
 */
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import HyState from '@/components/HyState.vue'
import { deletePost, fetchPostDetail } from '@/api/posts'
import { BIZ_CODE } from '@/utils/error-code'
import { ApiError } from '@/utils/request'
import type { PostDetailVO } from '@/api/types'
import {
  authorName,
  bool,
  buildDiskCopyText,
  compactCount,
  diskTypeLabel,
  num,
  relativeTime,
  shouldShowDiskCard,
  text,
  toImageView,
  type PostImageView,
} from '@/utils/format'
import {
  canOpenExternalLink,
  copyText,
  openExternalLink,
  shouldShowCustomToastAfterCopy,
} from '@/utils/platform'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const postId = ref(0)
const post = ref<PostDetailVO | null>(null)
const loading = ref(false)
const error = ref('')
/** 404（帖子不存在/已删除）。与普通错误分开，用于渲染友好页而不是可重试的错误条 */
const notFound = ref(false)

/* ---------------------------------------------------------------------------
 * 派生数据
 * ------------------------------------------------------------------------- */

const images = computed<PostImageView[]>(() => (post.value?.images ?? []).map(toImageView))

const authorAvatar = computed(() => post.value?.author?.avatarUrl || '/static/avatar-default.png')

const diskCode = computed(() => text(post.value?.diskCode).trim())

const showDisk = computed(() => shouldShowDiskCard(post.value))

/**
 * 待审核横幅的显示条件。
 *
 * `status === 0` 表示待审核（契约 `PostDetailVO.status` 的说明：
 * `0 待审核 / 1 正常 / 2 已屏蔽`）。
 * 但**只有作者能看到自己的待审帖**（后端已保证），非作者根本拿不到 `status=0` 的响应，
 * 所以这里不需要再判作者 —— 多一层判断只会让口径分叉。
 */
const showPending = computed(() => num(post.value?.status) === 0)

/** 当前登录用户是否为本帖作者（决定是否显示编辑/删除） */
const isAuthor = computed(() => {
  const mine = auth.user?.id
  const theirs = post.value?.author?.id
  return mine !== undefined && theirs !== undefined && mine === theirs
})

/**
 * 是否仍在可编辑窗口内（30 分钟，契约约束）。
 *
 * 边界处理：
 * - `createdAt` 缺失或解析失败 → 返回 `true`（**放手让用户试**）。
 *   理由：这是"省一次失败"的优化，不是准入判定；解析失败时挡住用户是更坏的结果
 *   （后端会正常受理，前端却把入口藏了）。
 * - 用 `>` 而不是 `>=`：正好 30 分 0 秒算超时，与后端"限发布后 30 分钟内"一致。
 */
const editable = computed(() => {
  const ts = Date.parse(text(post.value?.createdAt))
  if (Number.isNaN(ts)) return true
  return Date.now() - ts <= 30 * 60 * 1000
})

/**
 * 本端能否直接打开外部链接。
 *
 * **在 H5/App 为 true，在小程序为 false**（ADR-0011 C1：个人主体无 web-view 权限）。
 *
 * ⚠️ 这里调用 `utils/platform.ts` 的**纯能力判断函数**，页面里不出现任何 `#ifdef`
 *    —— 平台差异必须集中封装（任务书 §3.4）。
 *    也**不能**用 `openExternalLink()` 的返回值来探测能力：它在 H5 端会真的开窗口。
 */
const canOpenLink = computed(() => canOpenExternalLink())

/* ---------------------------------------------------------------------------
 * 加载
 * ------------------------------------------------------------------------- */

onLoad((options) => {
  const raw = options?.id
  const parsed = Number(raw)
  if (!raw || Number.isNaN(parsed) || parsed <= 0) {
    // 路由参数非法：当作"不存在"处理，给友好页（与后端 400 的语义区分开，
    // 因为用户根本没发出请求，谈不上"参数错误"）
    notFound.value = true
    return
  }
  postId.value = parsed
  void load()
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  notFound.value = false
  try {
    post.value = await fetchPostDetail(postId.value)
  } catch (e) {
    post.value = null
    if (e instanceof ApiError) {
      /*
       * 404 单独处理（口径：友好页而不是白屏）。
       * ⚠️ 这里**不把 404 当"内容不存在"以外的任何东西**：
       *    它也可能是"帖子存在但当前用户无权查看"（已屏蔽 → 后端按 404 处理），
       *    此时文案里的"或已被删除"已经覆盖，不需要前端猜测真实原因。
       */
      if (e.code === BIZ_CODE.NOT_FOUND) {
        notFound.value = true
      } else {
        error.value = e.message
      }
    } else {
      error.value = '帖子加载失败，请稍后重试'
    }
  } finally {
    loading.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 交互
 * ------------------------------------------------------------------------- */

/** 点击九宫格看大图（用原图 `url`，不是缩略图） */
function preview(index: number): void {
  const urls = images.value.map((img) => img.url)
  if (!urls.length) return
  uni.previewImage({
    urls,
    current: urls[index],
  })
}

/** 一键复制网盘信息（口径 8，文案格式由 §5.5 锁定） */
async function copyDisk(): Promise<void> {
  const payload = buildDiskCopyText(post.value?.diskUrl, post.value?.diskCode)
  if (!payload) {
    uni.showToast({ title: '没有可复制的网盘信息', icon: 'none' })
    return
  }

  const res = await copyText(payload)
  if (!res.ok) {
    uni.showToast({ title: '复制失败，请长按链接手动复制', icon: 'none', duration: 2500 })
    return
  }

  /*
   * 小程序端的 `setClipboardData` 会**自带**系统提示，
   * 这里再弹一次就会出现两个 toast（`utils/platform.ts` 的既定封装的用法）。
   */
  if (shouldShowCustomToastAfterCopy()) {
    uni.showToast({ title: '已复制，快去粘贴吧', icon: 'none' })
  }
}

/** 在浏览器打开网盘链接（仅 H5 / App 可用） */
function openDisk(): void {
  const url = text(post.value?.diskUrl).trim()
  if (!url) return
  const opened = openExternalLink(url)
  if (!opened) {
    // 小程序端：退回"复制 + 提示"，这不是降级 bug 而是平台硬限制
    void copyDisk()
  }
}

function goHome(): void {
  /*
   * `reLaunch` 而不是 `navigateBack`：用户可能是从搜索/分享链接直接进来的，
   * 栈里没有"上一页"，`navigateBack` 会静默失败（表现为点了没反应）。
   */
  uni.reLaunch({ url: '/pages/index/index' })
}

function onEdit(): void {
  uni.navigateTo({ url: `/pages/post/edit?id=${postId.value}` })
}

async function onDelete(): Promise<void> {
  const res = await uni.showModal({
    title: '删除帖子',
    content: '删除后不可恢复，确定删除吗？',
    confirmText: '删除',
  })
  if (!res.confirm) return

  try {
    await deletePost(postId.value)
    uni.showToast({ title: '已删除', icon: 'success' })
    /*
     * 删除成功后退回上一页。
     * ⚠️ 上一页若是版块页/首页，它们的 `onShow` 会重新拉列表 —— 这正是
     *    我们不用 `onLoad` 而用 `onShow` 加载的原因（见 index.vue 的说明）。
     *    若栈里只有本页（直接链接进入），退回失败 → 兜底回首页。
     */
    setTimeout(() => {
      const pages = getCurrentPages()
      if (pages.length > 1) {
        uni.navigateBack()
      } else {
        goHome()
      }
    }, 600)
  } catch (e) {
    uni.showToast({
      title: e instanceof ApiError ? e.message : '删除失败，请稍后重试',
      icon: 'none',
      duration: 2500,
    })
  }
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.page {
  min-height: 100vh;
  padding-bottom: $hy-space-xl;
  box-sizing: border-box;
}

.card {
  margin: $hy-space-md;
  padding: $hy-space-md;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
}

/* ---------- 404 友好页 ---------- */
.gone {
  padding: 160rpx $hy-space-lg;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__icon {
    font-size: 96rpx;
  }

  &__title {
    margin-top: $hy-space-md;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    margin: $hy-space-sm 0 $hy-space-lg;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    text-align: center;
  }
}

/* ---------- 待审横幅 ---------- */
.pending {
  margin-bottom: $hy-space-md;
  padding: $hy-space-sm $hy-space-md;
  border-radius: $hy-radius-sm;
  /* 待审是"进行中"而不是错误，用警示色而不是危险色 */
  background-color: rgba(250, 157, 59, 0.12);

  &__text {
    font-size: $hy-font-xs;
    color: $hy-color-warning;
    line-height: 1.6;
  }
}

/* ---------- 标题 ---------- */
.title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;

  &__tag {
    flex-shrink: 0;
    margin-right: $hy-space-xs;
    padding: 2rpx 10rpx;
    border-radius: $hy-radius-sm;
    font-size: $hy-font-xs;
    color: $hy-text-inverse;

    &--top {
      background-color: $hy-color-danger;
    }

    &--essence {
      background-color: $hy-color-warning;
    }
  }

  &__text {
    font-size: $hy-font-xl;
    font-weight: 600;
    color: $hy-text-primary;
    line-height: 1.4;
  }
}

/* ---------- 作者与元信息 ---------- */
.meta {
  margin-top: $hy-space-md;
  display: flex;
  align-items: center;

  &__avatar {
    width: 72rpx;
    height: 72rpx;
    border-radius: 50%;
    background-color: $hy-bg-hover;
    flex-shrink: 0;
  }

  &__info {
    margin-left: $hy-space-sm;
    min-width: 0;
    flex: 1;
  }

  &__author {
    font-size: $hy-font-sm;
    color: $hy-text-regular;
  }

  &__sub {
    margin-top: 2rpx;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__board {
    color: $hy-color-primary;
  }

  &__sep {
    margin: 0 8rpx;
  }
}

/* ---------- 正文 ---------- */
.content {
  font-size: $hy-font-md;
  color: $hy-text-regular;
  line-height: 1.7;
  /* 契约里 content 是纯文本，换行符靠 pre-wrap 保留（配合 <text> 使用） */
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 九宫格 ---------- */
.grid {
  display: flex;
  flex-wrap: wrap;

  &__item {
    /* 三列：每列 1/3 宽，靠 margin 制造间隙。
       用 calc 而不是 gap，兼容性更好（小程序旧基础库对 flex gap 支持不全） */
    width: calc(33.33% - 8rpx);
    height: 220rpx;
    margin: 0 12rpx 12rpx 0;
    border-radius: $hy-radius-sm;
    background-color: $hy-bg-hover;

    /* 每行第三张去掉右边距，保证右边缘对齐 */
    &:nth-child(3n) {
      margin-right: 0;
    }
  }

  &__count {
    display: block;
    margin-top: $hy-space-xs;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 网盘卡片 ---------- */
.disk {
  border: 1rpx solid rgba(43, 108, 255, 0.25);
  background-color: $hy-color-primary-light;

  &__head {
    display: flex;
    align-items: center;
  }

  &__type {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-color-primary;
  }

  &__url {
    display: block;
    margin-top: $hy-space-sm;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    /* 长链接必须能换行，否则会把卡片撑出屏幕 */
    word-break: break-all;
  }

  &__code-row {
    margin-top: $hy-space-sm;
    display: flex;
    align-items: center;
  }

  &__code-label {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }

  &__code {
    margin-left: $hy-space-sm;
    padding: 2rpx 16rpx;
    border-radius: $hy-radius-sm;
    font-size: $hy-font-md;
    font-weight: 600;
    letter-spacing: 2rpx;
    color: $hy-text-primary;
    background-color: $hy-bg-card;
  }

  &__actions {
    margin-top: $hy-space-md;
    display: flex;
    flex-direction: column;
    gap: $hy-space-sm;
  }

  &__tip {
    display: block;
    margin-top: $hy-space-sm;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    line-height: 1.6;
  }
}

/* ---------- 评论区占位 ---------- */
.comments {
  &__head {
    display: flex;
    align-items: baseline;
  }

  &__title {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__count {
    margin-left: $hy-space-sm;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__todo {
    display: block;
    margin-top: $hy-space-sm;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }
}

/* ---------- 作者操作 ---------- */
.actions {
  &__title {
    display: block;
    margin-bottom: $hy-space-sm;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }

  &__note {
    display: block;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }

  &__danger {
    margin-top: $hy-space-sm;
  }
}
</style>
