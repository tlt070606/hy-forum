package com.hyforum.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;

/**
 * 统一分页响应结构（docs/技术方案.md §6.1）：
 *
 * <pre>{ "code": 0, "data": { "list": [], "total": 0, "page": 1, "size": 20 } }</pre>
 *
 * <p>M1 没有任何分页接口，但分页结构属于通用约定，必须先把形状定下来，
 * 否则 M3/M4 每个模块都会自己造一个（这正是"临时约定"的来源）。</p>
 *
 * <p><b>分页硬上限 20</b>（docs/ops/deployment.md §5，对应验收项 {@code M3_page_size_hard_cap_20}）：
 * 在 {@link #of(IPage, int, int)} 里统一收敛，业务模块不需要各自判断。</p>
 *
 * @param list  当前页数据
 * @param total 总条数
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数（实际生效值，已被硬上限裁剪）
 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

    /** 分页硬上限：任何 size 请求不得超过该值（deployment §5 / §11 单帖图片≤9 同源思路）。 */
    public static final int MAX_PAGE_SIZE = 20;

    /** 默认每页条数。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 由 MyBatis-Plus 的 IPage 转换成契约结构。
     *
     * @param page      查询结果
     * @param requested 请求的页码，非法（&lt;1）时归一到 1
     * @param size      请求的每页条数，非法或超限时归一到 [1, {@value #MAX_PAGE_SIZE}]
     */
    public static <T> PageResult<T> of(IPage<T> page, int requested, int size) {
        return new PageResult<>(page.getRecords(), page.getTotal(), normalizePage(requested), normalizeSize(size));
    }

    /** 手工构造（用于尚未落库、但在内存里完成分页的场景）。 */
    public static <T> PageResult<T> of(List<T> list, long total, int requested, int size) {
        return new PageResult<>(list, total, normalizePage(requested), normalizeSize(size));
    }

    /** 页码归一：小于 1 一律按第 1 页处理，不抛异常（避免"翻到 0 页"这种无意义报错）。 */
    public static int normalizePage(int requested) {
        return requested < 1 ? 1 : requested;
    }

    /** 每页条数归一：裁剪到 [1, MAX_PAGE_SIZE]。 */
    public static int normalizeSize(int requested) {
        if (requested < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
