package com.hyforum.post;

import com.hyforum.post.disk.DiskCopyText;
import com.hyforum.post.disk.DiskFieldNormalizer;
import com.hyforum.post.disk.DiskFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网盘字段归一化与一键复制格式（docs/技术方案.md §5.5、ADR-0008）。
 *
 * <p>覆盖的验收项与方法名：</p>
 * <ul>
 *   <li>{@code M3_disk_url_normalized_with_pwd} —— 拆出 {@code disk_code}、URL 去掉 {@code pwd}；</li>
 *   <li>{@code M3_disk_url_normalized_without_pwd} —— {@code disk_code} 保持空；</li>
 *   <li>{@code M3_copy_payload_format} —— 链接行 / 提取码行 / 来源行。</li>
 * </ul>
 *
 * <p>「提取码可为空」（{@code M3_disk_code_may_be_empty}）刻意做成接口级用例
 * （{@code M3PostPublishTest}）—— 那条要证明的是「阿里云盘／夸克场景<b>可以保存</b>」，
 * 单元测试只能证明"函数返回 null"，证明不了内容能落库。</p>
 *
 * <h2>为什么这组是纯单元测试（不起 Spring）</h2>
 * <p>归一化是一条纯函数式的字符串规则：把"用户从网盘 App 复制来的东西"变成
 * "库里两个字段的一致状态"。它值得有<b>密集的边界用例</b>（多种参数顺序、
 * 大小写、空值、冲突），而这些用例不该付 Spring 启动的代价 ——
 * 也正因为不起 Spring，它们才能跑得又多又快。</p>
 */
@DisplayName("M3 · 网盘字段归一化与复制格式")
class M3DiskFieldTest {

    // ==================================================================
    // 归一化：带 pwd
    // ==================================================================

    /** 验收项：{@code M3_disk_url_normalized_with_pwd}（ADR-0008）。 */
    @Test
    void M3_disk_url_normalized_with_pwd() {
        // ---------- 基本形态：百度网盘 + pwd ----------
        DiskFields basic = DiskFieldNormalizer.normalize("https://pan.baidu.com/s/1AbCdEfGh?pwd=9k2m", null);
        assertThat(basic.diskUrl())
                .as("disk_url 必须被规范化为不含 pwd 的干净链接（ADR-0008）")
                .isEqualTo("https://pan.baidu.com/s/1AbCdEfGh");
        assertThat(basic.diskCode())
                .as("pwd 必须被解析进 disk_code")
                .isEqualTo("9k2m");

        // ---------- pwd 后面还有别的参数：其他参数必须原样保留 ----------
        DiskFields withOthers = DiskFieldNormalizer.normalize(
                "https://pan.baidu.com/s/1AbCdEfGh?pwd=9k2m&from=app&t=1", null);
        assertThat(withOthers.diskUrl())
                .as("只去掉 pwd，其余查询参数不得丢失（丢了会破坏短链的跳转语义）")
                .isEqualTo("https://pan.baidu.com/s/1AbCdEfGh?from=app&t=1");
        assertThat(withOthers.diskCode()).isEqualTo("9k2m");

        // ---------- pwd 在中间 ----------
        DiskFields middle = DiskFieldNormalizer.normalize(
                "https://pan.baidu.com/s/1AbCdEfGh?from=app&pwd=9k2m&t=1", null);
        assertThat(middle.diskUrl()).isEqualTo("https://pan.baidu.com/s/1AbCdEfGh?from=app&t=1");
        assertThat(middle.diskCode()).isEqualTo("9k2m");

        // ---------- 带锚点：锚点必须保留 ----------
        DiskFields withFragment = DiskFieldNormalizer.normalize(
                "https://pan.baidu.com/s/1AbCdEfGh?pwd=9k2m#list", null);
        assertThat(withFragment.diskUrl()).isEqualTo("https://pan.baidu.com/s/1AbCdEfGh#list");
        assertThat(withFragment.diskCode()).isEqualTo("9k2m");

        // ---------- 参数名大小写不敏感（用户粘贴的链接可能来自不同来源） ----------
        DiskFields upperCase = DiskFieldNormalizer.normalize(
                "https://pan.baidu.com/s/1AbCdEfGh?PWD=9K2M", null);
        assertThat(upperCase.diskUrl()).isEqualTo("https://pan.baidu.com/s/1AbCdEfGh");
        assertThat(upperCase.diskCode()).as("提取码的值保持原样（不得被改成小写）").isEqualTo("9K2M");

        // ---------- pwd 为空值：参数要去掉，但不能造出一个空字符串的提取码 ----------
        DiskFields emptyPwd = DiskFieldNormalizer.normalize("https://pan.baidu.com/s/1AbCdEfGh?pwd=", null);
        assertThat(emptyPwd.diskUrl()).isEqualTo("https://pan.baidu.com/s/1AbCdEfGh");
        assertThat(emptyPwd.diskCode())
                .as("空提取码必须归一成 null —— 存空字符串会让前端渲染出一个空的「提取码：」行")
                .isNull();

        // ---------- 链接里的 pwd 与入参 diskCode 冲突：以链接为准 ----------
        DiskFields conflict = DiskFieldNormalizer.normalize(
                "https://pan.baidu.com/s/1AbCdEfGh?pwd=fromUrl", "fromInput");
        assertThat(conflict.diskCode())
                .as("链接里已带 pwd 时以链接为准 —— disk_url 已被剥掉 pwd，"
                        + "若 disk_code 用入参，两者就不一致（复制出去的内容是错的）")
                .isEqualTo("fromUrl");

        // ---------- 入参 diskCode 的前后空白必须去掉 ----------
        DiskFields trimmed = DiskFieldNormalizer.normalize(
                "https://pan.baidu.com/s/1AbCdEfGh", "  9k2m  ");
        assertThat(trimmed.diskCode()).isEqualTo("9k2m");
    }

    /** 验收项：{@code M3_disk_url_normalized_without_pwd}（ADR-0008）。 */
    @Test
    void M3_disk_url_normalized_without_pwd() {
        // ---------- 链接不带 pwd 且没给提取码 → disk_code 保持空 ----------
        DiskFields plain = DiskFieldNormalizer.normalize("https://www.alipan.com/s/xxxx", null);
        assertThat(plain.diskUrl()).as("不含 pwd 的链接必须原样保留").isEqualTo("https://www.alipan.com/s/xxxx");
        assertThat(plain.diskCode()).as("没有 pwd 就没有提取码，必须是 null").isNull();

        // ---------- 空字符串入参与 null 等价（都归一成 null） ----------
        DiskFields blankCode = DiskFieldNormalizer.normalize("https://pan.quark.cn/s/yyyy", "   ");
        assertThat(blankCode.diskUrl()).isEqualTo("https://pan.quark.cn/s/yyyy");
        assertThat(blankCode.diskCode()).as("空白提取码必须归一成 null").isNull();

        // ---------- 链接前后空白必须去掉（用户复制时常带一个尾空格） ----------
        DiskFields padded = DiskFieldNormalizer.normalize("  https://pan.quark.cn/s/zzz  ", null);
        assertThat(padded.diskUrl()).isEqualTo("https://pan.quark.cn/s/zzz");

        // ---------- 别的参数里出现 pwd 字样但不是参数名 → 不得被误删 ----------
        DiskFields notPwdParam = DiskFieldNormalizer.normalize(
                "https://pan.baidu.com/s/1Ab?from=pwd_test", null);
        assertThat(notPwdParam.diskUrl())
                .as("只认参数名恰为 pwd 的那一项，不得把 pwd_test 这类值误当提取码")
                .isEqualTo("https://pan.baidu.com/s/1Ab?from=pwd_test");
        assertThat(notPwdParam.diskCode()).isNull();
    }

    // ==================================================================
    // 一键复制格式
    // ==================================================================

    /**
     * 验收项：{@code M3_copy_payload_format}（技术方案 §5.5 第 4 条 / ADR-0008）。
     *
     * <p>格式是契约原文规定的三行：</p>
     * <pre>
     * 链接：{disk_url}
     * 提取码：{disk_code}
     * 来自 Hy论坛
     * </pre>
     * <p>为什么值得为"三行文本"写用例：这段文本是<b>用户唯一会带走的东西</b> ——
     * 他把它粘到群里，别人照着它去网盘取文件。少一个换行、少一个标签，
     * 复制出去的就是一段看不懂的文字；而这类"渲染细节"在代码评审里最容易被当成无关紧要。</p>
     */
    @Test
    void M3_copy_payload_format() {
        String payload = DiskCopyText.format("https://pan.baidu.com/s/1AbCdEfGh", "9k2m");

        String[] lines = payload.split("\n", -1);
        assertThat(lines)
                .as("§5.5 规定复制内容为三行（链接 / 提取码 / 来源），实际：%n%s", payload)
                .hasSize(3);
        assertThat(lines[0]).as("第一行是链接行").isEqualTo("链接：https://pan.baidu.com/s/1AbCdEfGh");
        assertThat(lines[1]).as("第二行是提取码行").isEqualTo("提取码：9k2m");
        assertThat(lines[2]).as("第三行是来源行").isEqualTo("来自 Hy论坛");

        // 换行必须是 \n 而不是 \r\n：这段文本会被写进剪贴板，
        // 而测试断言与前端拼接都以 \n 为口径（CRLF 会让逐行断言与前端 split 行为分叉）
        assertThat(payload).as("换行统一用 \\n，不得混入 \\r").doesNotContain("\r");

        // ---------- 无提取码（阿里云盘/夸克）：只保留链接行与来源行 ----------
        String noCode = DiskCopyText.format("https://pan.quark.cn/s/yyyy", null);
        assertThat(noCode.split("\n", -1))
                .as("没有提取码时不得留一行空的「提取码：」（前端此时只渲染「打开链接」按钮）")
                .hasSize(2);
        assertThat(noCode).isEqualTo("链接：https://pan.quark.cn/s/yyyy\n来自 Hy论坛");

        // ---------- 防御：链接为空时不允许拼出半截内容 ----------
        assertThatThrownBy(() -> DiskCopyText.format(null, "9k2m"))
                .as("没有链接就没有可复制的内容，必须显式报错而不是返回半截文本")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiskCopyText.format("   ", "9k2m"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
