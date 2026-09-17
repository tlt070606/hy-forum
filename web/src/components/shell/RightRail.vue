<template>
  <aside class="right-rail">
    <!-- ==================== 热门话题榜 ==================== -->
    <view class="card" data-testid="hot-topic-rank">
      <view class="card__head">
        <HyIcon type="diamond" size="md" color="#ff7d00" />
        <text class="card__title">热门话题</text>
      </view>

      <view
        v-for="(topic, index) in ranking"
        :key="topic.name"
        class="rank"
        :data-testid="`rank-item-${index}`"
        @click="goTopic(topic.name)"
      >
        <!--
          名次色：前 3 名用品牌紫/暖色，其余中性灰。
          这与参考图一致，也是榜单类产品的通行做法 —— 名次本身就是信息。
        -->
        <text class="rank__no" :class="`rank__no--${rankTone(index)}`">{{ index + 1 }}</text>
        <view class="rank__body">
          <view class="rank__title-row">
            <text class="rank__name">#{{ topic.name }}</text>
            <!--
              角标是**文字块**而不是图标：参考图的「沸/热/新」本身就是文字角标，
                 而 flame/star 这类图形在纯 CSS 里代价过高（见 HyIcon 的说明）。
            -->
            <text v-if="topic.badge" class="rank__badge" :class="`rank__badge--${topic.badge}`">
              {{ BADGE_TEXT[topic.badge] }}
            </text>
          </view>
          <view class="rank__meta">
            <HyIcon type="eye" size="xs" class="rank__eye" />
            <text class="rank__views">{{ formatHeat(topic.viewsTenThousand) }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- ==================== 推荐关注 ==================== -->
    <view class="card" data-testid="recommend-people">
      <view class="card__head">
        <HyIcon type="users" size="md" color="#6b4bc4" />
        <text class="card__title">推荐关注</text>
        <text class="card__action" data-testid="recommend-shuffle" @click="shuffle">
          换一批 ›
        </text>
      </view>

      <view
        v-for="(person, index) in shownPeople"
        :key="person.nickname"
        class="person"
        :data-testid="`person-${index}`"
      >
        <Avatar :nickname="person.nickname" :size="36" />
        <view class="person__body">
          <view class="person__name-row">
            <text class="person__name">{{ person.nickname }}</text>
            <!-- 认证蓝 V：用一个小圆点 + 文字替代图形徽章，三端一致 -->
            <text v-if="person.verified" class="person__verified">V</text>
          </view>
          <text class="person__bio">{{ person.bio }}</text>
        </view>
        <view
          class="follow"
          :class="{ 'follow--done': followed.has(person.nickname) }"
          :data-testid="`follow-${index}`"
          @click="toggleFollow(person.nickname)"
        >
          <text class="follow__text">{{ followed.has(person.nickname) ? '已关注' : '关注' }}</text>
        </view>
      </view>
    </view>

    <!-- ==================== 热门活动 ==================== -->
    <view class="card" data-testid="hot-activities">
      <view class="card__head">
        <HyIcon type="diamond" size="md" color="#07c160" />
        <text class="card__title">热门活动</text>
      </view>

      <view
        v-for="(act, index) in HOT_ACTIVITIES"
        :key="act.title"
        class="activity"
        :data-testid="`activity-${index}`"
      >
        <text class="activity__title">{{ act.title }}</text>
        <text class="activity__desc">{{ act.desc }}</text>
        <text class="activity__join">{{ compactCount(act.joinCount) }} 人参与</text>
      </view>
    </view>
  </aside>
</template>

<script setup lang="ts">
/**
 * 右侧栏（参考图右栏）：热门话题榜 + 推荐关注 + 热门活动。
 *
 * ⚠️ **这一栏全部是假数据**，理由见 `mock/hotContent.ts` 顶部：
 *    话题、推荐关注、活动在本项目里**既没有数据表也没有接口**
 *    （`schema.sql` 的 16 张表里没有 `topic`/`tag` 任何一张）。
 *    要做成真的必须先加表 + 加接口，那是契约变更（L1 的事）。
 *
 * 这里唯一**真实**的行为是「关注」按钮 —— 但它也只改前端内存状态，
 * 因为 `POST /api/follow/{userId}` 不在 14 个路径的契约里（属 M4）。
 * 点它会明确提示"接口未交付"，不假装成功。
 */
import { computed, ref } from 'vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import {
  HOT_ACTIVITIES,
  HOT_TOPICS,
  RANKING_TOPIC_COUNT,
  RECOMMEND_BATCH_SIZE,
  RECOMMEND_PEOPLE,
  type TopicBadge,
} from '@/mock/hotContent'
import { compactCount, formatHeat } from '@/utils/postView'

/** 角标文案。放在组件里而不是 mock 里：它是**展示层**的映射，不是数据 */
const BADGE_TEXT: Record<TopicBadge, string> = { boil: '沸', hot: '热', new: '新' }

/** 榜单取前 N 个，**不重排** —— 顺序就是榜单顺序 */
const ranking = computed(() => HOT_TOPICS.slice(0, RANKING_TOPIC_COUNT))

function rankTone(index: number): 'top' | 'mid' | 'normal' {
  if (index === 0) return 'top'
  if (index < 3) return 'mid'
  return 'normal'
}

/* ---------------------------------------------------------------------------
 * 推荐关注：换一批 + 关注
 * ------------------------------------------------------------------------- */

/** 「换一批」的起始偏移。轮转而不是随机 —— 随机会出现"换了但一模一样" */
const batchOffset = ref(0)

const shownPeople = computed(() => {
  const total = RECOMMEND_PEOPLE.length
  const out = []
  for (let i = 0; i < RECOMMEND_BATCH_SIZE; i++) {
    out.push(RECOMMEND_PEOPLE[(batchOffset.value + i) % total])
  }
  return out
})

function shuffle(): void {
  batchOffset.value = (batchOffset.value + RECOMMEND_BATCH_SIZE) % RECOMMEND_PEOPLE.length
}

/** 已关注集合（仅前端内存；后端没有对应接口） */
const followed = ref(new Set<string>())

function toggleFollow(nickname: string): void {
  /*
   * 关注**不做假成功**：`POST /api/follow/{userId}` 不在契约里（属 M4），
   * 所以这里明确告知"未交付"，而不是把按钮切成"已关注"骗用户。
   * 这与项目既有约定一致（README §7：接口没交付时明确提示，不用假数据冒充已完成）。
   */
  if (!followed.value.has(nickname)) {
    uni.showToast({ title: '关注功能将在 M4 交付', icon: 'none', duration: 2000 })
    return
  }
  // 取消关注同样没有接口，但把已经点亮的按钮收回去是纯前端行为，可以允许
  const next = new Set(followed.value)
  next.delete(nickname)
  followed.value = next
}

function goTopic(name: string): void {
  uni.navigateTo({ url: `/pages/topic/index?name=${encodeURIComponent(name)}` })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.right-rail {
  width: $hy-shell-right-width;
  flex-shrink: 0;
}

.card {
  margin-bottom: $hy-shell-gap;
  padding: 14px 16px;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  &:last-child {
    margin-bottom: 0;
  }

  &__head {
    display: flex;
    align-items: center;
    margin-bottom: 10px;
  }

  &__title {
    margin-left: 8px;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__action {
    margin-left: auto;
    font-size: $hy-font-xs;
    color: $hy-color-primary;
  }
}

/* ---------- 榜单 ---------- */
.rank {
  display: flex;
  align-items: flex-start;
  padding: 7px 4px;
  border-radius: $hy-radius-sm;

  &:active {
    background-color: $hy-bg-hover;
  }

  &__no {
    width: 18px;
    flex-shrink: 0;
    font-size: $hy-font-md;
    font-weight: 700;
    text-align: center;

    &--top {
      color: #f53f3f;
    }
    &--mid {
      color: #ff7d00;
    }
    &--normal {
      color: $hy-text-placeholder;
      font-weight: 400;
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
    margin-left: 8px;
  }

  &__title-row {
    display: flex;
    align-items: center;
  }

  &__name {
    font-size: $hy-font-sm;
    color: $hy-text-primary;
    /* 长话题名截断，否则会把阅读数挤出卡片 */
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__badge {
    flex-shrink: 0;
    margin-left: 6px;
    padding: 0 4px;
    font-size: 10px;
    line-height: 14px;
    color: $hy-text-inverse;
    border-radius: 3px;

    &--boil {
      background-color: $hy-color-danger;
    }
    &--hot {
      background-color: $hy-color-warning;
    }
    &--new {
      background-color: $hy-color-info;
    }
  }

  &__meta {
    margin-top: 2px;
    display: flex;
    align-items: center;
  }

  &__eye {
    margin-right: 4px;
  }

  &__views {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 推荐关注 ---------- */
.person {
  display: flex;
  align-items: center;
  padding: 7px 0;

  &__body {
    flex: 1;
    min-width: 0;
    margin-left: 10px;
  }

  &__name-row {
    display: flex;
    align-items: center;
  }

  &__name {
    font-size: $hy-font-sm;
    font-weight: 600;
    color: $hy-text-primary;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__verified {
    flex-shrink: 0;
    margin-left: 5px;
    width: 14px;
    height: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 9px;
    color: $hy-text-inverse;
    background-color: $hy-color-info;
    border-radius: 50%;
  }

  &__bio {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}

.follow {
  flex-shrink: 0;
  margin-left: 8px;
  height: 28px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  border: 1px solid $hy-color-primary;
  border-radius: $hy-radius-pill;

  &--done {
    border-color: $hy-border-input;

    .follow__text {
      color: $hy-text-secondary;
    }
  }

  &__text {
    font-size: $hy-font-xs;
    color: $hy-color-primary;
  }
}

/* ---------- 热门活动 ---------- */
.activity {
  padding: 7px 0;

  &__title {
    display: block;
    font-size: $hy-font-sm;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__join {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-color-primary;
  }
}
</style>
