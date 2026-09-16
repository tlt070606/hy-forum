package com.hyforum.media.oss;

/**
 * 回调请求中验签所需的全部输入（从 HTTP 层抽出来，使验签器<b>可被独立测试与替换</b>）。
 *
 * <p>任务书 §6.2 的 ★ 要求"验签器要可替换（接口 + 测试替身，或让公钥地址可注入）"——
 * 本类型是那个接缝：验签器只依赖这个纯数据对象，不依赖 {@code HttpServletRequest}，
 * 于是测试可以按官方算法构造任意请求（含伪造签名），
 * 而不需要真的跑一遍 OSS。</p>
 *
 * @param requestUri          资源路径（servlet 给出的原始 URI，验签前会按官方文档做 URL 解码）
 * @param queryString         原始查询串（<b>不含</b>前导 {@code ?}；无参时为 {@code null}）
 * @param body                请求体<b>原文字节</b>（验签算的是原文，任何反序列化-再序列化都会让它变样）
 * @param authorizationHeader {@code Authorization} 头（Base64 的 RSA-MD5 签名）
 * @param pubKeyUrlHeader     {@code x-oss-pub-key-url} 头（Base64 编码的公钥地址）
 * @param dateHeader          {@code Date} 头（RFC 1123），用于防重放窗口判定
 */
public record OssCallbackRequest(
        String requestUri,
        String queryString,
        byte[] body,
        String authorizationHeader,
        String pubKeyUrlHeader,
        String dateHeader) {

    /**
     * 官方算法里的待签名字符串：
     * {@code url_decode(path) + query_string + '\n' + body}。
     *
     * <p>两个容易写错的细节：</p>
     * <ol>
     *   <li>查询串要<b>带前导 {@code ?}</b>（官方示例里 {@code query_string} 就是
     *       {@code ?id=1&index=2}），而 servlet 的 {@code getQueryString()} 不带 ——
     *       少拼这个 {@code ?} 会让所有带参回调验签失败，且失败得很"干净"（什么都对不上）；</li>
     *   <li>{@code path} 要<b>先 URL 解码</b>（文档原文 {@code url_decode(path)}）。</li>
     * </ol>
     */
    public String stringToSign() {
        String path = java.net.URLDecoder.decode(requestUri == null ? "" : requestUri,
                java.nio.charset.StandardCharsets.UTF_8);
        String query = (queryString == null || queryString.isEmpty()) ? "" : "?" + queryString;
        byte[] rawBody = body == null ? new byte[0] : body;
        // 请求体按 UTF-8 参与签名：OSS 官方示例的 body 是文本（表单或 JSON），
        // 与 Java 端 getBytes(UTF_8) 的口径一致
        return path + query + "\n" + new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
    }
}
