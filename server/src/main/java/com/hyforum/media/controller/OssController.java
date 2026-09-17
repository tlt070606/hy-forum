package com.hyforum.media.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.media.oss.OssCallbackRequest;
import com.hyforum.media.oss.OssCallbackVerifier;
import com.hyforum.media.oss.OssVerifyResult;
import com.hyforum.media.service.OssCallbackService;
import com.hyforum.media.service.OssSignatureService;
import com.hyforum.media.vo.OssCallbackResultVO;
import com.hyforum.media.vo.OssSignatureVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OSS 直传与回调（docs/技术方案.md §6.8）。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>路径</th><th>鉴权</th></tr>
 *   <tr><td>GET</td><td>/api/oss/signature</td><td><b>需登录</b>（未登录 401）</td></tr>
 *   <tr><td>POST</td><td>/api/oss/callback</td><td><b>匿名放行，但必须验签</b></td></tr>
 * </table>
 *
 * <h2>为什么回调是匿名的（这是裁决，不是疏漏）</h2>
 * <p>任务书 §5 裁决 ③：回调由 <b>OSS</b> 发起，<b>没有登录态</b>，因此必须匿名放行；
 * 它的安全性<b>完全由验签承担</b>。刻意<b>不</b>为了"拿到当前用户"去解析 token ——
 * 回调发生在"帖子还不存在"的时刻，本来也没有用户可言（落库口径见裁决 ④：
 * {@code post_id = 0}，发帖时认领）。</p>
 *
 * <h2>回调的响应形态（裁决 CR-A，2026-09-16）</h2>
 * <ul>
 *   <li>验签通过 → <b>HTTP 200 + 统一响应体</b> {@code {"code":0,"message":"ok"}}。
 *       OSS 官方文档要求回调响应必须是 200、必须带 {@code Content-Length}、body 为 JSON 或 XML，
 *       且该 body 会被 OSS <b>透传给上传方</b>（前端），所以它是对外可见的；</li>
 *   <li>验签失败 → <b>HTTP 403 + code 403</b>（非 2xx 才能让 OSS 判定上传失败并重试）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/oss")
@Tag(name = "OSS 直传", description = "直传签名与上传回调")
public class OssController {

    private static final Logger log = LoggerFactory.getLogger(OssController.class);

    private final OssSignatureService signatureService;
    private final OssCallbackService callbackService;
    private final OssCallbackVerifier callbackVerifier;
    private final ObjectMapper objectMapper;

    public OssController(OssSignatureService signatureService,
                         OssCallbackService callbackService,
                         OssCallbackVerifier callbackVerifier,
                         ObjectMapper objectMapper) {
        this.signatureService = signatureService;
        this.callbackService = callbackService;
        this.callbackVerifier = callbackVerifier;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取直传签名（§6.8，<b>需登录</b>）。
     *
     * <p>刻意不加 {@code @AllowAnonymous}：签名的滥用面是"匿名刷签名 + 刷 OSS 流量"，
     * 而它本身只对已登录用户有意义（发帖需要登录）。</p>
     */
    @GetMapping("/signature")
    @Operation(summary = "获取 OSS 直传签名",
            description = "返回 {host, policy, signature, dir, expire, callback}；需登录；policy 限定目录 post/、单图 ≤5MB、仅 image/*")
    public ApiResponse<OssSignatureVO> signature(HttpServletRequest request) {
        return ApiResponse.ok(signatureService.issueSignature(request));
    }

    /**
     * OSS 上传成功回调（§6.8，匿名 + 验签）。
     *
     * <p><b>请求体参数为什么是 {@code byte[]}</b>：验签算的是<b>请求体原文</b>
     * （官方算法 {@code url_decode(path) + query_string + '\n' + body}）。
     * 若声明成 DTO，Spring 会先反序列化 —— 一旦有人"顺手"重新序列化（字段顺序、空白、
     * 数字格式都会变），待签名字符串就与 OSS 算的那份不同，所有真实回调都会验签失败。
     * 用 {@code byte[]} 把原文拿在手里，验签与后续解析各用其所需。</p>
     */
    @PostMapping("/callback")
    @AllowAnonymous
    @Operation(summary = "OSS 上传回调",
            description = "OSS 发起，无登录态；必须验签（RSA+MD5，公钥来自 x-oss-pub-key-url）；"
                    + "通过后按 post_id=0、audit_status=0 落 post_image，并在 data 里回带 {id,url,thumbUrl}（CR-G）")
    public ResponseEntity<ApiResponse<OssCallbackResultVO>> callback(@RequestBody(required = false) byte[] body,
                                                       HttpServletRequest request) {
        byte[] rawBody = body == null ? new byte[0] : body;

        OssCallbackRequest verifyInput = new OssCallbackRequest(
                request.getRequestURI(),
                request.getQueryString(),
                rawBody,
                request.getHeader("Authorization"),
                request.getHeader("x-oss-pub-key-url"),
                request.getHeader("Date"));

        OssVerifyResult verifyResult = callbackVerifier.verify(verifyInput);
        if (!verifyResult.passed()) {
            // 失败原因只进日志（不进响应体）：对攻击者暴露"你差在哪一步"等于送一份调参指南
            log.warn("OSS 回调验签失败：{}（来源 {} {}）",
                    verifyResult.reason(), request.getMethod(), request.getRequestURI());
            throw new BizException(ErrorCode.FORBIDDEN, "回调验签失败");
        }

        OssCallbackResultVO registered;
        try {
            registered = callbackService.registerUploadedImage(rawBody);
            log.info("OSS 回调处理完成：{}", registered.url());
        } catch (IllegalArgumentException ex) {
            // 消息体/类型/大小/目录不合法 → 400 参数错误（不是 500，也不是 403：
            // 403 已被"验签失败"占用，混用会让运维无法区分"被伪造"与"内容不合规"）
            log.warn("OSS 回调内容不合法：{}", ex.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }

        // CR-G：把这个结果回给 OSS（它透传给前端）—— 前端据此知道回调成功、并拿到要提交的裸 URL
        return successWithContentLength(registered);
    }

    /**
     * 成功响应：<b>显式带上 {@code Content-Length}</b>。
     *
     * <p>为什么这一段是必要的：OSS 官方文档对回调响应有三条硬要求 ——
     * 「正常情况返回 HTTP/1.1 200 OK」「响应头中必须包含 Content-Length」「响应体为 JSON 或 XML」。
     * 而本项目的响应<b>全部是分块编码</b>（{@code Transfer-Encoding: chunked}，实测所有端点都如此），
     * 没有 {@code Content-Length} —— 也就是说前两条里有一条字面上不满足。</p>
     *
     * <p>为什么不改全局配置：那属于 {@code common}／容器层，会一次性改变<b>所有</b>端点的响应形态
     * （本段写权只有 media 与一个异常分支）。因此只在这一个端点上显式声明长度 ——
     * 它正是 OSS 唯一要读的那个响应。</p>
     *
     * <p>长度由<b>同一份 ObjectMapper</b> 序列化同一对象算出，与随后写出的报文逐字节一致；
     * 用 {@code ResponseEntity<ApiResponse<...>>} 而不是 {@code byte[]}，
     * 是为了让契约里这个接口的响应 schema 仍然是统一的 {@code ApiResponse...}
     * （返回 {@code byte[]} 会把契约污染成二进制）。</p>
     */
    private ResponseEntity<ApiResponse<OssCallbackResultVO>> successWithContentLength(
            OssCallbackResultVO data) {
        ApiResponse<OssCallbackResultVO> payload = ApiResponse.ok(data);
        int length;
        try {
            length = objectMapper.writeValueAsBytes(payload).length;
        } catch (JsonProcessingException ex) {
            // 序列化失败说明环境异常；此时宁可少一个响应头，也不能让回调失败
            log.warn("计算回调响应长度失败，将不带 Content-Length 返回：{}", ex.getMessage());
            return ResponseEntity.ok(payload);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(length)
                .body(payload);
    }
}
