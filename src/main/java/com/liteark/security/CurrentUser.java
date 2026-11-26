package com.liteark.security;

import com.liteark.common.BizException;
import com.liteark.entity.User;
import com.liteark.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUser {
    private final UserMapper userMapper;

    public Long id() {
        Object p = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof Long)) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return (Long) p;
    }

    /** 公开接口用：未登录返回 null，不抛 401 */
    public Long optionalId() {
        Object p = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return p instanceof Long ? (Long) p : null;
    }

    public User get() {
        User u = userMapper.selectById(id());
        if (u == null) throw new BizException(401, "用户不存在");
        if (u.getStatus() != null && u.getStatus() != 1) throw new BizException(403, "账号已被禁用");
        return u;
    }

    public boolean isAdmin() {
        return get().getRoleId() != null && get().getRoleId() == 1L;
    }

    public void requireAdmin() {
        if (!isAdmin()) throw new BizException(403, "无管理员权限");
    }
}
