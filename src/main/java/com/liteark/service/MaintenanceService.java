package com.liteark.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liteark.entity.FileMeta;
import com.liteark.entity.SystemConfig;
import com.liteark.entity.UploadTask;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.SystemConfigMapper;
import com.liteark.mapper.UploadTaskMapper;
import com.liteark.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/** 定时维护：回收站过期清理、过期分片清理、过期共享失效、日志保留期清理（需求 §5.4 / §10 Q-10/Q-13） */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {
    private final JdbcTemplate jdbcTemplate;
    private final FileMetaMapper fileMapper;
    private final UploadTaskMapper taskMapper;
    private final SystemConfigMapper configMapper;
    private final FileUtil fileUtil;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Scheduled(initialDelay = 60000L, fixedDelay = 3600000L)
    public void cleanAll() {
        try { cleanRecycle(); } catch (Exception e) { log.warn("回收站清理失败", e); }
        try { cleanUploads(); } catch (Exception e) { log.warn("分片清理失败", e); }
        try { expireShares(); } catch (Exception e) { log.warn("共享过期处理失败", e); }
        try { cleanLogs(); } catch (Exception e) { log.warn("日志清理失败", e); }
    }

    private int cfg(String key, int def) {
        SystemConfig c = configMapper.selectById(key);
        try { return c == null ? def : Integer.parseInt(c.getConfigValue()); }
        catch (Exception e) { return def; }
    }

    private String cutoff(int days) {
        return FMT.format(new Date(System.currentTimeMillis() - Math.max(1, days) * 86400000L));
    }

    public int cleanRecycle() {
        int days = cfg("recycle_retention_days", 30);
        String limit = cutoff(days);
        List<FileMeta> expired = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getIsDeleted, 1).le(FileMeta::getDeletedAt, limit));
        int count = 0;
        Map<Long, Long> freedByOwner = new LinkedHashMap<>();
        for (FileMeta f : expired) {
            Set<Long> ids = new LinkedHashSet<>();
            collect(f.getId(), f.getOwnerId(), ids, 0);
            for (Long id : ids) {
                FileMeta item = fileMapper.selectById(id);
                if (item == null) continue;
                if (item.getIsDir() == 0 && item.getStorageKey() != null) {
                    try { Files.deleteIfExists(fileUtil.storagePath(item.getOwnerId(), item.getStorageKey())); }
                    catch (Exception ignored) {}
                }
                if (item.getIsDir() == 0 && item.getSize() != null) {
                    freedByOwner.merge(item.getOwnerId(), item.getSize(), Long::sum);
                }
                jdbcTemplate.update("DELETE FROM doc_version WHERE file_id = ?", id);
                jdbcTemplate.update("DELETE FROM share WHERE file_id = ?", id);
                jdbcTemplate.update("DELETE FROM permission WHERE folder_id = ?", id);
                fileMapper.deleteById(id);
                count++;
            }
        }
        // 过期清理同样要回收配额，否则用户空间只会越用越满
        for (Map.Entry<Long, Long> e : freedByOwner.entrySet()) {
            jdbcTemplate.update("UPDATE user SET used = MAX(0, COALESCE(used, 0) - ?) WHERE id = ?", e.getValue(), e.getKey());
        }
        if (count > 0) log.info("回收站自动清理 {} 项（保留 {} 天）", count, days);
        return count;
    }

    private void collect(Long id, Long ownerId, Set<Long> acc, int guard) {
        if (id == null || guard > 500 || !acc.add(id)) return;
        List<FileMeta> children = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getParentId, id).eq(FileMeta::getOwnerId, ownerId));
        for (FileMeta c : children) collect(c.getId(), ownerId, acc, guard + 1);
    }

    public int cleanUploads() {
        List<UploadTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<UploadTask>()
                .isNotNull(UploadTask::getExpireAt).le(UploadTask::getExpireAt, FMT.format(new Date())));
        int count = 0;
        for (UploadTask t : tasks) {
            Path dir = Paths.get(System.getProperty("user.dir"), "data", "temp", "chunks", t.getUploadId()).normalize();
            // 	data-dir 可能外置，优先通过 FileUtil 的 temp 目录推导
            try {
                String temp = resolveTempDir();
                dir = Paths.get(temp, "chunks", t.getUploadId()).normalize();
            } catch (Exception ignored) {}
            try {
                if (Files.exists(dir)) {
                    try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
                    }
                }
            } catch (Exception ignored) {}
            taskMapper.deleteById(t.getId());
            count++;
        }
        if (count > 0) log.info("过期分片任务清理 {} 个", count);
        return count;
    }

    private String resolveTempDir() {
        // 从 jdbc url 推导数据目录：jdbc:sqlite:<dir>/liteark.db
        String url = jdbcTemplate.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        if (url != null && url.contains("sqlite:")) {
            String db = url.substring(url.indexOf("sqlite:") + 7);
            return Paths.get(db).getParent().resolve("temp").toString();
        }
        return "./data/temp";
    }

    public int expireShares() {
        int n = jdbcTemplate.update("UPDATE share SET revoked = 1 WHERE revoked = 0 AND expires_at IS NOT NULL AND expires_at < ?",
                FMT.format(new Date()));
        if (n > 0) log.info("过期共享自动失效 {} 条", n);
        return n;
    }

    public int cleanLogs() {
        int days = cfg("log_retention_days", 180);
        int n = jdbcTemplate.update("DELETE FROM operation_log WHERE created_at < ?", cutoff(days));
        if (n > 0) log.info("操作日志清理 {} 条（保留 {} 天）", n, days);
        return n;
    }
}
