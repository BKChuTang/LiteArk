package com.liteark.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liteark.common.BizException;
import com.liteark.config.LiteArkProperties;
import com.liteark.entity.FileMeta;
import com.liteark.entity.Share;
import com.liteark.entity.ShareAccessLog;
import com.liteark.entity.User;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.ShareAccessLogMapper;
import com.liteark.mapper.ShareMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.util.ClientUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShareService {
    private final ShareMapper shareMapper;
    private final FileMetaMapper fileMapper;
    private final ShareAccessLogMapper accessMapper;
    private final LiteArkProperties props;
    private final JdbcTemplate jdbcTemplate;
    private final UserMapper userMapper;

    public Share create(Long ownerId, Long fileId, String permission, String password, Integer expireDays,
                        Integer maxDownloads, String shareType, List<Long> targetIds) {
        FileMeta f = fileMapper.selectById(fileId);
        // 不再要求创建者必须是所有者：只要通过了 Controller 的 share_internal/share_external 权限校验即可
        if (f == null || f.getIsDeleted() == 1) {
            throw new BizException(404, "文件不存在");
        }
        boolean internal = "internal".equals(shareType);
        Set<Long> targets = new LinkedHashSet<>();
        if (internal) {
            if (targetIds == null || targetIds.isEmpty()) throw new BizException(400, "请至少选择一位内部用户");
            for (Long id : targetIds) {
                if (id == null || id <= 0) continue;
                User u = userMapper.selectById(id);
                if (u == null || (u.getStatus() != null && u.getStatus() != 1)) {
                    throw new BizException(400, "所选内部用户不存在或已禁用");
                }
                targets.add(id);
            }
            if (targets.isEmpty()) throw new BizException(400, "请至少选择一位内部用户");
        }
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().substring(0, 8);
        Share s = new Share();
        s.setToken(token);
        s.setFileId(fileId);
        s.setOwnerId(ownerId);
        s.setPermission(permission == null ? "view" : permission);
        s.setPassword(internal ? "" : (password == null ? "" : password));
        int days = expireDays == null ? 7 : expireDays;
        s.setExpiresAt(days > 0 ? fmt(System.currentTimeMillis() + days * 86400000L) : null);
        s.setRevoked(0);
        s.setMaxDownloads(maxDownloads == null ? 0 : Math.max(0, maxDownloads));
        s.setDownloadCount(0);
        s.setShareType(internal ? "internal" : "external");
        s.setTargetUserIds(targets.stream().map(String::valueOf).collect(Collectors.joining(",")));
        shareMapper.insert(s);
        return s;
    }

    public List<Share> myList(Long ownerId) {
        return shareMapper.selectList(new LambdaQueryWrapper<Share>()
                .eq(Share::getOwnerId, ownerId).eq(Share::getRevoked, 0)
                .orderByDesc(Share::getCreatedAt));
    }

    public void update(Long ownerId, Long id, String permission, String password, Integer expireDays, Integer maxDownloads,
                       String shareType, List<Long> targetIds) {
        Share s = shareMapper.selectById(id);
        if (s == null || !s.getOwnerId().equals(ownerId)) throw new BizException(404, "共享不存在");
        if (permission != null) s.setPermission(permission);
        if (shareType != null) {
            boolean internal = "internal".equals(shareType);
            s.setShareType(internal ? "internal" : "external");
            if (internal) {
                if (targetIds == null || targetIds.isEmpty()) throw new BizException(400, "请至少选择一位内部用户");
                s.setTargetUserIds(targetIds.stream().filter(x -> x != null && x > 0)
                        .map(String::valueOf).distinct().collect(Collectors.joining(",")));
                if (s.getTargetUserIds().isEmpty()) throw new BizException(400, "请至少选择一位内部用户");
                s.setPassword("");
            } else {
                s.setTargetUserIds("");
                if (password != null) s.setPassword(password);
            }
        } else if (password != null && !"internal".equals(s.getShareType())) {
            s.setPassword(password);
        }
        if (expireDays != null) {
            s.setExpiresAt(expireDays > 0 ? fmt(System.currentTimeMillis() + expireDays * 86400000L) : null);
        }
        if (maxDownloads != null) { s.setMaxDownloads(Math.max(0, maxDownloads)); s.setDownloadCount(0); }
        shareMapper.updateById(s);
    }

    /** 内部分享：必须登录且在名单中；外部分享不限制 */
    public void requireInternalAccess(Share s, Long viewerId) {
        if (s == null || !"internal".equals(s.getShareType())) return;
        if (viewerId == null) throw new BizException(401, "该分享仅对内部用户开放，请先登录");
        if (viewerId.equals(s.getOwnerId())) return;   // 创建者始终可打开自己的分享
        Set<Long> ids = parseTargets(s.getTargetUserIds());
        if (!ids.contains(viewerId)) throw new BizException(403, "你不在该分享的可访问名单中");
    }

    private Set<Long> parseTargets(String raw) {
        Set<Long> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String part : raw.split(",")) {
            try { out.add(Long.parseLong(part.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public void revoke(Long ownerId, Long id) {
        Share s = shareMapper.selectById(id);
        if (s == null || !s.getOwnerId().equals(ownerId)) throw new BizException(404, "共享不存在");
        s.setRevoked(1);
        shareMapper.updateById(s);
    }

    /** 公开访问校验：返回 null 表示通过；否则返回错误信息或 NEED_PASSWORD */
    public String check(String token, String password, HttpServletRequest req) {
        Share s = shareMapper.selectOne(new LambdaQueryWrapper<Share>()
                .eq(Share::getToken, token).last("limit 1"));
        if (s == null) return "共享链接不存在或已被撤销";
        if (s.getRevoked() != null && s.getRevoked() == 1) {
            if (s.getMaxDownloads() != null && s.getMaxDownloads() > 0
                    && (s.getDownloadCount() == null ? 0 : s.getDownloadCount()) >= s.getMaxDownloads()) {
                return "共享下载次数已用完";
            }
            return "共享链接不存在或已被撤销";
        }
        if (s.getExpiresAt() != null) {
            try {
                if (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s.getExpiresAt()).getTime() < System.currentTimeMillis()) {
                    return "共享链接已过期";
                }
            } catch (Exception e) {
                return "共享链接已过期";
            }
        }
        if (s.getPassword() != null && !s.getPassword().isEmpty() && !s.getPassword().equals(password)) {
            return "NEED_PASSWORD";
        }
        if (s.getMaxDownloads() != null && s.getMaxDownloads() > 0
                && (s.getDownloadCount() == null ? 0 : s.getDownloadCount()) >= s.getMaxDownloads()) {
            return "共享下载次数已用完";
        }
        return null;
    }

    public Share byToken(String token) {
        return shareMapper.selectOne(new LambdaQueryWrapper<Share>()
                .eq(Share::getToken, token).eq(Share::getRevoked, 0).last("limit 1"));
    }

    /** 下载计数，达到上限自动撤销（原子更新，避免并发超额） */
    @Transactional
    public void countDownload(Long shareId) {
        jdbcTemplate.update("UPDATE share SET download_count = COALESCE(download_count,0) + 1, "
                + "revoked = CASE WHEN max_downloads > 0 AND COALESCE(download_count,0) + 1 >= max_downloads THEN 1 ELSE revoked END "
                + "WHERE id = ?", shareId);
    }

    public void access(Long shareId, String token, HttpServletRequest req) {
        ShareAccessLog log = new ShareAccessLog();
        log.setShareId(shareId);
        log.setToken(token);
        log.setIp(ClientUtil.ip(req));
        log.setUa(ClientUtil.ua(req));
        log.setClientType(ClientUtil.clientType(req));
        accessMapper.insert(log);
    }

    public List<ShareAccessLog> accessList(Long shareId) {
        return accessMapper.selectList(new LambdaQueryWrapper<ShareAccessLog>()
                .eq(ShareAccessLog::getShareId, shareId)
                .orderByDesc(ShareAccessLog::getAccessedAt).last("limit 500"));
    }

    /** 判断 nodeId 是否位于 rootId 子树内（防越权） */
    public boolean insideShare(Long nodeId, Long rootId) {
        Long cur = nodeId;
        int guard = 0;
        while (cur != null && cur > 0 && guard++ < 1000) {
            if (cur.equals(rootId)) return true;
            FileMeta f = fileMapper.selectById(cur);
            if (f == null) break;
            cur = f.getParentId();
        }
        return false;
    }

    private static String fmt(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }
}
