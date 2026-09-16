package com.hyforum.media.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/**
 * OSS 回调验签的测试脚手架：**本地 RSA 密钥对 + 本地公钥桩服务器 + 按官方算法签名**。
 *
 * <h2>为什么必须有正向路径（本段最核心的假绿陷阱）</h2>
 * <p>如果只写"伪造签名必须被拒"这一条，那么一个<b>永远拒绝</b>的验签器也能让它通过 ——
 * 而整条链路永远没有一张图能落库。所以这里生成一对真实密钥，用私钥按 OSS 的算法签名、
 * 让被测后端从<b>本地的公钥桩</b>取公钥验证：验证的是<b>真实验签代码路径</b>，
 * 而不是"字段齐了就落库"。</p>
 *
 * <h2>算法来源（阿里云官方文档《callback》「验证请求签名确保安全」一节，逐字对齐）</h2>
 * <pre>
 * authorization = base64_encode(rsa_sign(private_key, url_decode(path) + query_string + '\n' + body, md5))
 *   · path          资源路径（先 URL 解码）
 *   · query_string  原始查询串，**含前导 '?'**（无查询串时为空串）
 *   · body          回调消息体原文
 *   · 哈希为 MD5，算法为 RSA → Java 里就是 Signature.getInstance("MD5withRSA")
 * 公钥：请求头 x-oss-pub-key-url 是 Base64 编码的公钥地址，解码后必须以
 *   http://gosspublic.alicdn.com/ 或 https://gosspublic.alicdn.com/ 开头（文档原文）
 * </pre>
 *
 * <p>测试把"允许的公钥地址前缀"配置成本地桩的地址（见 {@code M3OssApiTestSupport}），
 * 于是既跑通了真实取公钥 + 真实验签，又完全不依赖外网与真实 OSS。</p>
 */
public final class OssCallbackTestSupport {

    /** 公钥桩服务器对外暴露的路径（形状与 gosspublic 的 callback_pub_key_v1.pem 一致）。 */
    private static final String PUBLIC_KEY_PATH = "/callback_pub_key_v1.pem";

    private static KeyPair keyPair;
    private static HttpServer server;
    private static String baseUrl;

    private OssCallbackTestSupport() {
    }

    /** 启动公钥桩（幂等：同一个 JVM 里的多个测试类共用一份）。 */
    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();

            // 端口 0 = 由系统分配空闲端口（避免固定端口在并行/重复运行时撞车）
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(PUBLIC_KEY_PATH, OssCallbackTestSupport::servePublicKey);
            server.setExecutor(null);
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        } catch (Exception ex) {
            throw new IllegalStateException("启动 OSS 公钥桩失败", ex);
        }
    }

    /** 公钥地址（形如 {@code http://127.0.0.1:PORT/}），供测试注册到"允许前缀"配置里。 */
    public static String allowedPrefix() {
        start();
        return baseUrl;
    }

    /** {@code x-oss-pub-key-url} 头的值：公钥地址的 Base64（官方文档的形态）。 */
    public static String pubKeyUrlHeader() {
        start();
        return Base64.getEncoder().encodeToString((baseUrl + PUBLIC_KEY_PATH.substring(1))
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按官方算法生成 {@code authorization} 头。
     *
     * @param requestUri   资源路径（如 {@code /api/oss/callback}）
     * @param queryString  原始查询串；无则传 {@code null}
     * @param body         回调消息体原文
     */
    public static String sign(String requestUri, String queryString, byte[] body) {
        start();
        try {
            String signStr = urlDecode(requestUri)
                    + (queryString == null || queryString.isEmpty() ? "" : "?" + queryString)
                    + "\n"
                    + new String(body, StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance("MD5withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(signStr.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("生成回调签名失败", ex);
        }
    }

    /** 用<b>另一对</b>密钥签名（模拟伪造者：签名格式合法、但密钥不是 OSS 的）。 */
    public static String signWithForeignKey(String requestUri, byte[] body) {
        start();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            PrivateKey foreign = generator.generateKeyPair().getPrivate();
            String signStr = urlDecode(requestUri) + "\n" + new String(body, StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance("MD5withRSA");
            signature.initSign(foreign);
            signature.update(signStr.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("生成伪造签名失败", ex);
        }
    }

    /** 构造 OSS 回调消息体（与后端下发的 {@code callbackBody} 模板同形）。 */
    public static byte[] callbackBody(String object, String mimeType, long size, String etag) {
        return ("{\"object\":\"" + object + "\",\"bucket\":\"hy-forum-2026\","
                + "\"size\":\"" + size + "\",\"mimeType\":\"" + mimeType + "\","
                + "\"etag\":\"" + etag + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 服务公钥：返回 PEM（X.509 SubjectPublicKeyInfo，与 gosspublic 的 .pem 同格式）。 */
    private static void servePublicKey(HttpExchange exchange) throws IOException {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        byte[] payload = pem.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/x-pem-file");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 与官方文档的 {@code url_decode(path)} 对齐。 */
    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
