package com.liteark.service;

import com.liteark.common.BizException;
import com.liteark.config.LiteArkProperties;
import com.liteark.entity.SystemConfig;
import com.liteark.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Office 只读预览：优先使用服务端 LibreOffice headless 转 PDF（需求 §4.5）。
 * 未安装 LibreOffice 或配置关闭时接口返回明确提示，前端降级为“下载查看”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfficePreviewService {
    private static final List<String> OFFICE_EXT = Arrays.asList("doc", "docx", "xls", "xlsx", "ppt", "pptx");
    private static final String[] BINARIES = {
            "soffice", "libreoffice",
            "/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice",
            "/Applications/LibreOffice.app/Contents/MacOS/soffice"
    };

    private final LiteArkProperties props;
    private final SystemConfigMapper configMapper;

    public static boolean isOffice(String ext) {
        return ext != null && OFFICE_EXT.contains(ext.toLowerCase());
    }

    public boolean enabled() {
        SystemConfig c = configMapper.selectById("office_preview_enabled");
        return c == null || !"false".equalsIgnoreCase(c.getConfigValue());
    }

    public String binary() {
        for (String b : BINARIES) {
            try {
                Process p = new ProcessBuilder(b, "--version").redirectErrorStream(true).start();
                drain(p.getInputStream());
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return b;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public Path convert(Path source, String cacheKey) {
        if (!enabled()) throw new BizException(503, "Office 在线预览已在系统配置中关闭，请下载后查看");
        String bin = binary();
        if (bin == null) throw new BizException(503, "服务器未安装 LibreOffice，暂不支持 Office 在线预览，请下载后查看");
        String key = cacheKey.replaceAll("[^a-zA-Z0-9]", "_");
        Path outDir = Paths.get(props.getTempDir(), "office", key).normalize();
        Path cached = findPdf(outDir);
        if (cached != null) return cached;
        try {
            Files.createDirectories(outDir);
            Process p = new ProcessBuilder(bin, "--headless", "--norestore", "--convert-to", "pdf",
                    "--outdir", outDir.toString(), source.toString())
                    .redirectErrorStream(true).start();
            drain(p.getInputStream());
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new BizException(504, "Office 转码超时，请稍后重试或下载查看");
            }
            Path pdf = findPdf(outDir);
            if (pdf == null) throw new BizException(500, "Office 转码失败，请下载查看");
            return pdf;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException("Office 转码失败: " + e.getMessage()); }
    }

    private Path findPdf(Path dir) {
        if (!Files.exists(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().toLowerCase().endsWith(".pdf")).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private void drain(InputStream in) {
        new Thread(() -> {
            try { byte[] buf = new byte[4096]; while (in.read(buf) != -1) {} } catch (Exception ignored) {}
        }).start();
    }
}
