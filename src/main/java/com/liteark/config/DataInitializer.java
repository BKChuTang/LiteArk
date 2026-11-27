package com.liteark.config;

import com.liteark.entity.Role;
import com.liteark.entity.SystemConfig;
import com.liteark.entity.User;
import com.liteark.mapper.RoleMapper;
import com.liteark.mapper.SystemConfigMapper;
import com.liteark.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 首次启动自动建库、建目录、初始化角色/账号/配置 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final SystemConfigMapper configMapper;
    private final LiteArkProperties props;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1. 目录
        for (String dir : new String[]{props.getDataDir(), props.getFilesDir(), props.getTempDir()}) {
            Files.createDirectories(Paths.get(dir));
        }
        Path dbPath = Paths.get(props.getDataDir(), "liteark.db");
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("PRAGMA foreign_keys=ON");

        // 2. 建表（先逐行剥离 -- 注释，再按分号切分执行，避免注释与首条语句同段被整段跳过）
        ClassPathResource res = new ClassPathResource("schema.sql");
        try (InputStream in = res.getInputStream()) {
            String sql = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            StringBuilder clean = new StringBuilder();
            for (String line : sql.split("\\r?\\n")) {
                if (line.trim().startsWith("--")) continue;
                clean.append(line).append('\n');
            }
            for (String stmt : clean.toString().split(";")) {
                String s = stmt.trim();
                if (!s.isEmpty()) {
                    jdbcTemplate.execute(s);
                }
            }
        }
        // 2.1 增量迁移（兼容已有 V1.1 核心版数据库；列已存在时忽略错误）
        migrate("ALTER TABLE share ADD COLUMN max_downloads INTEGER DEFAULT 0");
        migrate("ALTER TABLE share ADD COLUMN download_count INTEGER DEFAULT 0");
        migrate("ALTER TABLE file_meta ADD COLUMN thumbnail_path TEXT DEFAULT ''");
        migrate("ALTER TABLE upload_task ADD COLUMN parent_id INTEGER DEFAULT 0");
        migrate("ALTER TABLE permission ADD COLUMN source_role_id INTEGER DEFAULT NULL");
        migrate("ALTER TABLE share ADD COLUMN share_type TEXT DEFAULT 'external'");
        migrate("ALTER TABLE share ADD COLUMN target_user_ids TEXT DEFAULT ''");
        migrate("ALTER TABLE user ADD COLUMN totp_allowed INTEGER DEFAULT 1");
        log.info("数据库初始化完成: {}", dbPath.toAbsolutePath());

        // 3. 角色
        if (roleMapper.selectCount(null) == 0) {
            Role admin = new Role();
            admin.setName("超级管理员");
            admin.setDescription("系统最高权限");
            admin.setIsSystem(1);
            roleMapper.insert(admin);
            Role user = new Role();
            user.setName("普通用户");
            user.setDescription("网盘普通使用者");
            user.setIsSystem(1);
            roleMapper.insert(user);
        }

        // 4. 默认账号
        if (userMapper.selectCount(null) == 0) {
            BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(enc.encode("admin123"));
            admin.setNickname("超级管理员");
            admin.setRoleId(1L);
            admin.setQuota(props.getDefaultQuota());
            admin.setUsed(0L);
            admin.setStatus(1);
            userMapper.insert(admin);

            User demo = new User();
            demo.setUsername("demo");
            demo.setPassword(enc.encode("123456"));
            demo.setNickname("演示用户");
            demo.setRoleId(2L);
            demo.setQuota(2L * 1024 * 1024 * 1024);
            demo.setUsed(0L);
            demo.setStatus(1);
            userMapper.insert(demo);
        }

        // 5. 系统配置默认值
        String[][] defaults = {
                {"chunk_size_pc", "8388608"},        // PC 分片 8MB
                {"chunk_size_mobile", "2097152"},    // 移动端分片 2MB
                {"max_file_size", String.valueOf(props.getMaxFileSize())},
                {"conflict_strategy", props.getConflictStrategy()},
                {"default_share_expire", "7"},
                {"site_name", "轻舟 LiteArk"},
                {"default_quota", String.valueOf(props.getDefaultQuota())},
                {"upload_concurrency_pc", "4"},   // PC 上传并发
                {"upload_concurrency_mobile", "2"}, // 移动端上传并发
                {"recycle_retention_days", "30"}, // 回收站保留天数（需求 §10 Q-10）
                {"log_retention_days", "180"},    // 日志保留天数（需求 §10 Q-13）
                {"office_preview_enabled", "true"},// LibreOffice 转码预览开关
                {"online_edit_enabled", "true"},  // 在线编辑总开关
                {"edit_ext_whitelist", "txt,md,csv,json,html"}, // 可编辑后缀白名单
                {"totp_global_enabled", "false"},       // 二次验证总开关
                {"totp_max_attempts", "5"},             // 验证码连续错误阈值
                {"totp_lock_minutes", "30"},            // 锁定时长（分钟）
                {"totp_window", "1"},                   // 时间容错窗口（前后各 N 个 30 秒步长）
                {"totp_recovery_count", "10"},          // 每次生成的恢复码数量
                {"totp_disable_policy", "A"},           // 管理员禁用后的处置策略：A 保留绑定 / B 解除绑定
                {"trusted_device_ttl_days", "30"},      // 信任设备有效时长（天）
        };
        for (String[] kv : defaults) {
            if (configMapper.selectById(kv[0]) == null) {
                SystemConfig c = new SystemConfig();
                c.setConfigKey(kv[0]);
                c.setConfigValue(kv[1]);
                configMapper.insert(c);
            }
        }
        log.info("初始账号与配置就绪（admin/admin123, demo/123456）");
    }

    private void migrate(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // 列/索引已存在属于正常情况，不阻断启动
            log.debug("迁移跳过: {} ({})", sql, e.getMessage());
        }
    }
}
