<template>
  <!--
    举报弹层（底部弹出）。
    ⚠️ 用 `position: fixed` 的遮罩 + 面板自绘，而不是 `uni.showModal`：
    原生弹窗只能放两行文字，塞不下"5 个原因 + 补充说明"。三端都能用这一套。
  -->
  <view v-if="open" class="sheet" data-testid="report-sheet">
    <!-- 点遮罩关闭；`@click.stop` 在面板上，避免点面板内部也关掉 -->
    <view class="sheet__mask" data-testid="report-mask" @click="close" />
    <view class="sheet__panel">
      <view class="sheet__head">
        <text class="sheet__title">举报{{ targetLabel }}</text>
        <view class="sheet__close" data-testid="report-close" @click="close">
          <HyIcon type="close" size="md" />
        </view>
      </view>

      <text class="sheet__hint">请选择举报原因（必选）</text>
      <view class="reasons" data-testid="report-reasons">
        <view
          v-for="r in REPORT_REASONS"
          :key="r.value"
          class="reasons__item"
          :class="{ 'reasons__item--active': reason === r.value }"
          :data-testid="`report-reason-${r.value}`"
          :data-active="reason === r.value ? '1' : '0'"
          @click="reason = r.value"
        >
          <text class="reasons__text">{{ r.label }}</text>
        </view>
      </view>

      <text class="sheet__hint">补充说明（可选，最多 200 字）</text>
      <view class="sheet__input">
        <textarea
          v-model="detail"
          class="sheet__textarea"
          :maxlength="200"
          placeholder="说说具体情况，方便我们判断"
          placeholder-class="sheet__placeholder"
          data-testid="report-detail"
        />
      </view>

      <!--
        提交按钮：未选原因时**禁用并说明**，而不是让它看起来能点却报错。
        ⚠️ 不做"假成功"：只有后端返回成功才提示成功并关闭；失败把错误原因显示出来。
      -->
      <view
        class="sheet__submit"
        :class="{ 'sheet__submit--disabled': !reason || submitting }"
        :data-disabled="!reason || submitting ? '1' : '0'"
        data-testid="report-submit"
        @click="submit"
      >
        <text class="sheet__submit-text">{{ submitting ? '提交中…' : '提交举报' }}</text>
      </view>
      <text v-if="error" class="sheet__error" data-testid="report-error">{{ error }}</text>
      <text class="sheet__note">
        举报会提交给管理员处理。恶意举报可能被限制（同一账号每天有次数上限）。
      </text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 举报弹层（M5）。详情页举报**帖子**、评论区举报**评论**都用这一个组件。
 *
 * 契约：`POST /api/report`，`ReportCreateRequest = { targetType*, targetId*, reasonType*, reasonDetail? }`
 * - `targetType`：1 帖子 / 2 评论 / 3 用户（取值来自契约 description，见 `api/report.ts`）
 * - `reasonType`：5 类固定选项（同上）
 *
 * ⚠️ 限流属**业务动作维度** → 超限是 **HTTP 200 + 业务码**（不是 429），
 *    所以这里不去判断 429，交给 `request` 层按错误码表给文案。
 */
import { ref, watch } from 'vue'
import HyIcon from '@/components/HyIcon.vue'
import { REPORT_REASONS, submitReport } from '@/api/report'
import { ApiError } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  /** 是否打开（配合 `v-model` 用） */
  open: boolean
  /** 1 帖子 / 2 评论 / 3 用户 */
  targetType: number
  targetId: number
  /** 展示用目标名（"帖子"/"评论"/"用户"），由调用方给准 */
  targetLabel: string
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  /** 提交成功（调用方可以据此做点别的，例如刷新） */
  submitted: [id: number]
}>()

const auth = useAuthStore()

const reason = ref(0)
const detail = ref('')
const submitting = ref(false)
const error = ref('')

/**
 * 每次打开都清空上一次的选择。
 * ⚠️ 不清的话会出现"我上次选的违法违规还亮着"，用户直接点提交就报错报到了错的原因上。
 */
watch(
  () => props.open,
  (v) => {
    if (v) {
      reason.value = 0
      detail.value = ''
      error.value = ''
    }
  }
)

function close(): void {
  if (submitting.value) return
  emit('update:open', false)
}

async function submit(): Promise<void> {
  error.value = ''
  if (!reason.value) {
    error.value = '请先选择举报原因'
    return
  }
  // 未登录：举报需要登录（后端会拒 401），先引导，别让用户白填一段说明
  if (!auth.isLoggedIn) {
    uni.showToast({ title: '请先登录后再举报', icon: 'none' })
    setTimeout(() => uni.navigateTo({ url: '/pages/auth/index?mode=login' }), 700)
    return
  }

  submitting.value = true
  try {
    const id = await submitReport({
      targetType: props.targetType,
      targetId: props.targetId,
      reasonType: reason.value,
      // 补充说明为空就不传（契约里它可选；传空串与不传语义等价但更脏）
      ...(detail.value.trim() ? { reasonDetail: detail.value.trim() } : {}),
    })
    emit('submitted', id)
    emit('update:open', false)
    uni.showToast({ title: '举报已提交，我们会尽快处理', icon: 'none', duration: 2200 })
  } catch (e) {
    // 失败**不关弹层、也不清空选择** —— 用户可以直接重试，不用重填
    error.value = e instanceof ApiError ? e.message : '举报提交失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.sheet {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 200;
  display: flex;
  align-items: flex-end;
  justify-content: center;

  &__mask {
    position: absolute;
    left: 0;
    top: 0;
    right: 0;
    bottom: 0;
    background-color: rgba(29, 33, 41, 0.45);
  }

  &__panel {
    position: relative;
    width: 100%;
    max-width: 520px;
    padding: 18px 20px calc(18px + env(safe-area-inset-bottom));
    background-color: $hy-bg-card;
    border-radius: $hy-radius-md $hy-radius-md 0 0;
  }

  &__head {
    display: flex;
    align-items: center;
  }

  &__title {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__close {
    margin-left: auto;
    padding: 4px;
  }

  &__hint {
    display: block;
    margin-top: 14px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__input {
    margin-top: 8px;
    padding: 10px 12px;
    background-color: $hy-bg-page;
    border-radius: $hy-radius-sm;
  }

  &__textarea {
    width: 100%;
    height: 62px;
    font-size: $hy-font-md;
    line-height: 1.6;
    color: $hy-text-primary;
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }

  &__submit {
    margin-top: 16px;
    height: 42px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;

    /* 未选原因时压暗并禁止点击（不是"点了才报错"） */
    &--disabled {
      opacity: 0.45;
    }
  }

  &__submit-text {
    font-size: $hy-font-md;
    color: $hy-text-inverse;
  }

  &__error {
    display: block;
    margin-top: 10px;
    font-size: $hy-font-sm;
    color: $hy-color-danger;
    text-align: center;
  }

  &__note {
    display: block;
    margin-top: 12px;
    font-size: $hy-font-xs;
    line-height: 1.6;
    color: $hy-text-placeholder;
    text-align: center;
  }
}

/* ---------- 原因选项 ---------- */
.reasons {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;

  &__item {
    margin: 0 8px 8px 0;
    padding: 7px 16px;
    background-color: $hy-bg-page;
    border: 1px solid transparent;
    border-radius: $hy-radius-pill;

    &--active {
      background-color: $hy-color-primary-light;
      border-color: $hy-color-primary;

      .reasons__text {
        color: $hy-color-primary;
        font-weight: 600;
      }
    }
  }

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-regular;
  }
}
</style>
