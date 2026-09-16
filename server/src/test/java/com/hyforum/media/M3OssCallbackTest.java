package com.hyforum.media;

import com.hyforum.media.support.OssCallbackTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OSS 上传回调（docs/技术方案.md §6.8／§8.4、ADR-0007）。
 *
 * <p>对应验收项（名字逐字取自任务书 §6.2）：</p>
 * <ul>
 *   <li>{@code M3_oss_callback_rejects_bad_signature} —— 伪造签名 → 拒绝、<b>库里行数不变</b>；</li>
 *   <li>{@code M3_oss_callback_then_publish_claims_image} —— 回调落库 → 发帖认领 → 详情页可见。</li>
 * </ul>
 *
 * <h2>本段的核心假绿陷阱（任务书 §6.2 的 ★，这里逐条说明怎么防）</h2>
 * <ol>
 *   <li><b>验签器若"永远拒绝"，拒绝用例必然通过</b> → 因此两条用例都带<b>正向反证</b>：
 *       同一形态的请求换成正确签名后必须<b>成功落库</b>。走的是<b>真实验签代码路径</b>
 *       （本地公钥桩 + 本地 RSA 私钥签名，算法与阿里云官方文档逐字对齐），
 *       不是"字段齐了就落库"。</li>
 *   <li><b>只断言"回调返回成功"也是假绿</b>（回调可能压根没落库）→ 因此两条用例都<b>查库</b>，
 *       并断言 {@code post_id = 0}（未认领）、{@code audit_status = 0}（CR-006：尚未人工判定）。</li>
 *   <li><b>认领用例必须证明"是同一个 HTTP 请求产生的那一行被认领"</b> → 按 URL 精确比对行，
 *       并断言认领后 {@code post_id} 等于新帖 id、{@code sort} 与请求顺序一致、
 *       详情页 {@code images} 里能看到它。</li>
 * </ol>
 */
@DisplayName("M3b · OSS 上传回调")
class M3OssCallbackTest extends M3OssApiTestSupport {

    /** 回调里出现的对象 key（必须在 {@code dir} 之下，否则会被归属校验拒绝）。 */
    private static final String OBJECT_KEY = "post/2026/09/16/m3b-callback.jpg";

    /** 验收项：{@code M3_oss_callback_rejects_bad_signature}。 */
    @Test
    void M3_oss_callback_rejects_bad_signature() {
        int before = countAllImages();

        // ---------- ① 伪造签名（格式合法、密钥不是 OSS 的）→ 403 且不落库 ----------
        byte[] forgedBody = OssCallbackTestSupport.callbackBody(
                "post/2026/09/16/forged.jpg", IMAGE_CONTENT_TYPE, 12345L, "etag-forged");
        Response forged = postRawCallback(forgedBody,
                OssCallbackTestSupport.signWithAttackerKey("/api/oss/callback", forgedBody),
                OssCallbackTestSupport.pubKeyUrlHeader());

        assertThat(forged.statusCode())
                .as("伪造签名必须被拒（裁决 CR-A：403）。响应：%s", forged.asString())
                .isEqualTo(403);
        assertThat(forged.jsonPath().getInt("code")).isEqualTo(403);
        assertThat(countAllImages())
                .as("验签失败**不得落库** —— 只回错误码却照样写库，等于验签没生效")
                .isEqualTo(before);
        assertThat(findImageRowByUrl(imageUrlOf(signatureHost(), "post/2026/09/16/forged.jpg")))
                .as("伪造请求对应的行不得存在")
                .isNull();

        // ---------- ② 缺 Authorization 头 → 同样拒绝 ----------
        Response noSignature = postRawCallback(forgedBody, "", OssCallbackTestSupport.pubKeyUrlHeader());
        assertThat(noSignature.statusCode())
                .as("没有签名头的回调必须被拒。响应：%s", noSignature.asString())
                .isEqualTo(403);
        assertThat(countAllImages()).isEqualTo(before);

        // ---------- ③ 伪造**公钥地址** + 攻击者自签的"合法"签名 → 必须拒绝 ----------
        // 任务书 §5.6 的**方向②**，也是那一节里唯一被称为"安全闸门"的一步：
        // 若实现成"头里给什么 URL 就去取什么公钥"，攻击者自带一对密钥即可让验签**全部通过**
        // （且顺手构成 SSRF）。只打方向①的话，实现漏掉域名白名单时 ① 照样绿 —— 绿得毫无意义。
        byte[] attackerBody = OssCallbackTestSupport.callbackBody(
                "post/2026/09/16/attacker-supplied-key.jpg", IMAGE_CONTENT_TYPE, 2048L, "etag-attacker");
        String attackerSignature = OssCallbackTestSupport.signWithAttackerKey("/api/oss/callback", attackerBody);

        // ③-a **可达但不在允许名单内**的攻击者公钥服务器 + 与之配对的签名 → 必须拒绝
        //   ⚠️ 这一条是方向②里**唯一具备区分力**的那条：攻击者公钥必须真的能取到，
        //   否则"取公钥失败 → 拒绝"会让断言照样绿，用例就永远抓不到"没做域名白名单"这个缺陷。
        Response attackerKeyServer = postRawCallback(attackerBody, attackerSignature,
                OssCallbackTestSupport.pubKeyUrlHeaderFor(OssCallbackTestSupport.attackerPublicKeyUrl()));
        assertThat(attackerKeyServer.statusCode())
                .as("自带一对密钥（可达的攻击者公钥 + 自签）必须被拒，否则验签可被完全绕过：%s",
                        attackerKeyServer.asString())
                .isEqualTo(403);

        // ③-b 仿冒域名（不可达，故这条只证明"域名校验把非白名单地址挡在外面"，区分力弱于 ③-a）
        Response lookalikeKeyUrl = postRawCallback(attackerBody, attackerSignature,
                OssCallbackTestSupport.pubKeyUrlHeaderFor(
                        "https://gosspublic.alicdn.com.evil.example.com/pub.pem"));
        assertThat(lookalikeKeyUrl.statusCode())
                .as("与合法域名长得像的仿冒地址必须被拒：%s", lookalikeKeyUrl.asString())
                .isEqualTo(403);

        // ③-c 公钥地址头的形态本身非法（不是 Base64）
        Response malformedKeyUrl = postRawCallback(attackerBody, attackerSignature, "%%%not-base64%%%");
        assertThat(malformedKeyUrl.statusCode())
                .as("公钥地址头不是合法 Base64 时必须被拒：%s", malformedKeyUrl.asString())
                .isEqualTo(403);

        assertThat(countAllImages())
                .as("三个伪造/非法公钥地址的请求都不得落库")
                .isEqualTo(before);

        // ---------- ③-d 公钥地址**在允许名单内但取不到**（404）→ 必须拒绝 ----------
        // 打的是"取公钥失败被吞掉"的实现：若失败被忽略（或返回空密钥继续走），
        // 验签会退化成"永远通过"。这里用允许名单内的地址 + 一个不存在的路径。
        Response unfetchableKey = postRawCallback(attackerBody, attackerSignature,
                OssCallbackTestSupport.pubKeyUrlHeaderFor(
                        OssCallbackTestSupport.allowedPrefix() + "not-found.pem"));
        assertThat(unfetchableKey.statusCode())
                .as("公钥取不到时（允许名单内 404）必须拒绝，而不是继续放行：%s",
                        unfetchableKey.asString())
                .isEqualTo(403);

        // ---------- ③-e 陈旧请求（Date 过旧）见独立用例 M3_oss_callback_rejects_stale_timestamp ----------

        assertThat(countAllImages())
                .as("公钥取不到与伪造公钥地址的请求都不得落库")
                .isEqualTo(before);

        assertThat(countAllImages())
                .as("公钥取不到与陈旧请求都不得落库")
                .isEqualTo(before);

        // ---------- ④ 反证：同一形态的请求，换成**正确签名**必须成功落库 ----------
        // 没有这一步，"拒绝"可能只是因为验签器永远拒绝（★ 陷阱一）
        byte[] goodBody = OssCallbackTestSupport.callbackBody(
                "post/2026/09/16/after-reject.jpg", IMAGE_CONTENT_TYPE, 2048L, "etag-good");
        Response good = postOssStyleCallback(goodBody,
                OssCallbackTestSupport.sign("/api/oss/callback", null, goodBody),
                OssCallbackTestSupport.pubKeyUrlHeader());
        assertThat(good.statusCode())
                .as("正确签名必须被接受（否则本用例证明不了任何事）。响应：%s", good.asString())
                .isEqualTo(200);
        assertThat(good.jsonPath().getInt("code")).isZero();
        assertThat(good.header("Content-Length"))
                .as("OSS 官方文档要求回调响应必须带 Content-Length（否则它无法确认响应完整）")
                .isNotNull();
        assertThat(countAllImages())
                .as("验签通过必须真的落库（只能靠查库证明，不能只看响应）")
                .isEqualTo(before + 1);

        // ---------- ⑤ 验签通过但**内容不合规**的四条分支（见下面的私有方法） ----------
        assertDisallowedContentIsRejected();
    }

    /**
     * 追加断言：<b>验签通过但内容不合规</b>时必须 400 且不落库；合规时必须 200 且落库。
     *
     * <p><b>为什么不新开一个测试方法</b>：映射表（L1 独占）已按任务书 §6.2 预登记 4 个名字，
     * 新增名字会立刻在 {@code check_test_coverage_gaps.ps1} 的"多余"清单里冒出来，
     * 而任务书 §6.3 第 4 条明确要求"「多余」里不应再有本段的测试名"。
     * 这四条分支（类型白名单 / 5MB 上限 / 目录归属 / 幂等）与"不该落库的东西不许落库"
     * 是同一个主题，因此作为本用例的追加断言 —— 覆盖到了，又不制造映射表噪音。</p>
     *
     * <p>为什么必须测：这三条校验是 §8.4 与 ADR-0007 明确要求的"回调必须校验文件类型与大小"，
     * 若它们失效，任何人都能把桶当网盘用、或把桶里任意对象注册成帖子图片 ——
     * 而"验签正确"完全不覆盖这一类问题。</p>
     */
    private void assertDisallowedContentIsRejected() {
        int before = countAllImages();

        // ① 类型不在白名单（§8.4：只允许 jpeg/png/webp/gif）
        Response wrongType = postSignedCallback("post/2026/09/16/x.zip", "application/zip", 1024L);
        assertThat(wrongType.statusCode())
                .as("非图片类型必须被拒（否则桶会被当成网盘用）：%s", wrongType.asString())
                .isEqualTo(400);

        // ② 超过 5MB（§8.4 单图上限）
        Response tooBig = postSignedCallback("post/2026/09/16/big.jpg", IMAGE_CONTENT_TYPE,
                MAX_IMAGE_BYTES + 1);
        assertThat(tooBig.statusCode())
                .as("超过 5MB 必须被拒：%s", tooBig.asString())
                .isEqualTo(400);

        // ③ 对象不在帖子图片目录内（§8.4 第 4 条的归属校验在回调侧的另一端）
        Response outsideDir = postSignedCallback("other/2026/09/16/sneaky.jpg", IMAGE_CONTENT_TYPE, 1024L);
        assertThat(outsideDir.statusCode())
                .as("目录外的对象不得注册成帖子图片：%s", outsideDir.asString())
                .isEqualTo(400);

        assertThat(countAllImages())
                .as("三条不合规的回调都不得落库")
                .isEqualTo(before);

        // ④ 幂等：合规内容重复投递（OSS 会重试回调）不得产生第二行
        Response good = postSignedCallback("post/2026/09/16/content-ok.jpg", IMAGE_CONTENT_TYPE, 1024L);
        assertThat(good.statusCode()).as("合规内容必须成功：%s", good.asString()).isEqualTo(200);
        assertThat(countAllImages()).as("首次投递应新增一行").isEqualTo(before + 1);

        Response duplicate = postSignedCallback("post/2026/09/16/content-ok.jpg", IMAGE_CONTENT_TYPE, 1024L);
        assertThat(duplicate.statusCode())
                .as("重复投递必须仍返回成功（幂等），否则 OSS 会一直重试：%s", duplicate.asString())
                .isEqualTo(200);
        assertThat(countAllImages())
                .as("重复投递不得产生第二行（否则认领只会认领一行，另一行永远留着）")
                .isEqualTo(before + 1);
    }

    /** 验收项：{@code M3_oss_callback_then_publish_claims_image}。 */
    @Test
    void M3_oss_callback_then_publish_claims_image() {
        String host = signatureHost();
        String expectedUrl = imageUrlOf(host, OBJECT_KEY);
        assertThat(findImageRowByUrl(expectedUrl)).as("前置条件：该 URL 之前不该有行").isNull();

        // ---------- ① 真实验签通过 → 落库为"未认领"（post_id=0） ----------
        byte[] body = OssCallbackTestSupport.callbackBody(
                OBJECT_KEY, IMAGE_CONTENT_TYPE, 54321L, "etag-claim");
        Response callback = postRawCallback(body,
                OssCallbackTestSupport.sign("/api/oss/callback", null, body),
                OssCallbackTestSupport.pubKeyUrlHeader());
        assertThat(callback.statusCode()).as("回调必须 200：%s", callback.asString()).isEqualTo(200);
        assertThat(callback.jsonPath().getInt("code")).isZero();

        Map<String, Object> row = findImageRowByUrl(expectedUrl);
        assertThat(row)
                .as("回调后库里必须有这一行（查库，不看响应）")
                .isNotNull();
        assertThat(String.valueOf(row.get("post_id")))
                .as("回调发生在'帖子还不存在'的时刻，必须落 post_id=0（PostImage.UNBOUND_POST_ID），"
                        + "不许猜一个真实 id")
                .isEqualTo("0");
        assertThat(String.valueOf(row.get("audit_status")))
                .as("CR-006：新图一律是 0（尚未被人工判定），前台**不隐藏** 0")
                .isEqualTo("0");

        // ---------- ② 发帖带上该 URL → 该行被认领 ----------
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();
        long postId = createPostAndGetId(author.token(), withImages(
                postCreateBody(boardId, "回调落库的图应被认领", "正文"), List.of(expectedUrl)));

        Map<String, Object> claimed = findImageRowByUrl(expectedUrl);
        assertThat(claimed).as("认领后行仍在（认领是 UPDATE，不是重新 INSERT）").isNotNull();
        assertThat(String.valueOf(claimed.get("post_id")))
                .as("该行必须被认领到新帖（post_id = %d）", postId)
                .isEqualTo(String.valueOf(postId));
        assertThat(String.valueOf(claimed.get("sort")))
                .as("sort 必须与请求里的图片顺序一致（这里是唯一一张，故为 0）")
                .isEqualTo("0");
        assertThat(String.valueOf(claimed.get("audit_status")))
                .as("认领不得改写审核状态")
                .isEqualTo("0");
        assertThat(String.valueOf(claimed.get("thumb_url")))
                .as("认领时必须补上缩略图 URL（回调不写它，缩略图规则的唯一实现仍在 post 模块，"
                        + "避免两个模块各写一份）")
                .contains("x-oss-process");

        // ---------- ③ 详情页能看到它（闭环到 M3 的验收标准） ----------
        Response detail = getPostDetail(postId);
        assertThat(detail.jsonPath().getList("data.images.url"))
                .as("详情页 images 里必须能看到这张图：%s", detail.asString())
                .containsExactly(expectedUrl);
        assertThat(detail.jsonPath().getInt("data.imageCount")).isEqualTo(1);
        assertThat(detail.jsonPath().getString("data.coverUrl"))
                .as("封面 = 首图缩略图")
                .isEqualTo(String.valueOf(claimed.get("thumb_url")));
    }

    /**
     * 验收项：{@code M3_oss_callback_rejects_stale_timestamp}（L1 于 2026-09-16 登记进映射表）。
     *
     * <h2>为什么这道窗口是这条链上<b>唯一</b>的防重放控制</h2>
     * <p><b>{@code Date} 不在官方签名公式里</b>（{@code url_decode(path) + query_string + '\n' + body}）
     * —— 也就是说它是<b>未被签名覆盖</b>的字段：任何抓到过一次合法回调的人（或中间人）
     * 都可以把 {@code Date} 改新再投，签名照样成立。因此"验签通过"完全不等于"这不是重放"，
     * 唯一的防线就是请求时效窗口（{@code hy.oss.callback.max-age-seconds}，默认 15 分钟）。</p>
     *
     * <p>这也是为什么"实现了但没断言"在本项目等于没实现（H7 的先例：安全行为没验证过 = 没实现）。</p>
     *
     * <h2>两条构成一对（缺一即为假绿）</h2>
     * <ol>
     *   <li>反证：窗口内（{@code Date} = 现在）→ <b>200 且真的落库</b>。
     *       缺了它，一个"永远拒绝"的实现照样绿；而且若签名本身有问题，
     *       "签名不匹配"会先把它拒掉，断言就变成"因为别的原因被拒"——
     *       看着绿，实际一个字节的防重放逻辑都没验到。</li>
     *   <li>断言：偏差超窗 → <b>403 且 post_image 行数不变</b>（两侧都打：
     *       过去方向与未来方向 —— 实现用的是 {@code abs(偏差)}，
     *       只测一侧的话"只拦过去、放过未来"这种半截实现测不出来）。</li>
     * </ol>
     */
    @Test
    void M3_oss_callback_rejects_stale_timestamp() {
        String host = signatureHost();
        String objectKey = "post/2026/09/16/replay-window.jpg";

        byte[] body = OssCallbackTestSupport.callbackBody(objectKey, IMAGE_CONTENT_TYPE, 2048L, "etag-replay");
        String signature = OssCallbackTestSupport.sign("/api/oss/callback", null, body);

        // ---------- ① 反证：窗口内（Date = 现在）必须 200 且真的落库 ----------
        int beforeFresh = countAllImages();
        Response fresh = postCallbackWithDate(body, signature, OssCallbackTestSupport.pubKeyUrlHeader(),
                rfc1123Date(java.time.Duration.ZERO));
        assertThat(fresh.statusCode())
                .as("同一份签名 + 窗口内的 Date 必须成功（否则下面的拒绝断言说明不了任何事）：%s",
                        fresh.asString())
                .isEqualTo(200);
        assertThat(findImageRowByUrl(imageUrlOf(host, objectKey)))
                .as("窗口内的请求必须真的落库").isNotNull();
        assertThat(countAllImages())
                .as("窗口内的请求应当让 post_image 多一行")
                .isEqualTo(beforeFresh + 1);

        // ---------- ② 偏差超窗：两侧都必须 403，且 post_image 行数不变 ----------
        // 每次都用**各自的有效签名**（签名只覆盖 path+query+body，与 Date 无关），
        // 因此唯一能让它被拒的原因就是"偏差超窗" —— 断言才有指向性。
        int beforeStale = countAllImages();
        assertStaleRequestRejected("post/2026/09/16/stale-past.jpg", "过去方向（16 分钟前）",
                java.time.Duration.ofMinutes(-16), beforeStale);
        assertStaleRequestRejected("post/2026/09/16/stale-future.jpg", "未来方向（16 分钟后）",
                java.time.Duration.ofMinutes(16), beforeStale);

        assertThat(countAllImages())
                .as("两次超窗请求都不得落库（行数必须与拒绝前一致）")
                .isEqualTo(beforeStale);
    }

    /**
     * 发一个"签名有效但 {@code Date} 偏差超窗"的回调，断言被拒且不落库。
     *
     * @param objectKey        对象 key（必须在 post/ 目录内，否则会因内容校验被拒 —— 那就不是本断言要测的东西）
     * @param label            断言信息里的方向说明
     * @param offset           相对现在的偏差（±16 分钟）
     * @param expectedRowCount 拒绝后应当保持的行数
     */
    private void assertStaleRequestRejected(String objectKey, String label,
                                            java.time.Duration offset, int expectedRowCount) {
        byte[] body = OssCallbackTestSupport.callbackBody(objectKey, IMAGE_CONTENT_TYPE, 2048L, "etag-stale");
        String signature = OssCallbackTestSupport.sign("/api/oss/callback", null, body);

        Response stale = postCallbackWithDate(body, signature, OssCallbackTestSupport.pubKeyUrlHeader(),
                rfc1123Date(offset));

        assertThat(stale.statusCode())
                .as("%s 超窗（偏差 %d 分钟）必须 403：%s", label, Math.abs(offset.toMinutes()),
                        stale.asString())
                .isEqualTo(403);
        assertThat(stale.jsonPath().getInt("code"))
                .as("拒绝时也必须是统一响应体里的 403（CR-008）")
                .isEqualTo(403);
        assertThat(countAllImages())
                .as("%s 被拒后 post_image 行数必须不变（只回错误码却照样落库 = 防重放没生效）", label)
                .isEqualTo(expectedRowCount);
    }

    /**
     * 从签名接口取出本项目 OSS 的 host。
     *
     * <p>刻意**不**在测试里硬写域名：host 由后端按 {@code aliyun.oss.bucket-name + endpoint} 推导，
     * 测试从被测接口读回它，才是在验证"两边拼出来的 URL 一致" ——
     * 硬写一份就等于把域名抄了两遍，而那正是本项目反复吃过的"第二份会过期的事实"。</p>
     */
    private String signatureHost() {
        TestUser user = createFreshUser();
        Response signed = getSignature(user.token());
        String host = signed.jsonPath().getString("data.host");
        assertThat(host).as("签名接口必须能给出 host：%s", signed.asString()).isNotBlank();
        return host;
    }
}
