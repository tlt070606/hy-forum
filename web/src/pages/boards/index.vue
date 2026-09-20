<template>
  <AppShell active-nav="boards">
    <view class="boards-page">
      <view class="card head">
        <view class="head__back" data-testid="boards-back" @click="goBack">
          <HyIcon type="chevronLeft" size="md" />
          <text class="head__back-text">返回</text>
        </view>
        <text class="head__title">版块</text>
        <text class="head__count" data-testid="boards-total">{{ boards.length }}</text>
      </view>

      <ListState
        :loading="loading"
        :error="error"
        :empty="boards.length === 0"
        empty-text="还没有版块"
        loading-text="正在加载版块…"
        testid-base="boards-state"
        @retry="load"
      />

      <!--
        版块卡片。**顺序就是接口给的顺序**（`GET /api/boards` 带 `sort` 字段）——
        前端不重排：排序是服务端的事，前端再排一次会出现"界面上看到的顺序与接口不一致"。
      -->
      <view
        v-for="b in boards"
        :key="b.id"
        class="card board"
        data-testid="board-card"
        :data-board-id="b.id"
        @click="openBoard(b.id)"
      >
        <view class="board__main">
          <view class="board__name-row">
            <text class="board__name" data-testid="board-name">{{ b.name }}</text>
            <!-- 资源版块：发帖时要填网盘链接，这个标记是**有实际含义**的（契约的 isResource） -->
            <text v-if="b.isResource" class="board__tag" data-testid="board-resource">资源版块</text>
          </view>
          <text v-if="b.description" class="board__desc" data-testid="board-desc">{{ b.description }}</text>
        </view>
        <view class="board__right">
          <text class="board__count" data-testid="board-post-count">
            {{ compactCount(b.postCount ?? 0) }}
          </text>
          <text class="board__count-label">帖</text>
          <HyIcon type="chevron" size="md" color="#c9cdd4" />
        </view>
      </view>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 版块总览（需求方 2026-09-18：左栏「话题」去掉，**换成有后端接口的「版块」**）。
 *
 * 数据全部来自 `GET /api/boards`（真接口、免登录）：
 * `BoardVO = { id, name, slug, description, iconUrl, sort, postCount, isResource }`。
 *
 * ⚠️ 为什么它比原来的「话题」页有价值：
 *    话题在本项目里**既没有表也没有接口**（`schema.sql` 的 16 张表里没有 topic/tag），
 *    所以那一页只能是占位页；而版块是**真实存在的一等公民** ——
 *    发帖要选版块、帖子列表能按版块筛、资源版块还要填网盘字段。
 *    换句话说：这一页让"按版块浏览"这条真实路径第一次有了入口。
 *
 * ⚠️ 契约里**没有**"全部版块的分页/排序接口参数" —— 它一次返回全部（实测 7 个），
 *    所以这里不做分页（做了也是假的）。
 */
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import AppShell from '@/components/shell/AppShell.vue'
import HyIcon from '@/components/HyIcon.vue'
import ListState from '@/components/ListState.vue'
import { fetchBoards } from '@/api/boards'
import { ApiError } from '@/utils/request'
import { compactCount } from '@/utils/postView'
import type { BoardVO } from '@/api/types'

const boards = ref<BoardVO[]>([])
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    boards.value = await fetchBoards()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '版块加载失败，请稍后重试'
    boards.value = []
  } finally {
    loading.value = false
  }
}

/* 用 onShow：从某个版块页返回时，帖子数可能变了（刚发过帖），回来要看到新的数 */
onShow(() => {
  void load()
})

/**
 * 打开版块页。
 * ⚠️ 参数写成可选：契约生成的类型里**所有字段都是可选的**（`id?: number`），
 *    所以 `b.id` 的类型是 `number | undefined`。这里显式判空，
 *    而不是用 `b.id!` 断言 —— 断言会把这个不确定性藏起来，
 *    将来真给了没有 id 的数据就会跳到 `?id=undefined`。
 */
function openBoard(id?: number): void {
  if (!id) return
  uni.navigateTo({ url: `/pages/board/index?id=${id}` })
}

function goBack(): void {
  uni.navigateBack()
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.head {
  display: flex;
  align-items: center;

  &__back {
    display: flex;
    align-items: center;
  }

  &__back-text {
    margin-left: 4px;
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }

  &__title {
    margin-left: 12px;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__count {
    margin-left: 8px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

.board {
  display: flex;
  align-items: center;

  &__main {
    flex: 1;
    min-width: 0;
  }

  &__name-row {
    display: flex;
    align-items: center;
  }

  &__name {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__tag {
    margin-left: 8px;
    padding: 2px 8px;
    font-size: $hy-font-xs;
    color: $hy-color-primary;
    background-color: $hy-color-primary-light;
    border-radius: $hy-radius-sm;
  }

  &__desc {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-sm;
    line-height: 1.6;
    color: $hy-text-secondary;
  }

  &__right {
    margin-left: 12px;
    display: flex;
    align-items: center;
  }

  &__count {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__count-label {
    margin: 0 8px 0 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}
</style>
