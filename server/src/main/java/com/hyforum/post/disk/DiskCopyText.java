package com.hyforum.post.disk;

/**
 * 一键复制的内容格式（docs/技术方案.md §5.5 第 4 条、ADR-0008）。
 *
 * <pre>
 * 链接：{disk_url}
 * 提取码：{disk_code}
 * 来自 Hy论坛
 * </pre>
 *
 * <h2>为什么这段文本在契约里、而且必须有测试</h2>
 * <p>Web 端无法直接跳转网盘 App（浏览器沙箱限制，§5.5 已明确说明），
 * 用户拿到资源的唯一途径就是<b>复制这段文本、再粘到网盘 App 里</b>。
 * 换句话说它是资源的"交付格式"：标签写错一个字、少一个换行，
 * 复制出去的就是一段看不懂的文字，而这类细节在代码评审里最容易被当成无关紧要。</p>
 *
 * <h2>换行一律用 {@code \n}</h2>
 * <p>不用 {@code System.lineSeparator()}：这段文本会被写进剪贴板，
 * 而前端的拼接逻辑与测试断言都以 {@code \n} 为口径。
 * 在 Windows 上返回 {@code \r\n} 会让"后端生成的文本"与"前端生成的文本"逐字不同 ——
 * 两份实现就此分叉，而分叉点正是最难察觉的地方。</p>
 *
 * <h2>没有提取码时不留空行</h2>
 * <p>阿里云盘与夸克没有提取码机制（ADR-0008）。此时输出两行（链接 + 来源），
 * 而不是留一行空的「提取码：」—— 用户复制到群里，那一行空标签只会让人以为漏了内容。</p>
 */
public final class DiskCopyText {

    /** 来源行（契约原文，不得改动）。 */
    public static final String SOURCE_LINE = "来自 Hy论坛";

    /** 行分隔符：固定 {@code \n}。 */
    private static final String LINE_SEPARATOR = "\n";

    private DiskCopyText() {
    }

    /**
     * 拼出"一键复制"的文本。
     *
     * @param diskUrl  归一化之后的链接（不该含 {@code pwd} 参数）
     * @param diskCode 提取码，可为 {@code null}（此时不输出提取码行）
     * @return 待写入剪贴板的文本
     * @throws IllegalArgumentException 链接为空时抛出 —— 没有链接就没有可复制的内容，
     *        静默返回半截文本会让用户复制到一段无意义的字符串
     */
    public static String format(String diskUrl, String diskCode) {
        if (diskUrl == null || diskUrl.isBlank()) {
            throw new IllegalArgumentException("网盘链接为空，无法生成一键复制内容");
        }
        StringBuilder builder = new StringBuilder()
                .append("链接：").append(diskUrl.trim());
        if (diskCode != null && !diskCode.isBlank()) {
            builder.append(LINE_SEPARATOR).append("提取码：").append(diskCode.trim());
        }
        builder.append(LINE_SEPARATOR).append(SOURCE_LINE);
        return builder.toString();
    }
}
