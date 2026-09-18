<template>
  <!--
    纯 CSS 图标（**新增图标集，2026-09-16 随三栏改版重做**）。

    ==========================================================================
    为什么仍然不用 SVG / 图标字体（这是 M2 已经论证过的结论，沿用）
    ==========================================================================
    1. **SVG data URI**：微信小程序的 `<image>` 对 SVG 支持不可靠，
       而"图标全空白"是致命观感问题，不能赌平台实现。
    2. **图标字体**：小程序端字体加载有异步时序问题，多一层不确定性。
    3. **PNG 资源**：要为每个图标准备文件、无法换色、还要处理三端路径。
    4. **纯 CSS（本方案，延续 M2 的 `Icon.vue`）**：零资源、零加载时序、
       三端渲染一致（就是 border/background/transform），颜色由 `currentColor` 控制。
       代价是只能做几何图形 —— 本设计的图标恰好都是几何图形。

    ==========================================================================
    实现方式：**形状表 + 内联样式**，而不是一堆 CSS 类
    ==========================================================================
    每个图标 = 一组绝对定位的小方块（`SHAPES` 里的 CSS 片段数组），
    尺寸全部用**百分比**，因此图标只由外层 `width/height` 决定大小，
    同一套形状能用在 14px 与 24px 上，不需要为每个尺寸各写一份 CSS。

    ⚠️ 描边固定 `2px`（不随尺寸缩放）：因为要让描边随尺寸变就得用 CSS 自定义属性，
       而本项目在 M2 已经因为「小程序对 CSS 变量支持随基础库版本而变」而**刻意避开 CSS 变量**
       （见 `styles/variables.scss` 开头的说明）。因此图标最小使用尺寸定为 14px，
       在这个尺度上 2px 描边是合适的；**不要**把图标用到 12px 以下。

    ==========================================================================
    与旧 `Icon.vue` 的关系
    ==========================================================================
    本组件**取代**了 M2 的 `components/Icon.vue`（那个文件已删除），
    但**保留了它全部 8 个图标类型**（user / lock / eye / eye-off / shield / ticket / plus / arrow），
    所以 M2 的登录/注册/我的三个页面一行都不用改。
    尺寸预设名也沿用（sm / md / lg / xl），只是单位从 rpx 改成了 px。
  -->
  <view class="hy-icon" :class="[`hy-icon--${size}`]" :style="{ color: color || undefined }">
    <view v-for="(shape, index) in shapes" :key="index" class="hy-icon__s" :style="shape" />
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 图标类型。
 *
 * 分组说明（按用途，便于知道该用哪个）：
 * - 导航：home / hash / bookmark / users
 * - 操作：search / bell / heart / comment / moreH / edit / trash / logout / settings
 * - 状态：eye / eyeOff / check / close / globe / diamond
 * - M2 沿用：user / lock / shield / ticket / plus / arrow / chevron
 */
export type IconType =
  // 导航
  | 'home'
  | 'hash'
  | 'bookmark'
  | 'bookmarkFilled'
  | 'users'
  // 操作
  | 'search'
  | 'bell'
  | 'heart'
  | 'heartOutline'
  | 'heartFilled'
  | 'comment'
  | 'moreH'
  | 'edit'
  | 'trash'
  | 'logout'
  | 'settings'
  // 状态
  | 'eye'
  | 'eyeOff'
  | 'check'
  | 'close'
  | 'globe'
  | 'diamond'
  // M2 沿用
  | 'user'
  | 'lock'
  | 'shield'
  | 'ticket'
  | 'plus'
  | 'arrow'
  | 'chevron'
  | 'chevronLeft'

export type IconSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl'

const props = withDefaults(
  defineProps<{
    type?: IconType
    /** xs 12 / sm 14 / md 16 / lg 20 / xl 28（px）。⚠️ 不要用比 xs 更小的尺寸，描边不会跟着缩 */
    size?: IconSize
    /** 颜色；不传则继承父级 `color`（默认见下方 SCSS 的 $hy-icon-color） */
    color?: string
  }>(),
  { type: 'user', size: 'md', color: '' }
)

/** 描边宽度。固定值，理由见模板注释 */
const STROKE = '2px'

/**
 * 形状表：每个图标是一组绝对定位盒子的 CSS 片段。
 *
 * 写法约定（保持全表一致，否则改起来很容易漏）：
 * - 尺寸/位置一律用**百分比**（相对图标盒子），保证任意尺寸下比例正确
 * - 线框用的盒子写 `border: ${STROKE} solid currentColor` + `box-sizing: border-box`
 * - 实心盒子写 `background: currentColor`
 * - 需要旋转的写 `transform: rotate(...)`
 */
const S: string = STROKE
const line = (extra = '') => `border:${S} solid currentColor;box-sizing:border-box;${extra}`
const fill = (extra = '') => `background:currentColor;${extra}`

const SHAPES: Record<IconType, string[]> = {
  /* ---------------- 导航 ---------------- */

  // 房子：矩形墙体 + 旋转 45° 的"人字"屋顶
  home: [
    line('position:absolute;left:12%;top:44%;width:76%;height:48%;border-radius:0 0 3px 3px'),
    line(
      'position:absolute;left:22%;top:16%;width:56%;height:56%;border-right:none;border-bottom:none;transform:rotate(45deg)'
    ),
  ],

  // 井号（话题）：两根竖 + 两根横
  hash: [
    fill('position:absolute;left:30%;top:10%;width:12%;height:80%;transform:rotate(12deg)'),
    fill('position:absolute;left:58%;top:10%;width:12%;height:80%;transform:rotate(12deg)'),
    fill('position:absolute;left:12%;top:30%;width:76%;height:10%'),
    fill('position:absolute;left:12%;top:60%;width:76%;height:10%'),
  ],

  // 书签（线框）：矩形 + 底部白色三角挖出缺口
  bookmark: [
    line('position:absolute;left:22%;top:10%;width:56%;height:80%;border-radius:2px'),
    fill('position:absolute;left:30%;top:66%;width:40%;height:24%;clip-path:polygon(0 0,100% 0,50% 100%)'),
  ],

  // 书签（实心，表示"已收藏"）
  bookmarkFilled: [
    fill(
      'position:absolute;left:22%;top:10%;width:56%;height:80%;border-radius:2px;clip-path:polygon(0 0,100% 0,100% 100%,50% 72%,0 100%)'
    ),
  ],

  // 两个人（推荐关注）
  users: [
    line('position:absolute;left:4%;top:24%;width:26%;height:26%;border-radius:50%'),
    line('position:absolute;left:0%;top:58%;width:34%;height:30%;border-bottom:none;border-radius:50% 50% 0 0/100% 100% 0 0'),
    line('position:absolute;left:40%;top:10%;width:34%;height:34%;border-radius:50%'),
    line('position:absolute;left:34%;top:54%;width:44%;height:38%;border-bottom:none;border-radius:50% 50% 0 0/100% 100% 0 0'),
  ],

  /* ---------------- 操作 ---------------- */

  // 放大镜：圆环 + 手柄
  search: [
    line('position:absolute;left:8%;top:8%;width:60%;height:60%;border-radius:50%'),
    fill('position:absolute;left:62%;top:62%;width:30%;height:10%;transform:rotate(45deg);border-radius:5px'),
  ],

  // 铃铛：上圆 + 下矩形（拼成钟形）+ 小圆当铃舌
  bell: [
    line('position:absolute;left:22%;top:12%;width:56%;height:60%;border-bottom:none;border-radius:50% 50% 0 0/80% 80% 0 0'),
    line('position:absolute;left:12%;top:64%;width:76%;height:16%;border-radius:0 0 3px 3px'),
    fill('position:absolute;left:42%;top:84%;width:16%;height:16%;border-radius:50%'),
  ],

  /*
   * ==========================================================================
   * 爱心：**经典两"墓碑"画法**（2026-09-18 修正，形状终于在试验台上对齐了）
   * ==========================================================================
   * 画法：两个"墓碑"形（`border-radius: 50% 50% 0 0` 的竖矩形）各绕**心尖**
   * （即底部中心，`50% / 80%`）旋转 ∓45°，两者的并集就是一颗心。
   *
   * ⚠️ **我上一版画错了**，记在这里免得再犯：
   *    原来用的是"两个圆 + 一个旋转 45° 的方块"。那个**方块比两个圆还大**
   *    （对角线撑到 42%×2），于是并集被方块主导 —— 画出来是个**盾形**，不是心。
   *    28px 的截图里我以为"差不多"，用户一眼就看出来了（"你这个是爱心吗"）。
   *    教训：**图标的形状必须在放大图上确认**，小尺寸截图判断不了。
   *    所以本仓库留了 `scripts/icon-lab.mjs`：把候选画法放大到 120px 并排渲染，
   *    用截图挑，而不是凭想象。
   */

  /** 爱心（实心）。也用作 `heart`，语义上等价 */
  heartFilled: [
    fill(
      'position:absolute;left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg)'
    ),
    fill(
      'position:absolute;left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg)'
    ),
  ],

  /** 爱心（同实心，保留旧类型名，避免调用方因改名而崩） */
  heart: [
    fill(
      'position:absolute;left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg)'
    ),
    fill(
      'position:absolute;left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg)'
    ),
  ],

  /**
   * 爱心（线框）。
   *
   * 画法：先画实心（描边色），再用**卡片底色**按同一心尖缩小 `scale(.86)` 挖空。
   * ⚠️ 三个刻意的细节（全部是试验台上比出来的，参数别凭感觉改）：
   * 1. **不能用"给两个墓碑描边"** —— 它们在中下部是**重叠**的，
   *    描边会在心形内部交叉成一个 X（试验台候选 C 就是这个下场）；
   * 2. **`scale` 决定线宽**：`(1-s)/2` 大致就是单边厚度占比。
   *    一开始用 `.7`（≈15%/边）在 28px 下渲染出来**又粗又笨**（用户原话「爱心还是不对」），
   *    放大 5 倍才看清；现在用 `.86`（≈7%/边）才是参考图那种细线；
   * 3. **`margin-top: -4%` 是手工补的内缩偏心**：缩放绕"心尖"做，不补的话上半部分
   *    （两个圆瓣）会明显偏厚；补过头则心尖会多出一小截"尖刺"（-9% 就是这样）。
   *    注意 `margin` 的百分比是按**容器宽度**算的，不是高度。
   *
   * ⚠️ 代价：挖空用白色，**只能放在白色底上**（当前两处用法都是白卡片）。
   *    将来要用在非白底上，得把内层颜色改成所在容器的底色。
   */
  heartOutline: [
    fill(
      'position:absolute;left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg)'
    ),
    fill(
      'position:absolute;left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg)'
    ),
    fill(
      'position:absolute;left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg) scale(.88);background:#ffffff;margin-top:-2%'
    ),
    fill(
      'position:absolute;left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg) scale(.88);background:#ffffff;margin-top:-2%'
    ),
  ],

  // 评论气泡：圆角矩形 + 左下角小尾巴
  comment: [
    line('position:absolute;left:8%;top:14%;width:84%;height:58%;border-radius:6px'),
    fill(
      'position:absolute;left:22%;top:66%;width:26%;height:24%;clip-path:polygon(0 0,100% 0,20% 100%)'
    ),
  ],

  // 横向三点（"更多"）
  moreH: [
    fill('position:absolute;left:6%;top:44%;width:16%;height:16%;border-radius:50%'),
    fill('position:absolute;left:42%;top:44%;width:16%;height:16%;border-radius:50%'),
    fill('position:absolute;left:78%;top:44%;width:16%;height:16%;border-radius:50%'),
  ],

  // 铅笔（编辑）：斜置笔杆 + 笔尖
  edit: [
    line('position:absolute;left:44%;top:8%;width:22%;height:62%;border-radius:2px;transform:rotate(45deg)'),
    fill('position:absolute;left:44%;top:66%;width:22%;height:20%;clip-path:polygon(0 0,100% 0,50% 100%)'),
  ],

  // 垃圾桶：盖子 + 桶身 + 两条竖线
  trash: [
    line('position:absolute;left:16%;top:14%;width:68%;height:12%;border-radius:2px'),
    line('position:absolute;left:30%;top:4%;width:40%;height:10%;border-bottom:none;border-radius:3px 3px 0 0'),
    line('position:absolute;left:22%;top:26%;width:56%;height:64%;border-radius:0 0 3px 3px'),
    fill('position:absolute;left:40%;top:38%;width:8%;height:40%'),
    fill('position:absolute;left:54%;top:38%;width:8%;height:40%'),
  ],

  // 退出：向右的箭头 + 一根竖线（"从门里出去"）
  logout: [
    line('position:absolute;left:8%;top:14%;width:52%;height:72%;border-right:none;border-radius:3px 0 0 3px'),
    fill('position:absolute;left:40%;top:45%;width:50%;height:10%;border-radius:5px'),
    fill(
      'position:absolute;left:66%;top:24%;width:26%;height:26%;clip-path:polygon(100% 50%,0 0,0 100%)'
    ),
  ],

  // 设置：两条滑杆 + 两个旋钮（比齿轮好画得多，且同样是通用"设置"语义）
  settings: [
    fill('position:absolute;left:10%;top:26%;width:80%;height:8%;border-radius:5px'),
    fill('position:absolute;left:10%;top:66%;width:80%;height:8%;border-radius:5px'),
    line('position:absolute;left:26%;top:14%;width:24%;height:32%;border-radius:50%;background:#fff'),
    line('position:absolute;left:52%;top:54%;width:24%;height:32%;border-radius:50%;background:#fff'),
  ],

  /* ---------------- 状态 ---------------- */

  eye: [
    line('position:absolute;left:2%;top:22%;width:96%;height:56%;border-radius:50%'),
    fill('position:absolute;left:38%;top:38%;width:24%;height:24%;border-radius:50%'),
  ],

  eyeOff: [
    line('position:absolute;left:2%;top:22%;width:96%;height:56%;border-radius:50%'),
    fill('position:absolute;left:38%;top:38%;width:24%;height:24%;border-radius:50%'),
    fill('position:absolute;left:46%;top:6%;width:8%;height:88%;transform:rotate(45deg);border-radius:4px'),
  ],

  check: [
    fill('position:absolute;left:14%;top:50%;width:40%;height:10%;transform:rotate(45deg);border-radius:5px'),
    fill('position:absolute;left:38%;top:58%;width:54%;height:10%;transform:rotate(-45deg);border-radius:5px'),
  ],

  close: [
    fill('position:absolute;left:44%;top:8%;width:12%;height:84%;transform:rotate(45deg);border-radius:5px'),
    fill('position:absolute;left:44%;top:8%;width:12%;height:84%;transform:rotate(-45deg);border-radius:5px'),
  ],

  // 地球：圆 + 横线 + 竖椭圆
  globe: [
    line('position:absolute;left:6%;top:6%;width:88%;height:88%;border-radius:50%'),
    fill('position:absolute;left:6%;top:46%;width:88%;height:8%'),
    line('position:absolute;left:28%;top:6%;width:44%;height:88%;border-radius:50%'),
  ],

  // 菱形（装饰用的"活动"标记；星形在纯 CSS 里代价过高，且本项目已有"文字角标"这套语言）
  diamond: [
    fill('position:absolute;left:50%;top:50%;width:56%;height:56%;margin-left:-28%;margin-top:-28%;transform:rotate(45deg);border-radius:3px'),
  ],

  /* ---------------- M2 沿用（形状原样保留，只把 rpx 换成百分比） ---------------- */

  user: [
    line('position:absolute;left:27%;top:6%;width:46%;height:46%;border-radius:50%'),
    line(
      'position:absolute;left:11%;top:56%;width:78%;height:42%;border-bottom:none;border-radius:50% 50% 0 0/100% 100% 0 0'
    ),
  ],

  lock: [
    line('position:absolute;left:28%;top:4%;width:44%;height:42%;border-bottom:none;border-radius:50% 50% 0 0/100% 100% 0 0'),
    line('position:absolute;left:12%;top:44%;width:76%;height:56%;border-radius:4px'),
  ],

  shield: [
    line('position:absolute;left:14%;top:6%;width:72%;height:88%;border-radius:20% 20% 50% 50%/12% 12% 60% 60%'),
  ],

  ticket: [
    line('position:absolute;left:4%;top:22%;width:92%;height:56%;border-radius:4px'),
    fill('position:absolute;left:50%;top:22%;width:8%;height:56%;margin-left:-4%'),
  ],

  plus: [
    fill('position:absolute;left:10%;top:45%;width:80%;height:10%;border-radius:5px'),
    fill('position:absolute;left:45%;top:10%;width:10%;height:80%;border-radius:5px'),
  ],

  arrow: [
    fill('position:absolute;left:10%;top:45%;width:80%;height:10%;border-radius:5px'),
    line('position:absolute;left:52%;top:26%;width:36%;height:36%;border-left:none;border-bottom:none;transform:rotate(45deg)'),
  ],

  chevron: [
    line('position:absolute;left:28%;top:26%;width:36%;height:36%;border-left:none;border-bottom:none;transform:rotate(45deg)'),
  ],

  chevronLeft: [
    line('position:absolute;left:38%;top:26%;width:36%;height:36%;border-right:none;border-top:none;transform:rotate(45deg)'),
  ],
}

const shapes = computed<string[]>(() => SHAPES[props.type] ?? SHAPES.user)
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.hy-icon {
  position: relative;
  display: inline-block;
  flex-shrink: 0;
  /* 颜色默认取图标中性色，需要换色时由父级 color 或 color 属性覆盖 */
  color: $hy-icon-color;
  vertical-align: middle;

  /* ⚠️ 尺寸必须显式给（px），否则内部百分比形状没有参照、整块塌成 0 高。
     这条是 M2 踩过的坑：uni-app 的 <image>/<view> 外壳没有确定尺寸时，
     内部绝对定位元素撑不开它。 */
  &--xs {
    width: 12px;
    height: 12px;
  }
  &--sm {
    width: 14px;
    height: 14px;
  }
  &--md {
    width: 16px;
    height: 16px;
  }
  &--lg {
    width: 20px;
    height: 20px;
  }
  &--xl {
    width: 28px;
    height: 28px;
  }

  &__s {
    /* 形状统一锚定在图标盒子里，具体位置由内联样式给 */
    display: block;
  }
}
</style>
