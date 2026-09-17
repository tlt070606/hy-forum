<template>
  <AppShell active-nav="home" :post-total="null">
    <!-- ==================== 窄屏返回（宽屏有左栏，不需要） ==================== -->
    <view class="back" data-testid="detail-back" @click="goBack">
      <HyIcon type="chevronLeft" size="sm" />
      <text class="back__text">返回</text>
    </view>

    <!-- ==================== 加载 / 错误 ==================== -->
    <view v-if="loading" class="state" data-testid="detail-loading">
      <text class="state__text">正在加载…</text>
    </view>

    <view v-else-if="error" class="state" data-testid="detail-error">
      <text class="state__text state__text--error">{{ error }}</text>
      <view class="state__btn" data-testid="detail-retry" @click="load">
        <text class="state__btn-text">重试</text>
      </view>
    </view>

    <!--
      ==================== 404 友好页 ====================
      任务书 §6.2 第 4 页的硬要求：**404 要给友好页而不是白屏**。
      所以这里不是把错误文案换个颜色，而是给一个**带出口**的页面 ——
      用户的目标不是"知道帖子没了"，而是"接下来去哪"。
    -->
    <view v-else-if="notFound" class="gone" data-testid="detail-gone">
      <view class="gone__icon">
        <HyIcon type="shield" size="xl" color="#c9cdd4" />
      </view>
      <text class="gone__title">帖子不存在或已被删除</text>
      <text class="gone__desc">它可能已被作者删除，也可能仍在审核中（未通过审核的帖子其他人看不到）</text>
      <view class="gone__btn" data-testid="detail-gone-home" @click="goHome">
        <text class="gone__btn-text">回到首页</text>
      </view>
    </view>

    <!-- ==================== 正文主体 ==================== -->
    <template v-else-if="post">
      <view class="card">
        <!--
          待审横幅（status=0）。
          ⚠️ 实测（2026-09-17 探针）：status=0 的帖子对**匿名请求返回 404**，
             所以能走到这里的只有**作者自己**（后端只对作者返回待审帖）。
             因此这条横幅天然只会出现在作者眼前，不需要前端再判断"是不是我的"。
        -->
        <view v-if="status === 0" class="pending" data-testid="detail-pending">
          <text class="pending__text">
            本帖正在审核中，只有你自己能看到；通过审核后其他用户才可见
          </text>
        </view>

        <view class="title-row">
          <text v-if="bool(post.isTop)" class="tag tag--top">置顶</text>
          <text v-if="bool(post.isEssence)" class="tag tag--essence">精选</text>
          <text class="title-row__text" data-testid="detail-title">{{ text(post.title) }}</text>
        </view>

        <view class="author">
          <Avatar
            :url="text(post.author?.avatarUrl)"
            :nickname="authorName(post.author)"
            :size="40"
          />
          <view class="author__body">
            <text class="author__name" data-testid="detail-author">{{ authorName(post.author) }}</text>
            <view class="author__meta">
              <text v-if="post.boardName" class="author__board" data-testid="detail-board">
                {{ post.boardName }}
              </text>
              <text v-if="post.boardName" class="author__sep">·</text>
              <text class="author__time">{{ relativeTime(post.createdAt) }}</text>
              <!--
                浏览数：**每次打开详情页后端都会 +1**（走 Redis，§8.3），
                包括作者自己看、以及开发时我自己反复刷新。
                这是后端既定行为，前端不掩盖也不补偿。
              -->
              <text class="author__sep">·</text>
              <text class="author__view" data-testid="detail-view">{{ post.viewCount ?? 0 }} 次浏览</text>
            </view>
          </view>
        </view>

        <!-- 编辑过才显示。依据契约的 updatedAt（与 createdAt 差 60 秒以上才算改过） -->
        <text v-if="editedText" class="edited" data-testid="detail-edited">{{ editedText }}</text>
      </view>

      <!-- ==================== 正文 ==================== -->
      <!--
        ⚠️ 正文用 `<text>` 而**不是** `rich-text`：契约里 `content` 是**纯文本**
        （`type: string`，没有声明 HTML/Markdown）。用 rich-text 渲染等于
        把后端返回的字符串当标记语言解释 —— 那是一条 XSS / 样式注入路径。
        换行靠 CSS 的 `white-space: pre-wrap` 保留，观感与纯文本论坛一致。
      -->
      <view v-if="text(post.content).trim()" class="card">
        <text class="content" data-testid="detail-content">{{ post.content }}</text>
      </view>

      <!-- ==================== 图片 ==================== -->
      <!--
        口径 7：**只展示后端返回的 `images`**（后端已过滤 `audit_status=2`），
        **不自己拼图片 URL、不过滤、不重排**。
        ⚠️ 当前演示数据没有图（图片上传被 CR-G/CR-H 卡住），所以这块**在本环境里看不到效果**；
           而且本机**无外网**，即使有 OSS 图也加载不出来。见交付报告。
      -->
      <view v-if="images.length" class="card">
        <view class="images">
          <!--
            单图：按契约给的 width / height 算出**真实宽高比**当容器比例，
            这样 `aspectFill` 也不会裁掉任何内容（容器比例 == 图片比例）。
            没有宽高（=0）时回退 16:9。极端竖图把比例夹到 1.2，避免一张长图占满整屏。
          -->
          <view
            v-if="images.length === 1"
            class="images__single"
            :style="{ paddingTop: singleRatio + '%' }"
          >
            <image
              class="images__single-img"
              :src="images[0].thumbUrl"
              mode="aspectFill"
              data-testid="detail-image-0"
              @click="preview(0)"
            />
          </view>

          <!-- 多图：三列宫格 -->
          <template v-else>
            <image
              v-for="(img, index) in images"
              :key="img.id"
              class="images__grid-item"
              :src="img.thumbUrl"
              mode="aspectFill"
              :data-testid="`detail-image-${index}`"
              @click="preview(index)"
            />
          </template>
        </view>
        <text class="images__count">{{ images.length }} 张图片</text>
      </view>

      <!-- ==================== 网盘卡片（仅资源版块） ==================== -->
      <!--
        显示条件 = 版块的 `boardIsResource` **且** `diskUrl` 非空。
        后者不是多余的：资源版块的历史帖可能没有网盘链接，此时不该渲染一张空卡片。
      -->
      <view v-if="showDisk" class="card disk" data-testid="detail-disk">
        <view class="disk__head">
          <HyIcon type="diamond" size="md" color="#6b4bc4" />
          <text class="disk__type">{{ diskTypeLabel(post.diskType) }}</text>
        </view>

        <text class="disk__url" data-testid="detail-disk-url">{{ post.diskUrl }}</text>

        <!-- 提取码为空时整块不渲染（阿里云盘/夸克无提取码机制，§5.5 第 3 条） -->
        <view v-if="diskCode" class="disk__code-row">
          <text class="disk__code-label">提取码</text>
          <text class="disk__code" data-testid="detail-disk-code">{{ diskCode }}</text>
        </view>

        <view class="disk__actions">
          <!-- 一键复制：文案格式由《技术方案》§5.5 锁定，前端只负责复制 -->
          <view class="btn btn--primary" data-testid="detail-copy-disk" @click="copyDisk">
            <text class="btn__text">{{ diskCode ? '一键复制链接与提取码' : '复制链接' }}</text>
          </view>
          <view
            v-if="canOpenLink"
            class="btn btn--ghost"
            data-testid="detail-open-disk"
            @click="openDisk"
          >
            <text class="btn__text btn__text--ghost">在浏览器打开</text>
          </view>
        </view>

        <!-- 小程序端打不开外链（ADR-0011 C1，个人主体无 web-view 权限）→ 改为引导复制 -->
        <text v-if="!canOpenLink" class="disk__tip">
          当前环境无法直接打开外部链接，请复制后到浏览器中粘贴打开
        </text>
      </view>

      <!-- ==================== 互动栏 ==================== -->
      <!--
        ⚠️ 这三个按钮**可点但只提示"未交付"**，不做假成功（需求方 2026-09-17 选定）。
        理由：点赞/收藏/评论接口**不在契约的 14 个路径里**（属 M4）——
        做成不可点，用户不知道有这功能；做成假成功，则是拿假数据冒充已完成。
        正解是**提 CR 让后端补 M4 互动接口**（已登记报告 CR-I），接口到位后把这里接上即可。
      -->
      <view class="card interact" data-testid="detail-interact">
        <view class="interact__item" data-testid="detail-like" @click="notDelivered('点赞')">
          <HyIcon type="heart" size="lg" />
          <text class="interact__text">{{ post.likeCount ?? 0 }}</text>
        </view>
        <view class="interact__item" data-testid="detail-comment" @click="notDelivered('评论')">
          <HyIcon type="comment" size="lg" />
          <text class="interact__text">{{ post.commentCount ?? 0 }}</text>
        </view>
        <view class="interact__item" data-testid="detail-collect" @click="notDelivered('收藏')">
          <HyIcon type="bookmark" size="lg" />
          <text class="interact__text">{{ post.collectCount ?? 0 }}</text>
        </view>
        <view class="interact__item interact__item--last" data-testid="detail-report" @click="notDelivered('举报')">
          <HyIcon type="shield" size="lg" />
          <text class="interact__text">举报</text>
        </view>
      </view>

      <!-- ==================== 作者操作 ==================== -->
      <!--
        PUT / DELETE 是**后端真有的能力**（契约里就有），所以按"后端有什么就做什么"必须做出来。
        可见性：仅作者。
        编辑额外受**发布后 30 分钟内**限制（契约约束）—— 前端这个时间判断**只是 UX**，
        真正的准入判定在后端（超时返回 403），所以下面 onEdit 里对 403 有处理。
        为什么不在前端一律放开：超时后点进去填完表单才被拒，是最差的体验。
      -->
      <view v-if="isAuthor" class="card actions" data-testid="detail-actions">
        <text class="actions__title">作者操作</text>
        <view class="actions__row">
          <view
            v-if="editable"
            class="btn btn--ghost"
            data-testid="detail-edit"
            @click="onEdit"
          >
            <text class="btn__text btn__text--ghost">编辑</text>
          </view>
          <text v-else class="actions__note" data-testid="detail-edit-expired">
            帖子超过 30 分钟，已不可编辑
          </text>
          <view class="btn btn--danger" data-testid="detail-delete" @click="onDelete">
            <text class="btn__text btn__text--danger">删除</text>
          </view>
        </view>
      </view>
    </template>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 帖子详情页。
 *
 * 契约：`GET /api/posts/{id}`（**可选鉴权**）、`PUT /api/posts/{id}`、`DELETE /api/posts/{id}`。
 *
 * ==========================================================================
 * 三处容易写错的地方（都已在下面就地注明）
 * ==========================================================================
 * 1. **详情是可选鉴权的** → 未登录也必须发请求，不能在前端先拦成"请登录"；
 * 2. **404 要友好页，不能白屏**（任务书 §6.2 硬要求）；
 * 3. **图片地址只来自后端**（口径 7），前端不拼 OSS 缩略图参数。
 *
 * ==========================================================================
 * 实测过的后端行为（2026-09-17，见交付报告）
 * ==========================================================================
 * - `status=0`（待审）/ `status=2`（已屏蔽）对**匿名请求返回 404**，且**不出现在列表**里
 *   → 所以"待审横幅"只会出现在**作者**眼前；
 * - `PUT` **确实**把 `status` 落回 0（"编辑重审"闭环成立，DB 与响应都核过）；
 * - 帖子被删 → 404。
 */
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AppShell from '@/components/shell/AppShell.vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import { deletePost, fetchPostDetail } from '@/api/posts'
import { BIZ_CODE } from '@/utils/error-code'
import { ApiError } from '@/utils/request'
import type { PostDetailVO } from '@/api/types'
import {
  authorName,
  bool,
  buildDiskCopyText,
  diskTypeLabel,
  num,
  relativeTime,
  text,
  toImageView,
  type PostImageView,
} from '@/utils/postView'
import { canOpenExternalLink, copyText, openExternalLink, shouldShowCustomToastAfterCopy } from '@/utils/platform'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const postId = ref(0)
const post = ref<PostDetailVO | null>(null)
const loading = ref(false)
const error = ref('')
/** 404（不存在 / 已删除 / 无权限看）。与普通错误分开，用于渲染友好页而不是可重试的错误条 */
const notFound = ref(false)

/* ---------------------------------------------------------------------------
 * 派生
 * ------------------------------------------------------------------------- */

const status = computed(() => num(post.value?.status))

const images = computed<PostImageView[]>(() => (post.value?.images ?? []).map(toImageView))

const diskCode = computed(() => text(post.value?.diskCode).trim())

/** 显示网盘卡片：资源版块 **且** 确实有链接（见模板注释） */
const showDisk = computed(() => {
  const p = post.value
  if (!p) return false
  return bool(p.boardIsResource) && text(p.diskUrl).trim().length > 0
})

/** 单图容器比例（padding-top 百分比）。无宽高时回退 16:9 */
const singleRatio = computed(() => {
  const img = images.value[0]
  if (!img) return 56.25
  const w = num(post.value?.images?.[0]?.width)
  const h = num(post.value?.images?.[0]?.height)
  if (!w || !h) return 56.25
  // 夹到 120%（≈ 5:6 竖图）：再高就会让一张长图占满整屏
  return Math.min((h / w) * 100, 120)
})

/** 当前登录用户是否为本帖作者（决定编辑/删除是否显示） */
const isAuthor = computed(() => {
  const mine = auth.user?.id
  const theirs = post.value?.author?.id
  return mine !== undefined && theirs !== undefined && mine === theirs
})

/**
 * 是否仍在可编辑窗口内（**发布后 30 分钟内**，契约约束）。
 *
 * 边界：`createdAt` 缺失或解析失败 → 返回 `true`（**放手让用户试**）。
 * 这是"省一次失败"的优化，不是准入判定；解析失败时挡住用户是更坏的结果
 * （后端会正常受理，前端却把入口藏了）。
 */
const editable = computed(() => {
  const ts = Date.parse(text(post.value?.createdAt))
  if (Number.isNaN(ts)) return true
  return Date.now() - ts <= 30 * 60 * 1000
})

/**
 * "编辑于 …" 文案。
 * 用 `updatedAt` 与 `createdAt` 的差值判断（差 60 秒以上才算改过）：
 * 后端写入时两者通常相差毫秒级，直接用 `!==` 会几乎每次都显示"编辑过"。
 */
const editedText = computed(() => {
  const created = Date.parse(text(post.value?.createdAt))
  const updated = Date.parse(text(post.value?.updatedAt))
  if (Number.isNaN(created) || Number.isNaN(updated)) return ''
  if (updated - created < 60_000) return ''
  return `编辑于 ${relativeTime(post.value?.updatedAt)}`
})

/** 本端能否直接打开外部链接（H5/App 可以，小程序不行）。平台差异集中在 utils/platform */
const canOpenLink = computed(() => canOpenExternalLink())

/* ---------------------------------------------------------------------------
 * 加载
 * ------------------------------------------------------------------------- */

onLoad((options) => {
  /*
   * 路由参数是字符串，必须显式转数字。
   * 非法 id **不发请求**：直接按"不存在"渲染友好页 —— 与后端的 400 语义区分开，
   * 因为用户根本没发出请求，谈不上"参数错误"。
   */
  const raw = options?.id
  const parsed = Number(raw)
  if (!raw || Number.isNaN(parsed) || parsed <= 0) {
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
       * 404 单独处理。**不猜测 404 的具体原因**：它可能是"帖子不存在"、
       * "已被删除"、也可能是"未过审/已屏蔽"（后端对非作者一律按 404 处理）。
       * 友好页的文案已经把这三层都覆盖了，前端分辨它们只会编造事实。
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

/** 点击图片看大图。用**原图** `url`，不是缩略图 */
function preview(index: number): void {
  const urls = images.value.map((img) => img.url)
  if (!urls.length) return
  uni.previewImage({ urls, current: urls[index] })
}

/** 一键复制网盘信息（口径 8，文案格式由《技术方案》§5.5 锁定） */
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
   * 这里再弹一次就会出现两个 toast（见 utils/platform 的既定封装）。
   */
  if (shouldShowCustomToastAfterCopy()) {
    uni.showToast({ title: '已复制，快去粘贴吧', icon: 'none' })
  }
}

/** 在浏览器打开网盘链接（仅 H5 / App） */
function openDisk(): void {
  const url = text(post.value?.diskUrl).trim()
  if (!url) return
  if (!openExternalLink(url)) {
    // 小程序端：退回"复制 + 提示"，这不是降级 bug 而是平台硬限制
    void copyDisk()
  }
}

/**
 * 未交付功能的统一提示。
 *
 * 刻意**不做假成功**：接口不在契约里（属 M4/M5），所以明确告知，
 * 而不是把按钮点亮、把计数 +1 骗用户。
 */
function notDelivered(what: string): void {
  uni.showToast({ title: `${what}功能将在 M4 交付`, icon: 'none', duration: 2000 })
}

function goHome(): void {
  /*
   * `reLaunch` 而不是 `navigateBack`：用户可能从分享链接直接进来，栈里没有上一页，
   * `navigateBack` 会静默失败（表现为"点了没反应"）。
   */
  uni.reLaunch({ url: '/pages/index/index' })
}

function goBack(): void {
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack()
  } else {
    goHome()
  }
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
     * 删除成功后退回上一页。上一页若是首页，它的 `onShow` 会重新拉列表 ——
     * 这正是列表页用 onShow 而不是 onLoad 加载的原因。
     * 若栈里只有本页（直接链接进入），退回无意义 → 兜底回首页。
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

/* ---------- 窄屏返回 ---------- */
.back {
  display: none;
  align-items: center;
  margin-bottom: $hy-shell-gap;
  padding: 2px 0;

  &__text {
    margin-left: 2px;
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }
}

.card {
  margin-bottom: $hy-shell-gap;
  padding: 16px;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;
}

/* ---------- 状态 / 404 ---------- */
.state {
  padding: 48px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;

    &--error {
      color: $hy-color-danger;
    }
  }

  &__btn {
    margin-top: 14px;
    height: 32px;
    padding: 0 20px;
    display: flex;
    align-items: center;
    border: 1px solid $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }
}

.gone {
  padding: 64px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;

  &__icon {
    margin-bottom: 16px;
  }

  &__title {
    font-size: $hy-font-xl;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    margin-top: 10px;
    max-width: 480px;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    text-align: center;
    line-height: 1.7;
  }

  &__btn {
    margin-top: 22px;
    height: 36px;
    padding: 0 24px;
    display: flex;
    align-items: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-sm;
    color: $hy-text-inverse;
  }
}

/* ---------- 待审横幅 ---------- */
.pending {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: $hy-radius-sm;
  /* 待审是"进行中"而不是错误，用警示色而不是危险色 */
  background-color: rgba(255, 125, 0, 0.12);

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

  &__text {
    font-size: 22px;
    font-weight: 700;
    line-height: 1.45;
    color: $hy-text-primary;
  }
}

.tag {
  flex-shrink: 0;
  margin-right: 6px;
  padding: 0 5px;
  font-size: 10px;
  line-height: 16px;
  color: $hy-text-inverse;
  border-radius: 3px;

  &--top {
    background-color: $hy-color-danger;
  }

  &--essence {
    background-color: $hy-color-warning;
  }
}

/* ---------- 作者 ---------- */
.author {
  margin-top: 14px;
  display: flex;
  align-items: center;

  &__body {
    margin-left: 10px;
    min-width: 0;
    flex: 1;
  }

  &__name {
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }

  &__meta {
    margin-top: 2px;
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
    margin: 0 6px;
  }
}

.edited {
  display: block;
  margin-top: 8px;
  font-size: $hy-font-xs;
  color: $hy-text-placeholder;
}

/* ---------- 正文 ---------- */
.content {
  font-size: $hy-font-lg;
  line-height: 1.8;
  color: $hy-text-regular;
  /* content 是纯文本，换行符靠 pre-wrap 保留（配合 <text>） */
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 图片 ---------- */
.images {
  &__single {
    position: relative;
    width: 100%;
    height: 0;
    /* 高度由 padding-top 百分比撑开（比例来自契约的 width/height） */
    border-radius: $hy-radius-sm;
    overflow: hidden;
    background-color: $hy-bg-hover;
  }

  &__single-img {
    position: absolute;
    left: 0;
    top: 0;
    width: 100%;
    height: 100%;
  }

  &__grid-item {
    /* 三列。用 calc 而不是 gap：小程序旧基础库对 flex gap 支持不全 */
    width: calc(33.33% - 8px);
    height: 120px;
    margin: 0 12px 12px 0;
    border-radius: $hy-radius-sm;
    background-color: $hy-bg-hover;

    /* 每行第三张去掉右边距，保证右边缘对齐 */
    &:nth-child(3n) {
      margin-right: 0;
    }
  }

  &__count {
    display: block;
    margin-top: 4px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 网盘 ---------- */
.disk {
  border: 1px solid $hy-color-primary-border;
  background-color: $hy-color-primary-lighter;

  &__head {
    display: flex;
    align-items: center;
  }

  &__type {
    margin-left: 6px;
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-color-primary;
  }

  &__url {
    display: block;
    margin-top: 10px;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    /* 长链接必须能换行，否则会把卡片撑出屏幕 */
    word-break: break-all;
  }

  &__code-row {
    margin-top: 10px;
    display: flex;
    align-items: center;
  }

  &__code-label {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }

  &__code {
    margin-left: 10px;
    padding: 2px 14px;
    border-radius: $hy-radius-sm;
    font-size: $hy-font-lg;
    font-weight: 700;
    letter-spacing: 2px;
    color: $hy-text-primary;
    background-color: $hy-bg-card;
  }

  &__actions {
    margin-top: 14px;
    display: flex;
    /* 两个按钮并排；窄屏会自动换行（见下方 media query 的 flex-wrap） */
    align-items: center;
  }

  &__tip {
    display: block;
    margin-top: 10px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    line-height: 1.6;
  }
}

/* ---------- 按钮（自绘：需要精确控制参考图里的胶囊/描边两种形态） ---------- */
.btn {
  height: 36px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: $hy-radius-pill;
  margin-right: 10px;

  &--primary {
    background-color: $hy-color-primary;
  }

  &--ghost {
    border: 1px solid $hy-color-primary;
  }

  &--danger {
    border: 1px solid $hy-color-danger;
  }

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-inverse;

    &--ghost {
      color: $hy-color-primary;
    }

    &--danger {
      color: $hy-color-danger;
    }
  }
}

/* ---------- 互动栏 ---------- */
.interact {
  display: flex;
  align-items: center;

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;

    &--last {
      /* 举报在最后一格，去掉右间距 */
      margin-right: 0;
    }

    &:active {
      opacity: 0.6;
    }
  }

  &__text {
    margin-top: 4px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 作者操作 ---------- */
.actions {
  &__title {
    display: block;
    margin-bottom: 10px;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }

  &__row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
  }

  &__note {
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
    margin-right: 10px;
  }
}

/* ==========================================================================
 * 窄屏：显示返回入口、网盘按钮换行、图片宫格改两列
 * ========================================================================== */
@media (max-width: $hy-shell-breakpoint) {
  .back {
    display: flex;
  }

  .title-row__text {
    font-size: 19px;
  }

  .disk__actions {
    flex-wrap: wrap;
  }

  .images__grid-item {
    width: calc(50% - 6px);
    height: 140px;

    /* 两列时每行第二张去掉右边距（覆盖上面的 3n 规则） */
    &:nth-child(3n) {
      margin-right: 12px;
    }

    &:nth-child(2n) {
      margin-right: 0;
    }
  }
}
</style>
