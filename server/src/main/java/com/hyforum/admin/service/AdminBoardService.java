package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.admin.vo.AdminBoardVO;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.board.entity.Board;
import com.hyforum.domain.board.mapper.BoardMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 版块管理（M6 批次二；PLAN M6 验收"维护版块"）。
 *
 * <h2>slug 是身份标识，创建后不可改</h2>
 * <p>slug 是版块对外路由与 {@code uk_slug} 唯一键。改 slug 意味着所有外部引用
 * （收藏的 URL、文档截图、前端跳转）瞬间失效 —— 收益为零、破坏面全覆盖，
 * 因此更新端点<b>不接受 slug 字段</b>（传了也忽略，并在 javadoc 写明）。</p>
 *
 * <h2>停用（status=0）不删数据</h2>
 * <p>版块下可能有帖子；停用后前台版块列表不再展示（前台接口只出 status=1），
 * 帖子数据原样保留 —— 恢复启用即完整回来。</p>
 */
@Service
public class AdminBoardService {

    private static final Logger log = LoggerFactory.getLogger(AdminBoardService.class);

    public static final String ACTION_BOARD_CREATE = "BOARD_CREATE";
    public static final String ACTION_BOARD_UPDATE = "BOARD_UPDATE";

    /** slug 规则：小写字母/数字/连字符，2–30（与列宽一致）。 */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{2,30}$");

    private final BoardMapper boardMapper;
    private final AdminOperationLogger operationLogger;

    public AdminBoardService(BoardMapper boardMapper, AdminOperationLogger operationLogger) {
        this.boardMapper = boardMapper;
        this.operationLogger = operationLogger;
    }

    /** 后台版块列表（含停用的；按 sort 升序 —— 与前台排序同一口径）。 */
    @Transactional(readOnly = true)
    public List<AdminBoardVO> listBoards() {
        return boardMapper.selectList(Wrappers.<Board>lambdaQuery()
                        .orderByAsc(Board::getSort)
                        .orderByAsc(Board::getId))
                .stream()
                .map(b -> new AdminBoardVO(
                        b.getId(), b.getName(), b.getSlug(), b.getDescription(),
                        b.getIsResource() == null ? 0 : b.getIsResource(),
                        b.getSort() == null ? 0 : b.getSort(),
                        b.getStatus() == null ? 1 : b.getStatus(),
                        b.getPostCount() == null ? 0 : b.getPostCount(),
                        b.getCreatedAt()))
                .toList();
    }

    /**
     * 新建版块（{@code POST /api/admin/boards}）。
     *
     * @param adminId    操作管理员 id
     * @param name       名称（≤30 字，必填）
     * @param slug       路由标识（小写字母/数字/连字符，2–30，唯一，创建后不可改）
     * @param description 描述（可空）
     * @param sort       排序（升序，默认 0）
     * @param isResource 1 = 资源版块（发帖显示网盘字段）
     * @param ip         操作来源 IP
     * @return 留痕行 id
     */
    @Transactional
    public long createBoard(long adminId, String name, String slug,
                            String description, Integer sort, Integer isResource, String ip) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版块名称不能为空");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 30) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版块名称不能超过 30 字");
        }
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "slug 必须是小写字母/数字/连字符组成的 2–30 位字符串，收到：" + slug);
        }

        Board board = new Board();
        board.setName(trimmedName);
        board.setSlug(slug);
        board.setDescription(description == null || description.isBlank() ? null : description.trim());
        board.setSort(sort == null ? 0 : sort);
        board.setIsResource(isResource != null && isResource == 1 ? 1 : 0);
        board.setStatus(1);
        try {
            boardMapper.insert(board);
        } catch (DuplicateKeyException ex) {
            // uk_slug 撞键：给管理端一个能看懂的说法，而不是 500
            throw new BizException(ErrorCode.BAD_REQUEST, "slug 已存在：" + slug);
        }
        long logId = operationLogger.log(adminId, ACTION_BOARD_CREATE,
                AdminOperationLog.TARGET_BOARD, board.getId(), null,
                "board:" + slug + " name:" + trimmedName, ip);
        log.info("版块创建：adminId={} slug={}", adminId, slug);
        return logId;
    }

    /**
     * 更新版块（{@code PUT /api/admin/boards/{id}}）：部分更新，只改传了的字段；
     * <b>slug 不可改</b>（见类注释）。status 只接受 1 启用 / 0 停用。
     *
     * @return 留痕行 id
     */
    @Transactional
    public long updateBoard(long adminId, long boardId, String name, String description,
                            Integer sort, Integer status, Integer isResource, String ip) {
        Board board = boardMapper.selectById(boardId);
        if (board == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "版块不存在");
        }
        if (status != null && status != 0 && status != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 只支持 1（启用）/ 0（停用）");
        }
        if (isResource != null && isResource != 0 && isResource != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "isResource 只支持 0 / 1");
        }

        List<String> changed = new java.util.ArrayList<>();
        Board update = new Board();
        update.setId(boardId);
        if (name != null && !name.isBlank() && !name.trim().equals(board.getName())) {
            if (name.trim().length() > 30) {
                throw new BizException(ErrorCode.BAD_REQUEST, "版块名称不能超过 30 字");
            }
            update.setName(name.trim());
            changed.add("name");
        }
        if (description != null && !description.trim().equals(board.getDescription())) {
            update.setDescription(description.isBlank() ? null : description.trim());
            changed.add("description");
        }
        if (sort != null && sort != board.getSort()) {
            update.setSort(sort);
            changed.add("sort");
        }
        if (status != null && status != board.getStatus()) {
            update.setStatus(status);
            changed.add("status:" + board.getStatus() + "->" + status);
        }
        if (isResource != null && isResource != board.getIsResource()) {
            update.setIsResource(isResource);
            changed.add("isResource");
        }
        if (changed.isEmpty()) {
            // 没有任何字段变化：幂等成功但仍留痕（审计上"确认过一次"是有效动作）
            changed.add("no-op");
        }
        boardMapper.updateById(update);

        long logId = operationLogger.log(adminId, ACTION_BOARD_UPDATE,
                AdminOperationLog.TARGET_BOARD, boardId, null,
                "board:" + board.getSlug() + " " + String.join(",", changed), ip);
        log.info("版块更新：adminId={} slug={} 变更={}", adminId, board.getSlug(), changed);
        return logId;
    }
}
