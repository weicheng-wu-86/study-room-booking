package com.studyroom.security;

import com.studyroom.config.AppProperties;
import com.studyroom.entity.User;
import com.studyroom.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 的签发与解析（HMAC-SHA 对称签名，具体算法由密钥长度决定）。令牌中只放用户 id、账号和角色，不放任何敏感信息。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final Duration expire;
    private final Clock clock;

    public JwtUtil(AppProperties props, Clock clock) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.expire = Duration.ofHours(props.getJwt().getExpireHours());
        this.clock = clock;
    }

    public String generate(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expire)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验令牌；签名错误、已过期、格式不对都返回 null。
     */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    // 过期判断也使用注入的时钟，与签发时保持一致
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new LoginUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get("username", String.class),
                    Role.valueOf(claims.get("role", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
