package com.liteark.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "liteark")
public class LiteArkProperties {
    private String dataDir = "./data";
    private String filesDir = "./data/files";
    private String tempDir = "./data/temp";
    private String jwtSecret = "liteark-dev-secret-change-me-in-production";
    private long jwtExpireMs = 604800000L;
    private long maxFileSize = 2147483648L;
    private long defaultQuota = 21474836480L;
    private String conflictStrategy = "rename";
    private boolean adminIpWhitelistEnabled = false;
    private List<String> adminIpWhitelist = new ArrayList<>();

    /**
     * 目录配置统一转成绝对路径。
     * <p>默认配置为相对路径（./data），而 MultipartFile#transferTo 对相对路径的 File
     * 会按 Servlet 容器的 multipart 临时目录解析，导致上传落盘到
     * {tomcat-work}/data/files/... 并抛 FileNotFoundException。转绝对路径可避免该问题。
     */
    @PostConstruct
    public void normalizeDirs() {
        dataDir = absolute(dataDir);
        filesDir = absolute(filesDir);
        tempDir = absolute(tempDir);
    }

    private static String absolute(String p) {
        if (p == null || p.trim().isEmpty()) return p;
        Path path = Paths.get(p.trim());
        return (path.isAbsolute() ? path : path.toAbsolutePath()).normalize().toString();
    }
}
