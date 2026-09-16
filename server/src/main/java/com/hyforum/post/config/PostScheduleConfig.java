package com.hyforum.post.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务支持（帖子浏览量的批量回写需要它）。
 *
 * <p><b>为什么这个开关放在 {@code com.hyforum.post} 而不是 {@code com.hyforum.common}</b>：
 * M3 的写权只覆盖 {@code post/**} 与 {@code media/**}（任务书 §2），
 * {@code common/**} 属于 M1 的交付物、不由本任务改动。
 * 把 {@code @EnableScheduling} 放在本模块的配置类里，效果完全一样
 * （它是应用级的开关，与放在哪个包无关），且不越界。</p>
 *
 * <p><b>已知的副作用（如实登记）</b>：开启之后，其他模块将来加的 {@code @Scheduled}
 * 也会生效 —— 这正是所需的行为，但值得在这里写一句，
 * 免得后来者以为是哪个 M1 的配置开了它而去找不到。
 * 当前全项目只有 {@code PostViewCounter.flushPendingViews()} 一个定时任务。</p>
 */
@Configuration
@EnableScheduling
public class PostScheduleConfig {
}
