package com.hyforum.post.disk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网盘字段归一化：把用户粘进来的链接与提取码，整理成"库里两个字段始终一致"的状态。
 *
 * <p>契约与理由见 docs/技术方案.md §5.5 与 ADR-0008（「链接与提取码分离存储，写入时归一化」）。
 * 规则：</p>
 * <ol>
 *   <li>若链接携带 {@code pwd} 查询参数 → 把它的值写进 {@code diskCode}，并把该参数从链接里去掉；</li>
 *   <li>其余查询参数与锚点<b>原样保留</b>（丢掉它们会改变分享链接的跳转语义）；</li>
 *   <li>参数名大小写不敏感（{@code PWD} 也认），但提取码的<b>值保持原样</b>（不得被改成小写）；</li>
 *   <li>链接里带 {@code pwd} 时以链接为准，覆盖入参的 {@code diskCode} ——
 *       因为去掉 {@code pwd} 后链接已经不含提取码，若此时仍用入参，两者就不一致了；</li>
 *   <li>提取码为空/空白一律归一成 {@code null}，<b>不存空字符串</b>
 *       （存空串会让前端渲染出一行空的「提取码：」）；</li>
 *   <li>链接首尾空白去掉。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code java.net.URI} 解析</b>：分享链接是用户粘贴的自由文本，
 * 常见的形态包括中文参数值、没有编码的方括号、以及缺失 scheme 的短链。
 * {@code URI} 对这类输入会直接抛 {@code URISyntaxException}，导致"用户粘了一条能打开的链接，
 * 本站却报 500"。这里只做字符串层面的处理，对畸形输入天然宽容 ——
 * 归一化不是校验，校验（是否 http/https、长度上限）由上层在归一化之后做。</p>
 */
public final class DiskFieldNormalizer {

    /** 提取码参数名（小写口径，比较时把参数名也转小写）。 */
    private static final String PWD_PARAM = "pwd";

    private DiskFieldNormalizer() {
    }

    /**
     * 归一化。
     *
     * @param rawUrl  用户提交的分享链接（可带 {@code pwd} 参数；可为空白）
     * @param rawCode 用户单独填写的提取码（可空/空白）
     * @return 归一化后的两个字段（{@code diskCode} 可能为 {@code null}）
     */
    public static DiskFields normalize(String rawUrl, String rawCode) {
        String url = rawUrl == null ? null : rawUrl.trim();
        if (url == null || url.isEmpty()) {
            // 没有链接就谈不上提取码：非资源版块本来就不允许网盘字段，
            // 资源版块的空链接由上层校验拒绝（这里不抛异常，保持纯函数语义）
            return new DiskFields(url, blankToNull(rawCode));
        }

        // 拆锚点：只在 query 部分找 pwd，锚点里的 "pwd=" 是页面内的定位文本，不是参数
        int hashIndex = url.indexOf('#');
        String fragment = hashIndex >= 0 ? url.substring(hashIndex) : "";
        String withoutFragment = hashIndex >= 0 ? url.substring(0, hashIndex) : url;

        int queryIndex = withoutFragment.indexOf('?');
        if (queryIndex < 0) {
            // 没有任何查询参数 → 链接原样，提取码取入参
            return new DiskFields(withoutFragment + fragment, blankToNull(rawCode));
        }

        String base = withoutFragment.substring(0, queryIndex);
        String query = withoutFragment.substring(queryIndex + 1);

        String fromUrl = null;
        boolean pwdFound = false;
        List<String> keptParams = new ArrayList<>();
        for (String param : query.split("&", -1)) {
            int equals = param.indexOf('=');
            String name = equals < 0 ? param : param.substring(0, equals);
            if (name.trim().toLowerCase(Locale.ROOT).equals(PWD_PARAM)) {
                pwdFound = true;
                // 值为空也算"链接里没有提取码"，因此后面会退回入参
                String value = equals < 0 ? "" : param.substring(equals + 1);
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    fromUrl = trimmed;
                }
            } else if (!param.isEmpty()) {
                // 空参数段（例如 "...?a=1&&b=2"）直接丢掉，避免拼出畸形链接
                keptParams.add(param);
            }
        }

        StringBuilder normalized = new StringBuilder(base);
        if (!keptParams.isEmpty()) {
            normalized.append('?').append(String.join("&", keptParams));
        }
        normalized.append(fragment);

        // 链接里的 pwd 优先；链接里没有（或为空值）时用入参
        String code = fromUrl != null ? fromUrl : (pwdFound ? null : blankToNull(rawCode));
        return new DiskFields(normalized.toString(), code);
    }

    /** 空白（含全空白字符串）归一成 {@code null}。 */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
