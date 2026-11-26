package com.liteark.util;

import com.liteark.common.BizException;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 统一的文件响应构造器。
 * 解决三类问题：
 * 1. 中文/特殊字符文件名：Content-Disposition 同时给出 ASCII 兜底名与 RFC 5987 的 UTF-8 名；
 * 2. 音视频/PDF 拖动进度：支持 HTTP Range（206）与 Accept-Ranges；
 * 3. 在线预览安全：nosniff；html/svg 内联预览加 CSP sandbox，避免同源脚本读取登录态。
 */
public final class FileResponses {

    private FileResponses() {}

    public static ResponseEntity<Resource> build(Path path, String name, boolean inline, HttpServletRequest req) {
        try {
            Resource res = new FileSystemResource(path);
            long length = res.contentLength();
            String contentType = resolveContentType(path, name);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition(name, inline));
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=0, must-revalidate");
            headers.set("X-Content-Type-Options", "nosniff");
            if (inline && isActiveContent(contentType)) {
                // 沙箱化：脚本无法执行、无法读取同源 localStorage，HTML/SVG 仍能正常渲染
                headers.set("Content-Security-Policy",
                        "sandbox; default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:");
            }

            String range = req == null ? null : req.getHeader(HttpHeaders.RANGE);
            if (range != null && !range.trim().isEmpty()) {
                try {
                    List<HttpRange> ranges = HttpRange.parseRanges(range);
                    if (!ranges.isEmpty()) {
                        HttpRange hr = ranges.get(0);
                        long start = hr.getRangeStart(length);
                        long end = hr.getRangeEnd(length);
                        long count = end - start + 1;
                        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
                        headers.setContentLength(count);
                        return new ResponseEntity<>(new RegionResource(path, start, count), headers, HttpStatus.PARTIAL_CONTENT);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Range 非法时按完整内容返回
                }
            }
            headers.setContentLength(length);
            return new ResponseEntity<>(res, headers, HttpStatus.OK);
        } catch (IOException e) {
            throw new BizException("读取文件失败: " + e.getMessage());
        }
    }

    /** 文件片段资源：只读取 [start, start+count) 区间，供 206 响应零拷贝输出 */
    private static final class RegionResource extends AbstractResource {
        private final Path path;
        private final long start;
        private final long count;

        RegionResource(Path path, long start, long count) {
            this.path = path;
            this.start = start;
            this.count = count;
        }

        @Override
        public String getDescription() {
            return "region[" + start + ",+" + count + "] of " + path;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream in = Files.newInputStream(path);
            long skipped = 0;
            while (skipped < start) {
                long s = in.skip(start - skipped);
                if (s <= 0) {
                    if (in.read() < 0) break;
                    s = 1;
                }
                skipped += s;
            }
            return new BoundedInputStream(in, count);
        }

        @Override
        public long contentLength() {
            return count;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }

    /** 中文名走 filename*，filename 只保留 ASCII 兜底，避免部分客户端解码乱码 */
    static String disposition(String name, boolean inline) {
        String type = inline ? "inline" : "attachment";
        String safe = name == null || name.isEmpty() ? "file" : name;
        String ascii = safe.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_").replace("\\\\", "_");
        if (ascii.replace("_", "").trim().isEmpty()) {
            String ext = FileUtil.ext(safe);
            ascii = ext.isEmpty() ? "file" : "file." + ext;
        }
        String enc;
        try {
            enc = URLEncoder.encode(safe, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            enc = ascii;
        }
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + enc;
    }

    static boolean isActiveContent(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase();
        return ct.startsWith("text/html") || ct.startsWith("image/svg") || ct.startsWith("application/xhtml");
    }

    public static String resolveContentType(Path path, String name) {
        String byExt = byExtension(name);
        if (byExt != null) return byExt;
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(probed)) return probed;
        } catch (Exception ignored) {
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /**
     * 按扩展名判定 MIME。优先于 probeContentType，因为部分 Linux 容器缺少 /etc/mime.types，
     * 会把 md/csv/webp/mp4 等识别成 octet-stream，导致浏览器下载而不是预览。
     */
    public static String byExtension(String name) {
        String ext = FileUtil.ext(name == null ? "" : name);
        switch (ext) {
            case "txt": case "log": return "text/plain;charset=UTF-8";
            case "md": case "markdown": return "text/markdown;charset=UTF-8";
            case "csv": return "text/csv;charset=UTF-8";
            case "tsv": return "text/tab-separated-values;charset=UTF-8";
            case "json": return "application/json;charset=UTF-8";
            case "xml": return "application/xml;charset=UTF-8";
            case "html": case "htm": return "text/html;charset=UTF-8";
            case "css": case "js": return "text/plain;charset=UTF-8";
            case "pdf": return "application/pdf";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "ico": return "image/x-icon";
            case "mp4": return "video/mp4";
            case "m4v": return "video/mp4";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "ogv": return "video/ogg";
            case "avi": return "video/x-msvideo";
            case "mkv": return "video/x-matroska";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "ogg": case "oga": return "audio/ogg";
            case "aac": return "audio/aac";
            case "zip": return "application/zip";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default: return null;
        }
    }
}
