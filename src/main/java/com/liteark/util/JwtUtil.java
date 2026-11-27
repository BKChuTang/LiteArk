package com.liteark.util;

import com.liteark.config.LiteArkProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtUtil {
    private final LiteArkProperties props;

    private Key key() {
        return Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generate(Long userId, String username) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + props.getJwtExpireMs()))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** 二次验证中间态令牌：仅能用于验证码校验接口，不能访问业务接口。 */
    public String generatePending(Long userId, String username) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("purpose", "totp")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 5 * 60 * 1000L))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isPending(String token) {
        try {
            return "totp".equals(parse(token).get("purpose", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** 校验并解析二次验证中间态令牌，过期或类型不符返回 null。 */
    public Claims parsePending(String token) {
        try {
            Claims c = parse(token);
            return "totp".equals(c.get("purpose", String.class)) ? c : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).getBody();
    }

    public Long userId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }
}
