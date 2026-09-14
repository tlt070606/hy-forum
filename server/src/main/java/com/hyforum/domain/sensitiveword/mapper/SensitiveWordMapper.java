package com.hyforum.domain.sensitiveword.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.sensitiveword.entity.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词 Mapper（{@code sensitive_word} 表）。
 *
 * <p>归属 domain：M5（内容安全）与 M6（后台敏感词管理）都要用它，
 * 按铁律 3 的落地口径，跨模块共享的数据访问必须收敛到 {@code com.hyforum.domain}。</p>
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {
}
