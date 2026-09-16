package com.hyforum.post.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.domain.board.entity.Board;
import com.hyforum.domain.board.mapper.BoardMapper;
import com.hyforum.post.vo.BoardVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 版块服务（docs/技术方案.md §6.4）。
 *
 * <p>版块是<b>数据库里的数据，不是硬编码</b>（§4.3 的注释）：后台可以自由增删改，
 * 因此这里不缓存、也不在代码里维护任何版块清单 —— 一旦有第二份清单，
 * 它必然与库里漂移，而漂移的表现是"后台加了版块，前台看不到"。</p>
 */
@Service
public class BoardService {

    private final BoardMapper boardMapper;

    public BoardService(BoardMapper boardMapper) {
        this.boardMapper = boardMapper;
    }

    /**
     * 前台可见的版块列表：只返回启用的（{@code status=1}），按 {@code sort} 升序。
     *
     * <p>停用版块必须被过滤掉：{@code status} 若不参与查询条件，后台"停用"就只是改了个数字，
     * 前台照样显示 —— 停用一个版块最常见的动机是"这个版块的内容不适合继续曝光"，
     * 让它在入口处继续可见等于没停。</p>
     *
     * <p>列表不分页：默认版块只有 7 个（§4.3），且它是导航结构而非内容流。</p>
     */
    public List<BoardVO> listBoards() {
        List<Board> boards = boardMapper.selectList(
                Wrappers.<Board>lambdaQuery()
                        .eq(Board::getStatus, 1)
                        .orderByAsc(Board::getSort)
                        .orderByAsc(Board::getId));
        return boards.stream().map(BoardVO::from).toList();
    }
}
