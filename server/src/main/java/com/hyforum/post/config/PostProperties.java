package com.hyforum.post.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 帖子模块的业务参数（前缀 {@code hy.post}）。
 *
 * <p>⚠️ <b>这些默认值就是契约值，全部来自 docs/技术方案.md</b>，不是随手挑的：
 * {@link #maxImages()} 来自 §11（单帖图片 ≤ 9，对应九宫格）；{@link #newUserWindowHours()}、
 * {@link #newUserPostLimit()}、{@link #postPerHourLimit()} 来自 §8.7 的频率限制表；
 * {@link #editWindowMinutes()} 来自 §6.5（发帖后 30 分钟内可编辑）。
 * 改它们等于改契约，要走 CR（见 docs/agents/工作计划.md §3）。</p>
 *
 * <p><b>为什么默认值写在代码里而不是 {@code application.yml}</b>：任务书 §2 把
 * {@code application.yml} 的写权限定在「新增 OSS 配置项的占位」，
 * 因此 M3 没有把这些键写进主配置文件（已登记进交付报告，请 L1 裁决是否要落成配置项）。
 * 记录型属性在缺少配置时取到 0，所以这里用紧凑构造器把 0 归一到契约默认值 ——
 * <b>宁可给出确定的行为，也不要因为"配置没写"而变成"限制为 0 帖"</b>（那种故障极难排查）。</p>
 *
 * @param maxImages           单帖图片数上限（契约 9）
 * @param editWindowMinutes   发帖后可编辑的时间窗口（契约 30 分钟）
 * @param newUserWindowHours  新注册用户的观察窗口（契约 24 小时）
 * @param newUserPostLimit    新注册用户在观察窗口内的发帖上限（契约 3）
 * @param postPerHourLimit    普通用户每小时的发帖上限（契约 10）
 */
@ConfigurationProperties(prefix = "hy.post")
public record PostProperties(
        int maxImages,
        int editWindowMinutes,
        int newUserWindowHours,
        int newUserPostLimit,
        int postPerHourLimit) {

    /** 契约默认值（技术方案 §11 / §8.7 / §6.5）。 */
    public PostProperties {
        if (maxImages <= 0) {
            maxImages = 9;
        }
        if (editWindowMinutes <= 0) {
            editWindowMinutes = 30;
        }
        if (newUserWindowHours <= 0) {
            newUserWindowHours = 24;
        }
        if (newUserPostLimit <= 0) {
            newUserPostLimit = 3;
        }
        if (postPerHourLimit <= 0) {
            postPerHourLimit = 10;
        }
    }
}
