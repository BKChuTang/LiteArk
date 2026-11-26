package com.liteark.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liteark.common.BizException;
import com.liteark.config.LiteArkProperties;
import com.liteark.entity.FileMeta;
import com.liteark.entity.SystemConfig;
import com.liteark.entity.UploadTask;
import com.liteark.entity.User;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.SystemConfigMapper;
import com.liteark.mapper.UploadTaskMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.util.FileUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/** 分片断点续传内核（需求 §3.2 / §4.4）：任务持久化、分片落盘、合并校验、配额与同名策略 */
@Service
@RequiredArgsConstructor
public class UploadTaskService {
    private final UploadTaskMapper taskMapper;
    private final FileMetaMapper fileMapper;
    private final UserMapper userMapper;
    private final SystemConfigMapper configMapper;
    private final FileUtil fileUtil;
    private final LiteArkProperties props;
    private final PermissionService permissionService;
    private final ThumbnailService thumbService;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public String configValue(String key) {
        SystemConfig c = configMapper.selectById(key);
        return c == null ? null : c.getConfigValue();
    }

    private String cfg(String key, String def) {
        SystemConfig c = configMapper.selectById(key);
        return c == null || c.getConfigValue() == null ? def : c.getConfigValue();
    }

    public int configuredChunkSize(String clientType) {
        return Integer.parseInt(cfg("Mobile".equalsIgnoreCase(clientType) ? "chunk_size_mobile" : "chunk_size_pc",
                "Mobile".equalsIgnoreCase(clientType) ? "2097152" : "8388608"));
    }

    /** 初始化或复用任务，返回已上传分片索引 */
    @Transactional
    public Map<String, Object> init(Long userId, Long parentId, String md5, String name,
                                    long totalSize, int chunkSize, int chunkTotal, String clientType) {
        Long pid0 = parentId == null ? 0L : parentId;
        permissionService.require(userId, pid0, "upload");
        // 写入他人授权目录时，文件归属目录所有者
        Long ownerId = permissionService.targetOwner(userId, pid0);
        if (name == null || name.trim().isEmpty()) throw new BizException("文件名不能为空");
        if (totalSize <= 0) throw new BizException("文件不能为空");
        long max = Long.parseLong(cfg("max_file_size", String.valueOf(props.getMaxFileSize())));
        if (totalSize > max) throw new BizException("文件超过单文件上限");
        if (chunkSize < 1024 * 1024 || chunkSize > 64 * 1024 * 1024) throw new BizException("分片大小需在 1MB–64MB 之间");
        int expected = (int) Math.ceil((double) totalSize / chunkSize);
        if (chunkTotal != expected) throw new BizException("分片数量与文件大小不匹配");

        User u = userMapper.selectById(ownerId);
        if (u != null && (u.getUsed() == null ? 0 : u.getUsed()) + totalSize > (u.getQuota() == null ? 0 : u.getQuota())) {
            throw new BizException("空间配额不足，请清理文件或联系管理员");
        }

        Long pid = pid0;
        String safeName = fileUtil.safeName(name);
        UploadTask existing = taskMapper.selectOne(new LambdaQueryWrapper<UploadTask>()
                .eq(UploadTask::getOwnerId, ownerId).eq(UploadTask::getFileMd5, md5)
                .eq(UploadTask::getFileName, safeName).eq(UploadTask::getStatus, "uploading")
                .last("limit 1"));
        if (existing != null) {
            boolean mismatch = !Objects.equals(existing.getParentId(), pid)
                    || existing.getChunkSize() != chunkSize || !Objects.equals(existing.getChunkTotal(), chunkTotal);
            if (!mismatch && !expired(existing)) {
                Map<String, Object> m = new HashMap<>();
                m.put("uploadId", existing.getUploadId());
                m.put("uploaded", parse(existing.getUploaded()));
                m.put("resumed", !parse(existing.getUploaded()).isEmpty());
                m.put("clientType", existing.getClientType());
                m.put("conflictStrategy", cfg("conflict_strategy", props.getConflictStrategy()));
                return m;
            }
            // 参数变更或过期：清理旧任务
            discard(existing);
        }

        UploadTask t = new UploadTask();
        t.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        t.setFileMd5(md5); t.setFileName(safeName); t.setTotalSize(totalSize);
        t.setChunkSize(chunkSize); t.setChunkTotal(chunkTotal);
        t.setParentId(pid);
        t.setUploaded("[]"); t.setOwnerId(ownerId);
        t.setClientType(clientType == null ? "PC" : clientType);
        t.setStatus("uploading");
        t.setExpireAt(FMT.format(new Date(System.currentTimeMillis() + 7 * 86400000L)));
        taskMapper.insert(t);
        Map<String, Object> m = new HashMap<>();
        m.put("uploadId", t.getUploadId());
        m.put("uploaded", Collections.emptyList());
        m.put("resumed", false);
        m.put("clientType", t.getClientType());
        m.put("conflictStrategy", cfg("conflict_strategy", props.getConflictStrategy()));
        return m;
    }

    /** 分片落盘，index 从 0 开始 */
    @Transactional
    public void chunk(Long userId, String uploadId, int index, MultipartFile part) {
        UploadTask t = taskOf(userId, uploadId);
        if ("done".equals(t.getStatus())) throw new BizException("该任务已完成");
        if (index < 0 || index >= t.getChunkTotal()) throw new BizException("分片序号越界");
        long expect = Math.min((long) t.getChunkSize(), t.getTotalSize() - (long) index * t.getChunkSize());
        if (part.getSize() != expect) throw new BizException("分片大小不正确，期望 " + expect + " 字节");
        try {
            Path dir = chunkDir(uploadId);
            Files.createDirectories(dir);
            Path target = dir.resolve(index + ".part");
            // 已上传过的分片直接跳过写入，保证重传幂等
            if (!Files.exists(target) || Files.size(target) != expect) part.transferTo(target.toFile());
            if (Files.size(target) != expect) throw new BizException("分片写入不完整");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("分片保存失败: " + e.getMessage());
        }
        List<Integer> uploaded = parse(t.getUploaded());
        if (!uploaded.contains(index)) {
            uploaded.add(index);
            Collections.sort(uploaded);
            t.setUploaded(toJson(uploaded));
            t.setUpdatedAt(FMT.format(new Date()));
            taskMapper.updateById(t);
        }
    }

    public Map<String, Object> status(Long userId, String uploadId) {
        UploadTask t = taskOf(userId, uploadId);
        Map<String, Object> m = new HashMap<>();
        m.put("uploadId", t.getUploadId());
        m.put("uploaded", parse(t.getUploaded()));
        m.put("chunkTotal", t.getChunkTotal());
        m.put("status", t.getStatus());
        m.put("fileName", t.getFileName());
        m.put("totalSize", t.getTotalSize());
        return m;
    }

    /** 合并分片、校验 MD5、落库并更新配额 */
    @Transactional
    public Map<String, Object> complete(Long userId, String uploadId) {
        UploadTask t = taskOf(userId, uploadId);
        Long ownerId = t.getOwnerId() == null ? userId : t.getOwnerId();
        if ("done".equals(t.getStatus())) throw new BizException("该任务已完成");
        List<Integer> uploaded = parse(t.getUploaded());
        if (uploaded.size() != t.getChunkTotal()) throw new BizException("还有 " + (t.getChunkTotal() - uploaded.size()) + " 个分片未上传");

        String strategy = cfg("conflict_strategy", props.getConflictStrategy());
        Long parentId = t.getParentId() == null ? 0L : t.getParentId();
        String name = t.getFileName();
        FileMeta existing = fileMapper.selectOne(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId).eq(FileMeta::getParentId, parentId)
                .eq(FileMeta::getName, name).eq(FileMeta::getIsDeleted, 0).last("limit 1"));
        if (existing != null && "skip".equalsIgnoreCase(strategy)) {
            finish(t);
            return Collections.singletonMap("skipped", true);
        }
        if (existing != null && "rename".equalsIgnoreCase(strategy)) name = uniqueName(ownerId, parentId, name);
        if (existing != null && "overwrite".equalsIgnoreCase(strategy)) {
            if (existing.getStorageKey() != null) {
                try { Files.deleteIfExists(fileUtil.storagePath(ownerId, existing.getStorageKey())); } catch (Exception ignored) {}
            }
            fileMapper.deleteById(existing.getId());
        }

        String ext = FileUtil.ext(name);
        String key = fileUtil.randomKey(ext);
        Path target = fileUtil.storagePath(ownerId, key);
        try {
            Files.createDirectories(target.getParent());
            merge(t, target);
            String md5;
            try (InputStream in = Files.newInputStream(target)) { md5 = DigestUtils.md5Hex(in); }
            if (!md5.equalsIgnoreCase(t.getFileMd5())) {
                Files.deleteIfExists(target);
                throw new BizException("合并校验失败，文件 MD5 不一致，请重试未完成分片");
            }
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException("分片合并失败: " + e.getMessage()); }

        FileMeta meta = new FileMeta();
        meta.setOwnerId(ownerId); meta.setParentId(parentId); meta.setName(name);
        meta.setIsDir(0); meta.setExt(ext); meta.setSize(t.getTotalSize());
        meta.setStorageKey(key); meta.setMd5(t.getFileMd5());
        meta.setIsDeleted(0); meta.setVersion(1);
        fileMapper.insert(meta);
        try {
            Path thumb = thumbService.get(ownerId, meta, 200);
            if (thumb != null) {
                meta.setThumbnailPath(Paths.get(props.getDataDir()).relativize(thumb).toString());
                fileMapper.updateById(meta);
            }
        } catch (Exception ignored) {}

        User u = userMapper.selectById(ownerId);
        if (u != null) {
            u.setUsed((u.getUsed() == null ? 0 : u.getUsed()) + t.getTotalSize());
            userMapper.updateById(u);
        }
        finish(t);
        Map<String, Object> m = new HashMap<>();
        m.put("id", meta.getId()); m.put("name", name); m.put("size", t.getTotalSize()); m.put("skipped", false);
        return m;
    }

    @Transactional
    public void cancel(Long userId, String uploadId) {
        UploadTask t = taskOf(userId, uploadId);
        finish(t);
    }

    private void merge(UploadTask t, Path target) throws Exception {
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < t.getChunkTotal(); i++) {
                Path part = chunkDir(t.getUploadId()).resolve(i + ".part");
                if (!Files.exists(part)) throw new BizException("缺少分片 " + i);
                try (RandomAccessFile raf = new RandomAccessFile(part.toFile(), "r"); FileChannel in = raf.getChannel()) {
                    long pos = out.position();
                    long transferred = 0;
                    while (transferred < in.size()) {
                        long n = in.transferTo(transferred, in.size() - transferred, out);
                        if (n <= 0) break;
                        transferred += n;
                    }
                    out.position(pos + transferred);
                }
            }
        }
    }

    private void finish(UploadTask t) {
        t.setStatus("done");
        t.setUpdatedAt(FMT.format(new Date()));
        taskMapper.updateById(t);
        try { deleteRecursively(chunkDir(t.getUploadId())); } catch (Exception ignored) {}
    }

    private void discard(UploadTask t) {
        taskMapper.deleteById(t.getId());
        try { deleteRecursively(chunkDir(t.getUploadId())); } catch (Exception ignored) {}
    }

    private Path chunkDir(String uploadId) {
        return Paths.get(props.getTempDir(), "chunks", uploadId).normalize();
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    /**
     * 取上传任务：任务可能由被授权用户发起、文件却归属目录所有者，
     * 因此按 uploadId 取任务后用“对目标目录的上传权限”校验访问者。
     */
    private UploadTask taskOf(Long userId, String uploadId) {
        UploadTask t = taskMapper.selectOne(new LambdaQueryWrapper<UploadTask>()
                .eq(UploadTask::getUploadId, uploadId).last("limit 1"));
        if (t == null) throw new BizException(404, "上传任务不存在");
        permissionService.require(userId, t.getParentId() == null ? 0L : t.getParentId(), "upload");
        return t;
    }

    private boolean expired(UploadTask t) {
        if (t.getExpireAt() == null) return false;
        try { return FMT.parse(t.getExpireAt()).getTime() < System.currentTimeMillis(); }
        catch (Exception e) { return false; }
    }

    private List<Integer> parse(String json) {
        List<Integer> list = new ArrayList<>();
        if (json == null || json.length() < 3) return list;
        String s = json.replaceAll("[\\[\\]\\s]", "");
        if (s.isEmpty()) return list;
        for (String p : s.split(",")) {
            try { list.add(Integer.parseInt(p)); } catch (Exception ignored) {}
        }
        Collections.sort(list);
        return list;
    }

    private String toJson(List<Integer> list) {
        return list.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private String uniqueName(Long ownerId, Long parentId, String name) {
        if (!exists(ownerId, parentId, name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String candidate = base + "(" + i + ")" + ext;
            if (!exists(ownerId, parentId, candidate)) return candidate;
        }
        return name + UUID.randomUUID();
    }

    private boolean exists(Long ownerId, Long parentId, String name) {
        return fileMapper.selectCount(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId).eq(FileMeta::getParentId, parentId)
                .eq(FileMeta::getName, name).eq(FileMeta::getIsDeleted, 0)) > 0;
    }
}
