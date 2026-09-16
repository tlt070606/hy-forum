<template>
  <view class="page">
    <HyState :loading="loading" :error="loadError" @retry="loadForEdit" />

    <template v-if="!loading && !loadError">
      <!-- ==================== 版块 ==================== -->
      <view class="card">
        <text class="label">版块<text class="label__req">*</text></text>

        <!--
          新建：用 chips 选版块。
          为什么不用 picker：版块数量少（§4.3 默认 7 个），chips 一屏可见、
          少一次弹层交互，而且能直接把 `isResource` 角标画出来 ——
          用户在选择的那一刻就知道"选它要多填网盘信息"（口径 9 的可见化）。
        -->
        <template v-if="!isEdit">
          <HyState
            v-if="boards.length === 0"
            :loading="boardsLoading"
            :error="boardsError"
            :empty="!boardsLoading && !boardsError"
            empty-text="没有可用版块"
            loading-text="正在加载版块…"
            @retry="loadBoards"
          />
          <view v-else class="chips" data-testid="compose-board-chips">
            <view
              v-for="b in boards"
              :key="b.id"
              class="chip"
              :class="{ 'chip--active': boardId === num(b.id) }"
              :data-testid="`compose-board-${num(b.id)}`"
              @click="selectBoard(num(b.id))"
            >
              <text class="chip__text">{{ text(b.name) }}</text>
              <text v-if="bool(b.isResource)" class="chip__tag">资源</text>
            </view>
          </view>
        </template>

        <!--
          编辑：**版块不可改**。
          原因：契约的 `PostUpdateRequest` **没有 `boardId` 字段** ——
          改版块这个能力在契约里不存在，前端不得编造（任务书 §2/§3）。
          与其把不可用的 chips 留在界面上误点，不如显式说明。
        -->
        <template v-else>
          <text class="readonly" data-testid="compose-board-readonly">
            {{ text(post?.boardName) || '（未提供版块名）' }}
          </text>
          <text class="note">改帖不支持更换版块</text>
        </template>
      </view>

      <!-- ==================== 标题 / 正文 ==================== -->
      <view class="card">
        <text class="label">标题<text class="label__req">*</text></text>
        <view class="control">
          <!--
            maxlength 与契约一致（`PostCreateRequest.title.maxLength = 100`）。
            前端限制只是即时反馈，**真正的校验始终在后端**。
          -->
          <input
            v-model="form.title"
            class="control__input"
            type="text"
            :maxlength="100"
            placeholder="一句话说清你要分享或讨论什么"
            placeholder-class="control__placeholder"
            data-testid="compose-title"
          />
        </view>
        <text class="counter">{{ form.title.length }}/100</text>

        <text class="label label--mt">正文</text>
        <view class="control control--area">
          <textarea
            v-model="form.content"
            class="control__textarea"
            :maxlength="10000"
            placeholder="补充说明（可选）"
            placeholder-class="control__placeholder"
            data-testid="compose-content"
          />
        </view>
        <text class="counter">{{ form.content.length }}/10000</text>
      </view>

      <!-- ==================== 图片 ==================== -->
      <view class="card">
        <text class="label">图片<text class="label__hint">最多 9 张</text></text>

        <!--
          ==================================================================
          上传功能当前**不可用**，原因是契约缺口而不是"还没写"（必须让用户看见）
          ==================================================================
          OSS PostObject 直传表单必须带 `OSSAccessKeyId` 字段，而它的**值**只能来自
          `GET /api/oss/signature` 的响应（铁律 5：前端绝不持有 AccessKey）。
          但 `openapi.json` 里 `OssSignatureVO` **至今没有 accessKeyId 字段**
          （CR-F 已改了后端代码，契约尚未重新导出）→ 前端拿不到这个值。

          按任务书 §7 的规定：**不自己编字段名、不"先这么写回头对齐"**，
          而是把缺口显式暴露在这里，并已开 CR 交给 L1。

          刻意**不做**的三件事（都会被"看起来在做"掩盖真实进度）：
          1. 不做假的"+ 选择图片"按钮然后点了报错；
          2. 不把图片数量写死成 0 假装功能不存在；
          3. 不用外部图床/本地 blob 冒充"上传成功"。
        -->
        <view class="blocked" data-testid="compose-upload-blocked">
          <text class="blocked__title">图片上传暂不可用</text>
          <text class="blocked__text">
            签名接口尚未返回直传所需的 AccessKey 标识（契约缺口 CR-F，开放中）。
            当前可正常发布**纯文字帖**与**资源帖（网盘链接）**。
          </text>
        </view>

        <!--
          编辑模式：把**已有图片**回显出来，并且提交时原样带上。
          ⚠️ 这一步不是可有可无的：契约里 `PUT` 是**覆盖语义**，
             `images` 不传 = **清空图片**。若不回传，改一次标题就会把帖子里的图全删掉。
        -->
        <view v-if="isEdit && existingImages.length" class="grid">
          <image
            v-for="(img, index) in existingImages"
            :key="img.id"
            class="grid__item"
            :src="img.thumbUrl"
            mode="aspectFill"
            :data-testid="`compose-existing-image-${index}`"
          />
        </view>
        <text v-if="isEdit && existingImages.length" class="note">
          编辑时保留原有图片；受上面的契约缺口限制，暂不能增删图片
        </text>
      </view>

      <!-- ==================== 网盘（仅资源版块） ==================== -->
      <!--
        ============================================================
        资源字段的显示条件：**当前版块的 `isResource === true`**（口径 9 / 10）
        ============================================================
        `isResource` 一个字母都不能改（契约 `BoardVO.isResource`）。
        编辑模式下版块不可改，因此用详情的 `boardIsResource` 判断 ——
        它同样是后端给的字段，不是前端推断的。
      -->
      <view v-if="showDiskFields" class="card" data-testid="compose-disk-card">
        <text class="label">网盘信息<text class="label__hint">资源版块必填</text></text>

        <!-- 网盘类型：取值来自契约 `diskType` 的字段描述（1 百度 … 6 其他） -->
        <view class="chips chips--wrap" data-testid="compose-disk-types">
          <view
            v-for="d in DISK_TYPES"
            :key="d.value"
            class="chip"
            :class="{ 'chip--active': form.diskType === d.value }"
            :data-testid="`compose-disk-type-${d.value}`"
            @click="form.diskType = d.value"
          >
            <text class="chip__text">{{ d.label }}</text>
          </view>
        </view>

        <text class="label label--mt">分享链接<text class="label__req">*</text></text>
        <view class="control">
          <input
            v-model="form.diskUrl"
            class="control__input"
            type="text"
            :maxlength="500"
            placeholder="粘贴网盘分享链接"
            placeholder-class="control__placeholder"
            data-testid="compose-disk-url"
          />
        </view>
        <!--
          提示用户"链接里带 pwd 也可以直接贴"：后端会拆出提取码并清理链接
          （《技术方案》§5.5 第 2 条的归一化）。把这条告诉用户能少一堆手填错误。
        -->
        <text class="note">链接里带 pwd= 参数也可以直接粘贴，后端会自动拆出提取码</text>

        <text class="label label--mt">提取码<text class="label__hint">可留空</text></text>
        <view class="control">
          <input
            v-model="form.diskCode"
            class="control__input"
            type="text"
            :maxlength="20"
            placeholder="阿里云盘 / 夸克网盘没有提取码，留空即可"
            placeholder-class="control__placeholder"
            data-testid="compose-disk-code"
          />
        </view>
      </view>

      <!-- ==================== 错误与提交 ==================== -->
      <!--
        错误用**常驻文案**而不是只弹 toast：submitError 里是 `ApiError.message`，
        它已经按契约错误码表映射过（口径 2 要求按码分支，不能只说"失败了"）。
        toast 一闪而过，用户来不及看清"是敏感词还是限流还是没登录"。
      -->
      <view v-if="submitError" class="error" data-testid="compose-error">
        <text class="error__text">{{ submitError }}</text>
      </view>

      <view class="submit">
        <wd-button
          type="primary"
          block
          :loading="submitting"
          data-testid="compose-submit"
          @click="onSubmit"
        >
          {{ isEdit ? '保存修改' : '发布' }}
        </wd-button>
        <!-- 重新审核提示：编辑成功后 status 回 0（§8.6 第 6 条），必须提前告知 -->
        <text v-if="isEdit" class="submit__note">
          保存后帖子会**重新进入审核**，审核通过前仅你自己可见
        </text>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 发帖 / 编辑页（同一个页面，用 `?id=` 区分模式）。
 *
 * ==========================================================================
 * 为什么新建与编辑共用一个页面
 * ==========================================================================
 * 两者的表单字段几乎完全一致（标题、正文、图片、网盘），差异只有三点：
 * 版块可选性、提交的接口、提交后的跳转。分成两个页面会让"资源版块要显示网盘字段"
 * 这条口径（口径 9）有两份实现 —— 两份实现迟早会在某一个上漂移，
 * 而漂移的表现是"某个入口发出来的帖子少了提取码"这种很难复现的问题。
 *
 * ==========================================================================
 * 契约要点（逐条对齐）
 * ==========================================================================
 * - `POST /api/posts` 必填 `boardId` + `title`；资源版另需 `diskType` + `diskUrl`（口径 10）
 * - `PUT /api/posts/{id}` 必填 `title`，且是**覆盖语义**（口径 11）→ 见 `buildUpdatePayload()`
 * - 改帖**仅作者、仅 30 分钟内**；成功后 `status` 回 0（§8.6 第 6 条）
 * - 图片上传：**当前被契约缺口阻断**（见模板里那段说明与报告中的 CR）
 */
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import HyState from '@/components/HyState.vue'
import { fetchBoards } from '@/api/boards'
import { createPost, fetchPostDetail, updatePost } from '@/api/posts'
import { BIZ_CODE } from '@/utils/error-code'
import { ApiError } from '@/utils/request'
import { DISK_TYPES, bool, num, text, toImageView, type PostImageView } from '@/utils/format'
import type { BoardVO, PostCreateRequest, PostUpdateRequest } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const isEdit = ref(false)
const postId = ref(0)

const boards = ref<BoardVO[]>([])
const boardsLoading = ref(false)
const boardsError = ref('')

const post = ref<Awaited<ReturnType<typeof fetchPostDetail>> | null>(null)

const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const submitError = ref('')

/** 表单。`diskType` 默认 1（百度网盘，最常见） */
const form = reactive({
  title: '',
  content: '',
  diskType: 1,
  diskUrl: '',
  diskCode: '',
})

/** 新建时选中的版块 id；0 表示还没选 */
const boardId = ref(0)

/* ---------------------------------------------------------------------------
 * 派生
 * ------------------------------------------------------------------------- */

/**
 * 资源字段是否显示。
 *
 * - 新建：看**被选中版块的** `isResource`
 * - 编辑：看详情的 `boardIsResource`（版块不可改，所以它就是当前版块的属性）
 *
 * ⚠️ 名字逐字来自契约：`BoardVO.isResource` / `PostDetailVO.boardIsResource`。
 *    口径 9 明确要求"字段名逐字是 `isResource`"，不要写成 `resource` / `is_resource`。
 */
const showDiskFields = computed(() => {
  if (isEdit.value) return bool(post.value?.boardIsResource)
  const hit = boards.value.find((b) => num(b.id) === boardId.value)
  return bool(hit?.isResource)
})

/** 编辑模式下的已有图片（回显 + 原样回传，防覆盖清空） */
const existingImages = computed<PostImageView[]>(() =>
  (post.value?.images ?? []).map(toImageView)
)

/* ---------------------------------------------------------------------------
 * 加载
 * ------------------------------------------------------------------------- */

onLoad((options) => {
  const raw = options?.id
  if (raw) {
    const parsed = Number(raw)
    if (Number.isNaN(parsed) || parsed <= 0) {
      loadError.value = '帖子参数有误，请返回重试'
      return
    }
    isEdit.value = true
    postId.value = parsed
    void loadForEdit()
    return
  }
  // 新建：先要版块列表才能渲染 chips
  void loadBoards()
})

async function loadBoards(): Promise<void> {
  boardsLoading.value = true
  boardsError.value = ''
  try {
    boards.value = await fetchBoards()
  } catch (e) {
    boardsError.value = e instanceof ApiError ? e.message : '版块加载失败，请稍后重试'
  } finally {
    boardsLoading.value = false
  }
}

/**
 * 编辑模式初始化。
 *
 * 为什么用**详情接口**取原始值而不是把值通过路由传过来：
 * 路由传参会把正文（可达 10000 字）塞进 URL，长度受限且会被日志记录；
 * 而且列表项（`PostSummaryVO`）**不含正文**（口径 6），根本拿不到。
 */
async function loadForEdit(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const detail = await fetchPostDetail(postId.value)
    post.value = detail

    // 逐字段回填。**只用契约字段**，缺失就是空
    form.title = text(detail.title)
    form.content = text(detail.content)
    form.diskType = num(detail.diskType) || 1
    form.diskUrl = text(detail.diskUrl)
    form.diskCode = text(detail.diskCode)
  } catch (e) {
    if (e instanceof ApiError) {
      // 404 / 403 都说明"这不是一个你能编辑的帖子"，直接给错误态让用户返回
      loadError.value = e.message
    } else {
      loadError.value = '帖子加载失败，请稍后重试'
    }
  } finally {
    loading.value = false
  }
}

function selectBoard(id: number): void {
  boardId.value = id
}

/* ---------------------------------------------------------------------------
 * 提交
 * ------------------------------------------------------------------------- */

/**
 * 前端预校验。
 *
 * ==========================================================================
 * 为什么还要在前端校验（后端已经会校验）
 * ==========================================================================
 * 后端校验是不可省的权威判定，但它的反馈发生在**一次往返之后**：
 * 用户填了 80 个字的标题、点了发布、等一秒、然后看到"提交的内容有误"——
 * 而"标题不能为空"这种错本来可以零延迟指出。
 *
 * ⚠️ 因此这里只做**契约里明确写了的**约束（必填、长度上限），
 *    不自己发明规则（例如"标题至少 5 个字"），否则会出现"前端拦住了、后端其实允许"
 *    这种把功能挡掉的 bug。
 *
 * @returns 错误文案；空串表示通过
 */
function validate(): string {
  if (!isEdit.value && !boardId.value) return '请选择一个版块'
  if (!form.title.trim()) return '请填写标题'
  if (form.title.length > 100) return '标题不能超过 100 字'
  if (form.content.length > 10000) return '正文不能超过 10000 字'

  /*
   * 资源版块必填网盘链接（口径 10）。
   * 注意 `diskCode` **可空** —— 阿里云盘/夸克无提取码机制（《技术方案》§5.5）。
   * 这里不能顺手把它也要求上，那会挡住两个主流网盘的用户。
   */
  if (showDiskFields.value && !form.diskUrl.trim()) return '资源版块必须填写网盘分享链接'
  if (form.diskUrl.length > 500) return '网盘链接不能超过 500 字'
  if (form.diskCode.length > 20) return '提取码不能超过 20 字'

  return ''
}

/** 构造发帖请求体（只包含契约里存在的字段） */
function buildCreatePayload(): PostCreateRequest {
  const payload: PostCreateRequest = {
    boardId: boardId.value,
    title: form.title.trim(),
  }
  // 正文为空时**不传**该字段（契约里 content 非必填），避免提交一个空的 content
  if (form.content.trim()) payload.content = form.content

  if (showDiskFields.value) {
    payload.diskType = form.diskType
    payload.diskUrl = form.diskUrl.trim()
    // 提取码为空就不传：契约里它可空，传空串与不传在语义上等价但更干净
    if (form.diskCode.trim()) payload.diskCode = form.diskCode.trim()
  }
  return payload
}

/**
 * 构造改帖请求体。
 *
 * ==========================================================================
 * ⚠️ 这个函数是"覆盖语义"的落地点，写错了会静默删数据
 * ==========================================================================
 * 契约明确：`PUT` 是**覆盖**，**没传的字段视为清空**（口径 11）。
 * 因此：
 * 1. `title` 必须带（契约必填）；
 * 2. `content` **必须带**，哪怕是空串 —— 否则用户"清空正文"这个操作保存不了；
 * 3. `images` **必须带**，且是"当前帖子已有的图片 URL" ——
 *    不传就等于**把图全删了**（契约里 `PostUpdateRequest.images` 的说明写得很清楚：
 *    "不传 = 清空图片"）。这是本页最容易造成数据丢失的一处；
 * 4. 资源版块的网盘字段**必须带全**：漏掉 `diskUrl` 就会把网盘信息清空
 *    （任务书 §5 第 11 条专门点了这一条）。
 *
 * 另外 `PostUpdateRequest` **没有 `boardId`** —— 改帖不支持换版块，这是契约的既定行为。
 */
function buildUpdatePayload(): PostUpdateRequest {
  const payload: PostUpdateRequest = {
    title: form.title.trim(),
    // 显式给空串：不传 = 清空，但"用户主动清空"和"我们忘了传"必须能区分开
    content: form.content.trim(),
    // 原样回传已有图片，防止被覆盖语义清空
    images: existingImages.value.map((img) => img.url),
  }

  if (showDiskFields.value) {
    payload.diskType = form.diskType
    payload.diskUrl = form.diskUrl.trim()
    payload.diskCode = form.diskCode.trim()
  }
  return payload
}

async function onSubmit(): Promise<void> {
  submitError.value = ''

  const invalid = validate()
  if (invalid) {
    submitError.value = invalid
    return
  }

  /*
   * 未登录：引导登录（口径 4）。
   * 为什么仍然在提交前再判一次（首页的「＋」已经判过）：用户可能在填写过程中
   * token 失效（另一个标签页退出登录），此时不该只是弹一句失败。
   */
  if (!auth.isLoggedIn) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    setTimeout(() => uni.navigateTo({ url: '/pages/auth/index?mode=login' }), 600)
    return
  }

  submitting.value = true
  try {
    if (isEdit.value) {
      await updatePost(postId.value, buildUpdatePayload())
      uni.showToast({ title: '已保存，重新进入审核', icon: 'none', duration: 2500 })

      /*
       * ==================================================================
       * 保存后**显式跳到详情页**，而不是 `uni.navigateBack()`
       * ==================================================================
       * 本机 E2E 实测：`navigateBack()` 在这里会落到**首页**，而不是上一页。
       * 根因是它依赖"历史栈里确实有上一页"：
       * - 编辑页可以从分享链接、刷新、或 E2E 的 `page.goto` 直接进入，
       *   此时 uni-app H5 的历史栈与页面栈并不对应，`history.back()` 会退到
       *   一个与"上一页"无关的位置；
       * - 而 `navigateBack` 失败时**不报错**，只表现为"跳到了奇怪的页面"，
       *   是最难排查的一类现象。
       *
       * 改成 `redirectTo` 详情页有两个好处：
       * 1. **结果确定** —— 不管用户从哪进来，保存后都停在这篇帖子上；
       * 2. 用户能**立刻看到修改结果**（含"重新进入审核"的待审横幅），
       *    这本来就是他点保存后最想确认的事。
       *
       * 用 `redirectTo` 而不是 `navigateTo`：编辑页在保存后已无价值，
       * 留在栈里会让"从详情返回"又回到一张填满内容的表单，容易重复提交。
       */
      setTimeout(() => {
        uni.redirectTo({ url: `/pages/post/detail?id=${postId.value}` })
      }, 1200)
    } else {
      const created = await createPost(buildCreatePayload())
      uni.showToast({ title: '发布成功', icon: 'success' })

      /*
       * 跳详情用 `redirectTo` 而不是 `navigateTo`：
       * 发帖页在栈里已经没有价值了，留在栈里会让用户"从详情返回"时又回到
       * 一张填满内容的表单，容易重复提交。`redirectTo` 把它替换掉。
       */
      const newId = num(created?.id)
      setTimeout(() => {
        if (newId) {
          uni.redirectTo({ url: `/pages/post/detail?id=${newId}` })
        } else {
          // 契约里 id 可选，万一没返回也不要卡在空页面
          uni.reLaunch({ url: '/pages/index/index' })
        }
      }, 800)
    }
  } catch (e) {
    submitError.value = resolveSubmitError(e)
  } finally {
    submitting.value = false
  }
}

/**
 * 把提交失败翻译成用户能据以行动的文案。
 *
 * ⚠️ 口径 2：**必须按码分支**，不能只判断"失败了"。区分三类：
 * - `401` → 登录态失效，**跳登录页**（口径 4：不得把 401 当成成功、不得白屏）
 * - `2002` → 发帖过于频繁，提示稍后重试（**HTTP 200 + 业务码**，不是 429，口径 3）
 * - 其它 → 用 `error-code.ts` 已映射好的文案
 *
 * 后端 `message` 里带了重试秒数（限流场景），所以 2002 这一支**保留后端原文**，
 * 只在后端没给的情况下才退回前端文案。
 */
function resolveSubmitError(e: unknown): string {
  if (!(e instanceof ApiError)) return '发布失败，请稍后重试'

  if (e.code === BIZ_CODE.UNAUTHORIZED) {
    setTimeout(() => uni.navigateTo({ url: '/pages/auth/index?mode=login' }), 600)
    return '登录状态已失效，请重新登录'
  }

  if (e.code === BIZ_CODE.POST_TOO_FREQUENT) {
    // 后端 message 含重试秒数，优先展示它；没有则用前端稳定文案
    return e.message || '发帖太频繁了，请稍后再试'
  }

  return e.message
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

/* ---------- 标签 ---------- */
.label {
  display: block;
  font-size: $hy-font-sm;
  font-weight: 600;
  color: $hy-text-primary;

  &--mt {
    margin-top: $hy-space-md;
  }

  &__req {
    margin-left: 4rpx;
    color: $hy-color-danger;
  }

  &__hint {
    margin-left: $hy-space-xs;
    font-size: $hy-font-xs;
    font-weight: 400;
    color: $hy-text-secondary;
  }
}

/* ---------- 输入控件 ---------- */
.control {
  margin-top: $hy-space-sm;
  padding: 0 $hy-space-md;
  height: 80rpx;
  display: flex;
  align-items: center;
  background-color: $hy-bg-page;
  border: 1rpx solid $hy-border-input;
  border-radius: $hy-radius-sm;

  /* 多行输入：高度自适应，因此不能用 flex 居中 */
  &--area {
    height: auto;
    padding: $hy-space-sm $hy-space-md;
    align-items: flex-start;
  }

  &__input {
    flex: 1;
    min-width: 0;
    height: 80rpx;
    font-size: $hy-font-sm;
    color: $hy-text-primary;
  }

  &__textarea {
    width: 100%;
    height: 280rpx;
    font-size: $hy-font-sm;
    line-height: 1.6;
    color: $hy-text-primary;
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }
}

/* ---------- 计数 ---------- */
.counter {
  display: block;
  margin-top: 6rpx;
  font-size: $hy-font-xs;
  color: $hy-text-placeholder;
  text-align: right;
}

/* ---------- chips（版块 / 网盘类型） ---------- */
.chips {
  margin-top: $hy-space-sm;
  display: flex;
  flex-wrap: wrap;

  &--wrap {
    /* 网盘类型有 6 项，必然换行 */
    margin-top: $hy-space-sm;
  }
}

.chip {
  display: flex;
  align-items: center;
  margin: 0 $hy-space-sm $hy-space-sm 0;
  padding: $hy-space-xs $hy-space-md;
  border: 1rpx solid $hy-border-input;
  border-radius: 32rpx;
  background-color: $hy-bg-card;

  &--active {
    border-color: $hy-color-primary;
    background-color: $hy-color-primary-light;

    .chip__text {
      color: $hy-color-primary;
      font-weight: 600;
    }
  }

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-regular;
  }

  &__tag {
    margin-left: $hy-space-xs;
    padding: 0 8rpx;
    border-radius: $hy-radius-sm;
    font-size: $hy-font-xs;
    color: $hy-text-inverse;
    background-color: $hy-color-success;
  }
}

/* ---------- 只读值（编辑模式的版块） ---------- */
.readonly {
  display: block;
  margin-top: $hy-space-sm;
  font-size: $hy-font-md;
  color: $hy-text-primary;
}

.note {
  display: block;
  margin-top: $hy-space-xs;
  font-size: $hy-font-xs;
  color: $hy-text-secondary;
  line-height: 1.6;
}

/* ---------- 上传不可用说明 ---------- */
.blocked {
  margin-top: $hy-space-sm;
  padding: $hy-space-sm $hy-space-md;
  border-radius: $hy-radius-sm;
  /* 用警示色而不是危险色：这是"能力尚未交付"，不是用户操作出错 */
  background-color: rgba(250, 157, 59, 0.12);

  &__title {
    display: block;
    font-size: $hy-font-sm;
    font-weight: 600;
    color: $hy-color-warning;
  }

  &__text {
    display: block;
    margin-top: 4rpx;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    line-height: 1.6;
  }
}

/* ---------- 已有图片（编辑模式） ---------- */
.grid {
  margin-top: $hy-space-sm;
  display: flex;
  flex-wrap: wrap;

  &__item {
    width: calc(33.33% - 8rpx);
    height: 200rpx;
    margin: 0 12rpx 12rpx 0;
    border-radius: $hy-radius-sm;
    background-color: $hy-bg-hover;

    &:nth-child(3n) {
      margin-right: 0;
    }
  }
}

/* ---------- 错误 ---------- */
.error {
  margin: 0 $hy-space-md $hy-space-md;
  padding: $hy-space-sm $hy-space-md;
  border-radius: $hy-radius-sm;
  background-color: rgba(250, 81, 81, 0.1);

  &__text {
    font-size: $hy-font-sm;
    color: $hy-color-danger;
    line-height: 1.6;
  }
}

/* ---------- 提交 ---------- */
.submit {
  margin: 0 $hy-space-md;
  display: flex;
  flex-direction: column;

  &__note {
    margin-top: $hy-space-sm;
    font-size: $hy-font-xs;
    color: $hy-color-warning;
    line-height: 1.6;
    text-align: center;
  }
}
</style>
