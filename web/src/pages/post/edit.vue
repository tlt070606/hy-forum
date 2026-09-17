<template>
  <AppShell active-nav="">
    <!-- ==================== 返回 ==================== -->
    <view class="back" data-testid="compose-back" @click="goBack">
      <HyIcon type="chevronLeft" size="sm" />
      <text class="back__text">{{ isEdit ? '返回帖子' : '返回' }}</text>
    </view>

    <view v-if="loading" class="card" data-testid="compose-loading">
      <text class="hint">正在加载…</text>
    </view>

    <view v-else-if="loadError" class="card" data-testid="compose-load-error">
      <text class="hint hint--error">{{ loadError }}</text>
    </view>

    <template v-else>
      <!-- ==================== 版块 ==================== -->
      <view class="card">
        <text class="label">版块<text class="label__req">*</text></text>

        <!--
          新建：用 chips 选版块，而不是 picker。
          理由：版块少（§4.3 默认 7 个），chips 一屏可见、少一次弹层交互，
          而且能把 `isResource` 角标画出来 —— 用户在选择的那一刻就知道
          "选它要多填网盘信息"（这是口径 9 的可见化）。
        -->
        <template v-if="!isEdit">
          <view v-if="boards.length" class="chips" data-testid="compose-board-chips">
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
          <text v-else class="hint">版块加载中…</text>
        </template>

        <!--
          编辑：**版块不可改**。
          契约的 `PostUpdateRequest` **没有 `boardId`** —— 改版块这个能力在契约里不存在，
          前端不得编造。与其留一组点了没用的 chips，不如显式说明。
        -->
        <template v-else>
          <text class="readonly" data-testid="compose-board-readonly">{{ text(post?.boardName) }}</text>
          <text class="hint">改帖不支持更换版块（契约的改帖请求里没有 boardId）</text>
        </template>
      </view>

      <!-- ==================== 标题 / 正文 ==================== -->
      <view class="card">
        <text class="label">标题<text class="label__req">*</text></text>
        <view class="control">
          <!-- maxlength 与契约一致（`title.maxLength = 100`）。前端限制只是即时反馈，真正校验始终在后端 -->
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
      <!--
        直传 OSS（铁律 8）：先向服务端要签名，再把响应里的字段**原样填表** POST 给 OSS，
        最后从上传响应里读回后端落库的 `{url, thumbUrl}`（CR-G 裁决 A）。
        前端**不拼任何 OSS 参数**，也不自己拼图片 URL。
      -->
      <view class="card">
        <view class="label-row">
          <text class="label">图片</text>
          <text class="label__hint">最多 9 张，单张 ≤ 5MB，支持 jpg/png/webp/gif</text>
        </view>

        <view class="grid" data-testid="compose-image-grid">
          <!-- 已有图片（编辑模式）。⚠️ 提交时**必须原样回传**，否则被 PUT 的覆盖语义清空 -->
          <view v-for="(img, idx) in existingImages" :key="`e${img.id}`" class="grid__cell">
            <image
              class="grid__img"
              :src="img.thumbUrl"
              mode="aspectFill"
              :data-testid="`compose-existing-image-${idx}`"
            />
            <text class="grid__badge">已有</text>
          </view>

          <!-- 本次刚上传的 -->
          <view v-for="(img, idx) in uploaded" :key="`u${img.id}`" class="grid__cell">
            <image
              class="grid__img"
              :src="img.thumbUrl"
              mode="aspectFill"
              :data-testid="`compose-uploaded-image-${idx}`"
            />
            <view
              class="grid__del"
              :data-testid="`compose-remove-image-${idx}`"
              @click="removeUploaded(idx)"
            >
              <HyIcon type="close" size="xs" color="#ffffff" />
            </view>
          </view>

          <!-- 添加入口。到 9 张就消失（契约：images 最多 9） -->
          <view
            v-if="totalImages < MAX_IMAGES"
            class="grid__cell grid__add"
            data-testid="compose-add-image"
            @click="pickImages"
          >
            <HyIcon type="plus" size="lg" color="#86909c" />
            <text class="grid__add-text">{{ uploading ? '上传中…' : '添加' }}</text>
          </view>
        </view>

        <text v-if="uploadError" class="hint hint--error" data-testid="compose-upload-error">
          {{ uploadError }}
        </text>
      </view>

      <!-- ==================== 网盘（仅资源版块） ==================== -->
      <!--
        显示条件 = 当前版块的 `isResource`（口径 9 / 10）。
        新建看被选中版块；编辑看详情的 `boardIsResource`（版块不可改，所以它就是当前版块的属性）。
        ⚠️ 字段名逐字来自契约：`BoardVO.isResource` / `PostDetailVO.boardIsResource`。
      -->
      <view v-if="showDiskFields" class="card" data-testid="compose-disk-card">
        <text class="label">网盘信息<text class="label__hint">资源版块必填</text></text>

        <view class="chips" data-testid="compose-disk-types">
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
          提示"链接里带 pwd 也可以直接贴"：后端会拆出提取码并把链接清干净（§5.5 第 2 条）。
          把这条告诉用户，能少一堆手填错误。
        -->
        <text class="hint">链接里带 pwd= 参数也可以直接粘贴，后端会自动拆出提取码</text>

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
        错误用**常驻文案**而不是只弹 toast：`submitError` 里是 `ApiError.message`，
        它已按契约错误码表映射过（口径 2 要求按码分支，不能只说"失败了"）。
        toast 一闪而过，用户来不及看清到底是敏感词、限流还是未登录。
      -->
      <view v-if="submitError" class="error" data-testid="compose-error">
        <text class="error__text">{{ submitError }}</text>
      </view>

      <view class="submit">
        <view class="submit__btn" data-testid="compose-submit" @click="onSubmit">
          <text class="submit__btn-text">
            {{ submitting ? '提交中…' : isEdit ? '保存修改' : '发布' }}
          </text>
        </view>
        <text v-if="isEdit" class="submit__note">保存后帖子会重新进入审核，通过前只有你自己可见</text>
      </view>
    </template>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 发帖 / 编辑页（同一个页面，用 `?id=` 区分模式）。
 *
 * 契约：`POST /api/posts`（发帖）、`PUT /api/posts/{id}`（改帖）。
 *
 * ==========================================================================
 * 为什么新建与编辑共用一个页面
 * ==========================================================================
 * 表单字段几乎完全一致（标题、正文、图片、网盘），差异只有三点：版块可选性、
 * 提交的接口、提交后的跳转。拆成两个页面会让"资源版块要显示网盘字段"这条口径
 * 有两份实现 —— 两份实现迟早会在某一个上漂移，而漂移的表现是
 * "某个入口发出来的帖子少了提取码"这种很难复现的问题。
 *
 * ==========================================================================
 * 三条从契约来的硬约束（写错任何一条都会静默丢数据）
 * ==========================================================================
 * 1. **`PUT` 是覆盖语义**：没传的字段视为清空。所以改帖必须提交**完整**表单，
 *    尤其是 `images` —— 不传 = **把图全删了**。见 `buildUpdatePayload()`。
 * 2. **资源版块必填 `diskType` + `diskUrl`**，`diskCode` 可空（阿里云盘/夸克无提取码）。
 * 3. **改帖会让 `status` 回到 0**（重新进入审核），必须提前告诉用户。
 */
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AppShell from '@/components/shell/AppShell.vue'
import HyIcon from '@/components/HyIcon.vue'
import { fetchBoards } from '@/api/boards'
import { createPost, fetchPostDetail, updatePost } from '@/api/posts'
import { fetchSignature } from '@/api/oss'
import { uploadImage, type LocalImage, type UploadedImage } from '@/utils/upload'
import { BIZ_CODE } from '@/utils/error-code'
import { ApiError } from '@/utils/request'
import { DISK_TYPES, bool, num, text, toImageView, type PostImageView } from '@/utils/postView'
import type { BoardVO, PostCreateRequest, PostDetailVO, PostUpdateRequest } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const isEdit = ref(false)
const postId = ref(0)

const boards = ref<BoardVO[]>([])
const post = ref<PostDetailVO | null>(null)

const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const submitError = ref('')

/**
 * 本次新上传的图片。
 * 编辑模式下提交时会与 `existingImages` **合并**一起回传（覆盖语义，见 `buildUpdatePayload`）。
 */
const uploaded = ref<UploadedImage[]>([])
const uploading = ref(false)
const uploadError = ref('')

/** 契约里 `images` 最多 9 张 */
const MAX_IMAGES = 9

/** 新建时选中的版块 id；0 = 还没选 */
const boardId = ref(0)

/** 表单。`diskType` 默认 1（百度网盘，最常见） */
const form = reactive({
  title: '',
  content: '',
  diskType: 1,
  diskUrl: '',
  diskCode: '',
})

/* ---------------------------------------------------------------------------
 * 派生
 * ------------------------------------------------------------------------- */

/**
 * 资源字段是否显示（口径 9）。
 * - 新建：看**被选中版块的** `isResource`
 * - 编辑：看详情的 `boardIsResource`（版块不可改，所以它就是当前版块的属性）
 */
const showDiskFields = computed(() => {
  if (isEdit.value) return bool(post.value?.boardIsResource)
  const hit = boards.value.find((b) => num(b.id) === boardId.value)
  return bool(hit?.isResource)
})

/** 编辑模式下的已有图片（回显 + **原样回传**，防止被覆盖语义清空） */
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
  void loadBoards()
})

async function loadBoards(): Promise<void> {
  try {
    boards.value = await fetchBoards()
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '版块加载失败，请稍后重试'
  }
}

/**
 * 编辑模式初始化。
 *
 * 为什么用**详情接口**取原值，而不是通过路由传过来：
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
    // 404 / 403 都说明"这不是一个你能编辑的帖子" → 给错误态让用户返回
    loadError.value = e instanceof ApiError ? e.message : '帖子加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function selectBoard(id: number): void {
  boardId.value = id
}

/* ---------------------------------------------------------------------------
 * 图片上传（直传 OSS）
 * ------------------------------------------------------------------------- */

/** 当前图片总数（已有 + 新传），用于 9 张上限的判断 */
const totalImages = computed(() => existingImages.value.length + uploaded.value.length)

/**
 * 选图并逐张直传。
 *
 * ⚠️ 一次最多选 `MAX_IMAGES - totalImages` 张（契约上限 9），
 *    但**选完还要再校验一次** —— `chooseImage` 的 `count` 在部分端只是建议值。
 */
function pickImages(): void {
  const remain = MAX_IMAGES - totalImages.value
  if (remain <= 0) {
    uni.showToast({ title: `最多 ${MAX_IMAGES} 张图片`, icon: 'none' })
    return
  }

  uni.chooseImage({
    count: remain,
    sizeType: ['compressed', 'original'],
    sourceType: ['album', 'camera'],
    success: (res) => {
      /*
       * `tempFilePaths` 在类型上是 `string | string[]` —— H5 恒为数组，
       * 但 uni 的类型声明为兼容"单文件"场景放宽了。
       * 这里**显式收敛**成数组，而不是用 `as string[]` 断言：
       * 断言会把这层不确定性藏起来，将来真给了字符串就会在下面静默变成未定义行为。
       */
      const rawPaths = res.tempFilePaths
      const paths: string[] = Array.isArray(rawPaths) ? rawPaths : rawPaths ? [rawPaths] : []

      /*
       * 把三端形状归一成 `LocalImage`：
       * - **H5**：`tempFiles` 里是真正的 `File` 对象（`type` / `name` 都有，`path` 是加上的 blob URL getter）；
       * - **小程序**：只有 `{path, size}`，没有 `type`，但 path 带扩展名。
       * 类型判定必须优先看 `type` —— 见 `utils/upload.ts` 文件头（本机踩过：blob URL 没有扩展名）。
       */
      const files = (res.tempFiles ?? []) as Array<{
        path?: string
        size?: number
        type?: string
        name?: string
      }>
      const locals: LocalImage[] = paths.map((p) => {
        const f = files.find((x) => x.path === p)
        return { path: p, size: f?.size, mime: f?.type, name: f?.name }
      })

      void uploadAll(locals.slice(0, remain))
    },
    fail: (err) => {
      // 用户主动取消不算错误，不打扰；其它失败才提示
      const msg = String(err?.errMsg ?? '')
      if (!msg.includes('cancel')) {
        uni.showToast({ title: '选择图片失败', icon: 'none' })
      }
    },
  })
}

/**
 * 串行上传（不是并发）。
 *
 * 为什么串行：一次要一份签名就够用（签名有有效期，串行不会超时），
 * 且并发上传在弱网下更容易整批失败、也更难给出"第几张失败"的准确提示。
 * 代价是大批图片时慢一些 —— 而契约上限只有 9 张，这个代价可以接受。
 */
async function uploadAll(images: LocalImage[]): Promise<void> {
  if (!images.length) return
  uploading.value = true
  uploadError.value = ''

  try {
    // 每张图都要一份签名？不必：同一份签名可传多张（policy 只约束前缀/大小/类型）。
    const sign = await fetchSignature()

    for (const img of images) {
      try {
        const done = await uploadImage(img, sign)
        uploaded.value.push(done)
      } catch (e) {
        /*
         * **一张失败不影响其它张**，但必须把失败原因留下来 ——
         * 静默跳过会让用户以为"我选了 3 张，怎么只上了 2 张"。
         */
        uploadError.value = e instanceof ApiError ? e.message : '有图片上传失败，请重试'
        break
      }
    }
  } catch (e) {
    // 取签名阶段失败（未登录 / 网络 / 后端挂了）
    if (e instanceof ApiError && e.isAuthExpired) {
      uploadError.value = '登录状态已失效，请重新登录后再上传'
      setTimeout(() => uni.navigateTo({ url: '/pages/auth/index?mode=login' }), 800)
    } else {
      uploadError.value = e instanceof ApiError ? e.message : '获取上传签名失败，请稍后重试'
    }
  } finally {
    uploading.value = false
  }
}

/** 移除一张**本次新上传**的图片。已有图片（编辑模式）不可移除 —— 见下方说明 */
function removeUploaded(idx: number): void {
  uploaded.value.splice(idx, 1)
  uploadError.value = ''
}

/*
 * ⚠️ 刻意**不提供"删除已有图片"**的按钮。
 *
 * 技术上做得到：`buildUpdatePayload()` 的 `images` 少一项即等于删掉它（覆盖语义）。
 * 但那是**不可撤销的破坏性操作**，而本轮它没有任何确认流程；
 * 一旦误点，图片从帖子里消失、OSS 对象还留着，用户没有任何办法恢复。
 * 要做就该配一个明确的二次确认（"删除后不可恢复"），那是独立的一次改动。
 * 现在把上限与语义说清楚，比给一个能做但危险的手势更稳妥。
 */

/* ---------------------------------------------------------------------------
 * 提交
 * ------------------------------------------------------------------------- */

/**
 * 前端预校验。
 *
 * 后端校验是不可省的权威判定，但它的反馈发生在**一次往返之后**：
 * 用户填了标题、点了发布、等一秒，然后看到"提交的内容有误"。
 * 而"标题不能为空"这种错本来可以零延迟指出。
 *
 * ⚠️ 这里只做**契约里明确写了的**约束（必填、长度上限），
 *    不自己发明规则（例如"标题至少 5 个字"）—— 否则会出现"前端拦住了、后端其实允许"，
 *    那是在挡功能。
 */
function validate(): string {
  if (!isEdit.value && !boardId.value) return '请选择一个版块'
  if (!form.title.trim()) return '请填写标题'
  if (form.title.length > 100) return '标题不能超过 100 字'
  if (form.content.length > 10000) return '正文不能超过 10000 字'

  /*
   * 资源版块必填网盘链接（口径 10）。
   * 注意 `diskCode` **可空** —— 阿里云盘/夸克无提取码机制（§5.5）。
   * 不能顺手把它也要求上，那会挡住两个主流网盘的用户。
   */
  if (showDiskFields.value && !form.diskUrl.trim()) return '资源版块必须填写网盘分享链接'
  if (form.diskUrl.length > 500) return '网盘链接不能超过 500 字'
  if (form.diskCode.length > 20) return '提取码不能超过 20 字'

  return ''
}

/**
 * 构造发帖请求体。
 *
 * ⚠️ 图片上传还没做，所以这里**不传 `images`** —— 新建帖本来就没有图，
 *    不传是正确语义（契约里它可选）。上传做好后再把 URL 列表塞进来。
 */
function buildCreatePayload(): PostCreateRequest {
  const payload: PostCreateRequest = {
    boardId: boardId.value,
    title: form.title.trim(),
  }
  // 正文为空时不传该字段（契约里 content 非必填），避免提交一个空的 content
  if (form.content.trim()) payload.content = form.content.trim()

  /*
   * 图片：传的是**后端落库后返回的 URL**（来自上传响应，CR-G 裁决 A），
   * 不是前端拼出来的地址。没有图就不传该字段（契约里它可选）。
   */
  if (uploaded.value.length) payload.images = uploaded.value.map((i) => i.url)

  if (showDiskFields.value) {
    payload.diskType = form.diskType
    payload.diskUrl = form.diskUrl.trim()
    // 提取码为空就不传：契约里它可空，传空串与不传语义等价但更干净
    if (form.diskCode.trim()) payload.diskCode = form.diskCode.trim()
  }
  return payload
}

/**
 * 构造改帖请求体。
 *
 * ==========================================================================
 * ⚠️ 这个函数是"覆盖语义"的落地点，写错会**静默删数据**
 * ==========================================================================
 * 契约明确：`PUT` 是**覆盖**，**没传的字段视为清空**。因此：
 * 1. `title` 必须带（契约必填）；
 * 2. `content` **必须带**，哪怕是空串 —— 否则用户"清空正文"这个操作保存不了；
 * 3. `images` **必须带**，且是"当前帖子已有的图片 URL" ——
 *    不传就等于**把图全删了**（契约里 `PostUpdateRequest.images` 的说明写得很清楚：
 *    "不传 = 清空图片"）。这是本页最容易造成数据丢失的一处；
 * 4. 资源版块的网盘字段**必须带全**：漏掉 `diskUrl` 就会把网盘信息清空。
 *
 * 另外 `PostUpdateRequest` **没有 `boardId`** —— 改帖不支持换版块，这是契约的既定行为。
 */
function buildUpdatePayload(): PostUpdateRequest {
  return {
    title: form.title.trim(),
    // 显式给空串：不传 = 清空，但"用户主动清空"和"我们忘了传"必须能区分开
    content: form.content.trim(),
    // 原样回传「已有图片 + 本次新上传的」，防止被覆盖语义清空
    images: [...existingImages.value.map((img) => img.url), ...uploaded.value.map((i) => i.url)],
    ...(showDiskFields.value
      ? {
          diskType: form.diskType,
          diskUrl: form.diskUrl.trim(),
          diskCode: form.diskCode.trim(),
        }
      : {}),
  }
}

async function onSubmit(): Promise<void> {
  submitError.value = ''

  const invalid = validate()
  if (invalid) {
    submitError.value = invalid
    return
  }

  // 图片还在传就提交 → 会发出一个"少图"的帖子，且用户以为图在里面。必须挡住。
  if (uploading.value) {
    submitError.value = '图片还在上传中，请稍候再发布'
    return
  }

  /*
   * 未登录：引导登录（口径 4）。
   * 为什么提交前再判一次（首页的「＋」已经判过）：用户可能在填写过程中
   * token 失效（另一个标签页退出登录），此时不该只弹一句失败。
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
       * 保存后**显式跳到详情页**，而不是 `uni.navigateBack()`。
       * 本机实测过：`navigateBack` 依赖"历史栈里确实有上一页"，而编辑页可以从
       * 分享链接 / 刷新 / E2E 的 `page.goto` 直接进入，此时 `history.back()`
       * 会退到与"上一页"无关的位置，**而且不报错** —— 最难查的一类现象。
       * `redirectTo` 结果确定，且用户能立刻看到修改结果与待审横幅。
       */
      setTimeout(() => {
        uni.redirectTo({ url: `/pages/post/detail?id=${postId.value}` })
      }, 1200)
    } else {
      const created = await createPost(buildCreatePayload())
      uni.showToast({ title: '发布成功', icon: 'success' })

      /*
       * 跳详情用 `redirectTo` 而不是 `navigateTo`：发帖页在保存后已无价值，
       * 留在栈里会让"从详情返回"又回到一张填满内容的表单，容易重复提交。
       */
      const newId = num(created?.id)
      setTimeout(() => {
        if (newId) {
          uni.redirectTo({ url: `/pages/post/detail?id=${newId}` })
        } else {
          // 契约里 id 可选，万一没返回也别卡在空页面
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
 * ⚠️ 口径 2：**必须按码分支**，不能只判断"失败了"。区分几类：
 * - `401` → 登录态失效，**跳登录页**（口径 4：不得把 401 当成成功、不得白屏）
 * - `2002` → 发帖过于频繁，**保留后端原文**（它带重试秒数，实测形如"请 8734 秒后重试"）
 * - `403` → 改帖被拒（非作者，或超过发布后 30 分钟）
 * - 其它 → 用 `error-code.ts` 已映射好的文案
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

  if (e.code === BIZ_CODE.FORBIDDEN) {
    return e.message || '你没有权限修改这个帖子（仅作者本人、且限发布后 30 分钟内）'
  }

  return e.message
}

function goBack(): void {
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.reLaunch({ url: '/pages/index/index' })
  }
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.back {
  display: flex;
  align-items: center;
  margin-bottom: $hy-shell-gap;

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

/* ---------- 标签 ---------- */
.label {
  display: block;
  font-size: $hy-font-sm;
  font-weight: 600;
  color: $hy-text-primary;

  &--mt {
    margin-top: 16px;
  }

  &__req {
    margin-left: 4px;
    color: $hy-color-danger;
  }

  &__hint {
    margin-left: 6px;
    font-size: $hy-font-xs;
    font-weight: 400;
    color: $hy-text-secondary;
  }
}

.hint {
  display: block;
  margin-top: 8px;
  font-size: $hy-font-xs;
  color: $hy-text-secondary;
  line-height: 1.6;

  &--error {
    color: $hy-color-danger;
  }
}

/* ---------- 输入控件 ---------- */
.control {
  margin-top: 8px;
  padding: 0 12px;
  height: 42px;
  display: flex;
  align-items: center;
  background-color: $hy-bg-page;
  border: 1px solid $hy-border-input;
  border-radius: $hy-radius-sm;

  /* 多行输入：高度自适应，因此不能 flex 居中 */
  &--area {
    height: auto;
    padding: 10px 12px;
    align-items: flex-start;
  }

  &__input {
    flex: 1;
    min-width: 0;
    height: 42px;
    font-size: $hy-font-md;
    color: $hy-text-primary;
  }

  &__textarea {
    width: 100%;
    height: 180px;
    font-size: $hy-font-md;
    line-height: 1.7;
    color: $hy-text-primary;
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }
}

.counter {
  display: block;
  margin-top: 4px;
  font-size: $hy-font-xs;
  color: $hy-text-placeholder;
  text-align: right;
}

/* ---------- chips ---------- */
.chips {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
}

.chip {
  display: flex;
  align-items: center;
  margin: 0 8px 8px 0;
  padding: 5px 14px;
  border: 1px solid $hy-border-input;
  border-radius: $hy-radius-pill;
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
    margin-left: 6px;
    padding: 0 6px;
    font-size: 10px;
    line-height: 15px;
    color: $hy-text-inverse;
    background-color: $hy-color-success;
    border-radius: 3px;
  }
}

/* ---------- 只读值 ---------- */
.readonly {
  display: block;
  margin-top: 8px;
  font-size: $hy-font-lg;
  font-weight: 600;
  color: $hy-text-primary;
}

/* ---------- 已有图片 ---------- */
.label-row {
  display: flex;
  align-items: baseline;
}

.grid {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;

  &__cell {
    position: relative;
    width: 96px;
    height: 96px;
    margin: 0 8px 8px 0;
    border-radius: $hy-radius-sm;
    /* 去掉这行的话，圆角会被内部 <image> 的直角盖住 */
    overflow: hidden;
    background-color: $hy-bg-hover;
  }

  &__img {
    width: 100%;
    height: 100%;
  }

  /* "已有"角标：区分"这次新传的"与"帖子里原来的"，避免用户以为新传失败了 */
  &__badge {
    position: absolute;
    left: 0;
    bottom: 0;
    padding: 0 6px;
    font-size: 10px;
    line-height: 16px;
    color: $hy-text-inverse;
    background-color: rgba(29, 33, 41, 0.5);
  }

  &__del {
    position: absolute;
    right: 0;
    top: 0;
    width: 20px;
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: rgba(29, 33, 41, 0.55);
    border-radius: 0 0 0 8px;
  }

  &__add {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    border: 1px dashed $hy-border-input;
    background-color: $hy-bg-card;
  }

  &__add-text {
    margin-top: 4px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 错误 ---------- */
.error {
  margin-bottom: $hy-shell-gap;
  padding: 10px 16px;
  border-radius: $hy-radius-sm;
  background-color: $hy-color-danger-light;

  &__text {
    font-size: $hy-font-sm;
    color: $hy-color-danger;
    line-height: 1.6;
  }
}

/* ---------- 提交 ---------- */
.submit {
  display: flex;
  flex-direction: column;

  &__btn {
    height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;

    &:active {
      background-color: $hy-color-primary-hover;
    }
  }

  &__btn-text {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-inverse;
  }

  &__note {
    margin-top: 10px;
    font-size: $hy-font-xs;
    color: $hy-color-warning;
    text-align: center;
    line-height: 1.6;
  }
}

@media (max-width: $hy-shell-breakpoint) {
  .card {
    padding: 14px;
  }

  .control__textarea {
    height: 150px;
  }
}
</style>
