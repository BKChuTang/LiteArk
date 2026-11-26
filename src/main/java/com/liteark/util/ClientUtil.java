package com.liteark.util;

import javax.servlet.http.HttpServletRequest;

/** 识别访问端类型与 IP（需求 §8.7 审计日志含 IP 与 UA） */
public final class ClientUtil {

    private ClientUtil() {}

    public static String ip(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = req.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = req.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return normalizeIp(ip);
    }

    /**
     * 统一成 IPv4 可读形式，避免日志里出现 {@code 0:0:0:0:0:0:0:1} 这类本机回环写法。
     *
     * <ul>
     *   <li>{@code ::1} / {@code 0:0:0:0:0:0:0:1} → {@code 127.0.0.1}</li>
     *   <li>{@code ::ffff:192.168.1.10} → {@code 192.168.1.10}</li>
     *   <li>{@code 0:0:0:0:0:ffff:7f00:1} 这类十六进制映射 → 点分十进制</li>
     *   <li>{@code [::1]:8080} / {@code 10.0.0.1:52344} 去掉端口</li>
     * </ul>
     * 真实的公网 IPv6 地址无法等价转换，保持原样。
     */
    public static String normalizeIp(String raw) {
        if (raw == null) return "";
        String ip = raw.trim();
        if (ip.isEmpty()) return "";

        // [::1]:8080 → ::1
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end > 0) ip = ip.substring(1, end);
        }

        String lower = ip.toLowerCase();
        if ("::1".equals(lower) || "0:0:0:0:0:0:0:1".equals(lower) || "::0.0.0.1".equals(lower)) {
            return "127.0.0.1";
        }

        // IPv4-mapped IPv6：::ffff:1.2.3.4 / ::ffff:0102:0304
        for (String prefix : new String[]{"::ffff:", "0:0:0:0:0:ffff:", "0000:0000:0000:0000:0000:ffff:"}) {
            if (lower.startsWith(prefix)) {
                String tail = ip.substring(prefix.length());
                if (tail.contains(".")) return tail;
                String v4 = hexToIpv4(tail);
                return v4 == null ? ip : v4;
            }
        }

        // 已带端口的 IPv4：10.0.0.1:52344
        if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d+")) {
            return ip.substring(0, ip.lastIndexOf(':'));
        }
        return ip;
    }

    /** {@code 7f00:1} → {@code 127.0.0.1}；格式不符返回 null */
    private static String hexToIpv4(String hexPair) {
        String[] parts = hexPair.split(":");
        if (parts.length != 2) return null;
        try {
            int hi = Integer.parseInt(parts[0], 16);
            int lo = Integer.parseInt(parts[1], 16);
            return ((hi >> 8) & 0xFF) + "." + (hi & 0xFF) + "." + ((lo >> 8) & 0xFF) + "." + (lo & 0xFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String ua(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        return ua == null ? "" : ua.length() > 300 ? ua.substring(0, 300) : ua;
    }

    /** PC / Mobile：按 UA 关键字判断（UA 仅作兜底，主判断由前端传 client_type） */
    public static String clientType(HttpServletRequest req) {
        String explicit = req.getHeader("X-Client-Type");
        if ("PC".equalsIgnoreCase(explicit) || "Mobile".equalsIgnoreCase(explicit)) {
            return explicit.toUpperCase();
        }
        String ua = ua(req).toLowerCase();
        if (ua.contains("mobile") || ua.contains("iphone") || ua.contains("android")
                || ua.contains("ipad") || ua.contains("harmony")) {
            return "Mobile";
        }
        return "PC";
    }
}
