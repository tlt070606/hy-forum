package com.hyforum.post.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.post.service.BoardService;
import com.hyforum.post.vo.BoardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 版块接口（docs/技术方案.md §6.4）。
 *
 * <p>只有一个端点：版块列表（含 {@code isResource} 标记）。免登录 —— 未登录用户也要能看版块，
 * 否则首页对访客是空的。</p>
 */
@RestController
@RequestMapping("/api/boards")
@Tag(name = "版块", description = "版块列表")
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    /**
     * 版块列表（§6.4）：返回启用的版块，含 {@code isResource} 标记。
     *
     * <p>{@code @AllowAnonymous} 而不是"路径白名单"：某个接口是否需要登录，
     * 只在该接口自己身上定义一次（与 M1 的做法一致，见 {@code AuthController}）。</p>
     */
    @GetMapping
    @AllowAnonymous
    @Operation(summary = "版块列表", description = "返回启用的版块，含 isResource 标记（前端据此决定是否显示网盘字段）")
    public ApiResponse<List<BoardVO>> listBoards() {
        return ApiResponse.ok(boardService.listBoards());
    }
}
