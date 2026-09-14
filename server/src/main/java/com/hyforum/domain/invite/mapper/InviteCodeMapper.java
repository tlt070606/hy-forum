package com.hyforum.domain.invite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.invite.entity.InviteCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 邀请码 Mapper（{@code invite_code} 表）。
 */
@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {

    /**
     * <b>条件更新</b>：仅当「码未被使用 且 未失效 且（无过期时间 或 未过期）」时，
     * 才把它标记为已使用并绑定用户，返回影响行数。
     *
     * <p>为什么不能用「先 SELECT 再 UPDATE」（P1-4 定案，技术方案 §8.8）：在 RC/RR
     * 隔离级别下，两个并发事务各自 SELECT 都会看到 {@code status=0}，然后各自 UPDATE
     * 成功 —— 同一个码被用两次。而 InnoDB 的 {@code UPDATE} 是<b>当前读</b>，
     * 第二个事务会读到已提交的 {@code status=1}，{@code WHERE} 不再匹配 → 影响行数 0。</p>
     *
     * <p>三类失败（已使用 / 已失效 / 已过期）在此一并返回 0，调用方只需判断
     * {@code affected == 1}，不需要也不应该在更新前先查一次状态。</p>
     *
     * @param code 邀请码原文
     * @param userId 使用者的用户 id（注册事务内先 INSERT user 拿到 id）
     * @return 影响行数；<b>必须等于 1</b>，否则调用方要抛业务异常并回滚整个注册事务
     */
    @Update("""
            UPDATE invite_code
               SET status = 1, used_by_user_id = #{userId}, used_at = NOW()
             WHERE code = #{code}
               AND status = 0
               AND (expire_at IS NULL OR expire_at > NOW())
            """)
    int markUsedIfAvailable(@Param("code") String code, @Param("userId") Long userId);
}
