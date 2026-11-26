package com.liteark.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liteark.common.R;
import com.liteark.config.LiteArkProperties;
import com.liteark.util.ClientUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 后台 IP 白名单（需求 §8.7）：拦截 /admin/** 与 /api/admin/**，配置项 liteark.admin-ip-whitelist-enabled */
@Component
@RequiredArgsConstructor
public class AdminIpWhitelistFilter extends OncePerRequestFilter {
    private final LiteArkProperties props;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (props.isAdminIpWhitelistEnabled()
                && (path.startsWith("/admin") || path.startsWith("/api/admin"))) {
            String ip = ClientUtil.ip(request);
            boolean allowed = false;
            for (String rule : props.getAdminIpWhitelist()) {
                if (rule == null) continue;
                String r = rule.trim();
                if (r.isEmpty()) continue;
                // 支持精确 IP、CIDR 前缀（如 10.10.）与 * 通配
                if ("*".equals(r) || r.equals(ip) || ip.startsWith(r.endsWith(".") ? r : r + ".")) {
                    allowed = true; break;
                }
            }
            if (!allowed) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write(objectMapper.writeValueAsString(R.fail(403, "当前 IP 不在后台访问白名单中")));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
