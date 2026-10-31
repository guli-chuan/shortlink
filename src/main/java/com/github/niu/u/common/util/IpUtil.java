package com.github.niu.u.common.util;

import javax.servlet.http.HttpServletRequest;

/**
 * 取客户端 IP。
 *
 * <p><b>为什么不无条件读 X-Forwarded-For：</b>这个头是「请求头」，
 * 客户端想填什么就填什么。如果服务直接暴露在公网、又盲信 XFF，
 * 刷接口的人每次换一个假 IP 就能完全绕过按 IP 的限流——
 * 限流形同虚设，而日志里看到的全是编造的地址。</p>
 *
 * <p>所以默认取 {@code getRemoteAddr()}（TCP 连接的真实对端）。
 * 只有当服务确实部署在可信反向代理后面（例如 nginx 在同一台机器、
 * 或内网里），才打开 {@code short_url.rate-limit.trust-proxy}。
 * 那种情况下，正确做法是由**最外层代理覆盖**这个头，而不是在客户端传来的值后面追加。</p>
 *
 * @author niuxiangqian
 * @since 2026/10/30
 */
public final class IpUtil {

    private IpUtil() {
    }

    public static String getClientIp(HttpServletRequest request, boolean trustProxy) {
        if (trustProxy) {
            // XFF 是逗号分隔的链：client, proxy1, proxy2。最左边是客户端，
            // 但它同样可被伪造；只有在"代理一定覆盖/清洗过这个头"时才可信。
            String forwarded = request.getHeader("X-Forwarded-For");
            if (isUsable(forwarded)) {
                int comma = forwarded.indexOf(',');
                String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
                if (isUsable(first)) {
                    return first;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (isUsable(realIp)) {
                return realIp.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isEmpty() && !"unknown".equalsIgnoreCase(value);
    }
}
