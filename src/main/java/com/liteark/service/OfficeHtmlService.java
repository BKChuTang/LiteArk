package com.liteark.service;

import com.liteark.common.BizException;
import com.liteark.config.LiteArkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Office 只读预览的兜底渲染：不依赖 LibreOffice，用 POI 直接把
 * docx / xlsx / pptx 转成静态 HTML（只读、无脚本），供浏览器 iframe 内联查看。
 * <p>定位：服务器装不了 / 不想装 LibreOffice 时保证"能看内容"；
 * 装了 LibreOffice 时优先走 {@link OfficePreviewService} 的 PDF 高保真转码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeHtmlService {

    /** 超过该大小不解析，避免大表/大图把堆打满 */
    private static final long MAX_PARSE_SIZE = 30L * 1024 * 1024;
    /** 单元格/段落抽取上限，超出给截断提示 */
    private static final int MAX_ROWS = 300;
    private static final int MAX_COLS = 40;
    private static final int MAX_SHEETS = 20;
    private static final int MAX_SLIDES = 200;
    /** 内嵌图片总预算（base64 后计入页面体积） */
    private static final long MAX_IMAGE_BUDGET = 6L * 1024 * 1024;
    /** 渲染结构版本：改动 HTML 结构时递增，避免命中旧缓存 */
    private static final String HTML_VER = "v4";

    private final LiteArkProperties props;

    /** 支持直接渲染的后缀 */
    public static boolean supported(String ext) {
        if (ext == null) return false;
        String e = ext.toLowerCase();
        return "docx".equals(e) || "xlsx".equals(e) || "pptx".equals(e);
    }

    /** 渲染并缓存为 html，返回缓存文件路径 */
    public Path render(Path source, String name, String ext, String cacheKey) {
        String base = (cacheKey == null || cacheKey.isEmpty() ? name : cacheKey).replaceAll("[^a-zA-Z0-9]", "_");
        String key = base + "_" + HTML_VER;
        Path dir = Paths.get(props.getTempDir(), "office-html").normalize();
        Path cached = dir.resolve(key + ".html");
        try {
            if (Files.exists(cached) && Files.size(cached) > 0) return cached;
            if (Files.size(source) > MAX_PARSE_SIZE) {
                throw new BizException(503, "文件较大（超过 30MB），在线解析预览不可用，请下载后查看");
            }
            Files.createDirectories(dir);
            String html;
            try (InputStream in = Files.newInputStream(source)) {
                html = renderHtml(in, ext);
            }
            Path tmp = dir.resolve(key + ".html.tmp");
            Files.write(tmp, html.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Office HTML 渲染失败: {}", e.toString());
            throw new BizException(503, "该文件无法在线解析预览，请下载后查看");
        }
    }

    private String renderHtml(InputStream in, String ext) throws Exception {
        String body;
        String e = ext.toLowerCase();
        switch (e) {
            case "docx": body = docx(in); break;
            case "xlsx": body = xlsx(in); break;
            case "pptx": body = pptx(in); break;
            default: throw new BizException(503, "不支持在线解析该格式，请下载后查看");
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>" + CSS + "</style></head><body><div class=\"la-doc\">" + body + "</div></body></html>";
    }

    // ---------------- docx ----------------

    private String docx(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            long[] budget = {MAX_IMAGE_BUDGET};
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph) sb.append(paragraph((XWPFParagraph) el, budget));
                else if (el instanceof XWPFTable) sb.append(table((XWPFTable) el));
            }
        }
        return sb.length() == 0 ? "<p class=\"la-empty\">文档没有可显示的文本内容</p>" : sb.toString();
    }

    private String paragraph(XWPFParagraph p, long[] budget) {
        StringBuilder inner = new StringBuilder();
        for (XWPFRun r : p.getRuns()) {
            String text = r.text();
            if (text != null && !text.isEmpty()) {
                String t = esc(text).replace("\n", "<br>");
                if (r.isBold()) t = "<strong>" + t + "</strong>";
                if (r.isItalic()) t = "<em>" + t + "</em>";
                inner.append(t);
            }
            List<XWPFPicture> pics = r.getEmbeddedPictures();
            if (pics != null) {
                for (XWPFPicture pic : pics) {
                    String img = imgTag(pic.getPictureData(), budget);
                    if (img != null) inner.append(img);
                }
            }
        }
        String text = inner.length() == 0 ? "" : inner.toString();
        if (text.trim().isEmpty()) return "";
        String style = "";
        int size = 0;
        try {
            if (!p.getRuns().isEmpty() && p.getRuns().get(0).getFontSizeAsDouble() != null) {
                size = (int) (p.getRuns().get(0).getFontSizeAsDouble() * 4.0 / 3.0);
            }
        } catch (Exception ignored) {
        }
        if (size > 0) style = " style=\"font-size:" + Math.min(Math.max(size, 10), 32) + "px\"";
        String align = "";
        if (p.getAlignment() != null) {
            switch (p.getAlignment()) {
                case CENTER: align = " la-center"; break;
                case RIGHT: align = " la-right"; break;
                case BOTH: align = " la-justify"; break;
                default: break;
            }
        }
        return "<p class=\"la-p" + align + "\"" + style + ">" + text + "</p>";
    }

    private String table(XWPFTable t) {
        StringBuilder sb = new StringBuilder("<table class=\"la-table\">");
        for (XWPFTableRow row : t.getRows()) {
            sb.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append("<td>").append(esc(cell.getText()).replace("\n", "<br>")).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    private String imgTag(XWPFPictureData data, long[] budget) {
        try {
            byte[] bytes = data.getData();
            if (bytes == null || bytes.length == 0 || budget[0] - bytes.length < 0) return null;
            budget[0] -= bytes.length;
            String ext = data.suggestFileExtension();
            String mime = "png".equalsIgnoreCase(ext) ? "image/png"
                    : ("gif".equalsIgnoreCase(ext) ? "image/gif" : "image/jpeg");
            return "<img class=\"la-img\" src=\"data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes) + "\">";
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- xlsx ----------------

    /**
     * 工作簿渲染成「工作表页签 + 面板」结构：
     * 多 workspace 时纵向平铺会让用户以为只有一个表（尤其第一个表很长时根本滚不到第二个），
     * 这里改成 Excel 式页签，点哪个看哪个。页签用 radio + label 纯 CSS 实现，不引入脚本。
     */
    private String xlsx(InputStream in) throws Exception {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(in)) {
            int total = wb.getNumberOfSheets();
            if (total <= 0) return "<p class=\"la-empty\">该文件没有可显示的工作表</p>";
            int n = Math.min(total, MAX_SHEETS);
            StringBuilder inputs = new StringBuilder();
            StringBuilder labels = new StringBuilder();
            StringBuilder panels = new StringBuilder();
            StringBuilder rules = new StringBuilder();
            for (int i = 0; i < n; i++) {
                Sheet sheet = wb.getSheetAt(i);
                String id = "la-sheet-" + i;
                String name = sheet.getSheetName() == null ? "工作表" + (i + 1) : sheet.getSheetName();
                inputs.append("<input type=\"radio\" name=\"la-sheet\" id=\"").append(id).append("\"")
                        .append(i == 0 ? " checked" : "").append(">");
                labels.append("<label for=\"").append(id).append("\" title=\"").append(esc(name)).append("\">")
                        .append(esc(name)).append("</label>");
                panels.append("<div class=\"la-panel la-panel-").append(i).append("\">")
                        .append(sheetTable(sheet, fmt)).append("</div>");
                rules.append("#").append(id).append(":checked~.la-tabs label[for=\"").append(id).append("\"]")
                        .append("{background:#0958d9;border-color:#0958d9;color:#fff}")
                        .append("#").append(id).append(":checked~.la-panels .la-panel-").append(i)
                        .append("{display:block}");
            }
            if (total > n) labels.append("<span class=\"la-tab-more\">共 ").append(total)
                    .append(" 个工作表，仅显示前 ").append(n).append(" 个</span>");
            return "<style>" + rules + "</style><div class=\"la-xls\">" + inputs
                    + "<div class=\"la-tabs\">" + labels + "</div>"
                    + "<div class=\"la-panels\">" + panels + "</div></div>";
        }
    }

    /** 单个工作表转表格（合并单元格用 colspan/rowspan 还原） */
    private String sheetTable(Sheet sheet, DataFormatter fmt) {
        int maxCol = 0, scanned = 0;
        for (Row row : sheet) {
            short last = row.getLastCellNum();
            if (last > maxCol) maxCol = last;
            if (++scanned >= MAX_ROWS) break;
        }
        int cols = Math.min(maxCol, MAX_COLS);
        if (cols <= 0) return "<p class=\"la-empty\">该工作表为空</p>";

        Map<Long, CellRangeAddress> starts = new HashMap<>();
        Set<Long> covered = new HashSet<>();
        collectMerged(sheet, starts, covered);

        StringBuilder body = new StringBuilder("<table class=\"la-table la-sheet\">");
        int rows = 0;
        boolean truncated = false;
        for (Row row : sheet) {
            if (rows >= MAX_ROWS) { truncated = true; break; }
            int r = row.getRowNum();
            StringBuilder tr = new StringBuilder("<tr>");
            boolean hasValue = false;
            for (int c = 0; c < cols; c++) {
                long key = cellKey(r, c);
                if (covered.contains(key)) continue;   // 合并区非起始单元格不输出
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String v = cell == null ? "" : fmt.formatCellValue(cell);
                if (v != null && !v.trim().isEmpty()) hasValue = true;
                String span = "";
                CellRangeAddress ra = starts.get(key);
                if (ra != null) {
                    int cs = Math.min(ra.getLastColumn(), cols - 1) - c + 1;
                    int rs = Math.min(ra.getLastRow(), r + (MAX_ROWS - rows) - 1) - r + 1;
                    if (cs > 1) span += " colspan=\"" + cs + "\"";
                    if (rs > 1) span += " rowspan=\"" + rs + "\"";
                }
                String tag = rows == 0 ? "th" : "td";
                // 单元格被 nowrap + 省略号截断时，悬停可看全文
                String title = v != null && v.length() > 40
                        ? " title=\"" + esc(v.replace("\n", " ")) + "\"" : "";
                tr.append("<").append(tag).append(span).append(title).append(">")
                        .append(esc(v).replace("\n", "<br>")).append("</").append(tag).append(">");
            }
            tr.append("</tr>");
            // Excel 里常有成片空行（格式带过来的），整行没内容就不占版面
            if (!hasValue) continue;
            body.append(tr);
            rows++;
        }
        body.append("</table>");
        if (rows == 0) return "<p class=\"la-empty\">该工作表为空</p>";
        if (truncated) body.append("<p class=\"la-tip\">表格较大，仅显示前 ").append(MAX_ROWS)
                .append(" 行，完整内容请下载查看</p>");
        return body.toString();
    }

    /** 收集合并区域：起点记 colspan/rowspan，其余单元格标记跳过 */
    private void collectMerged(Sheet sheet, Map<Long, CellRangeAddress> starts, Set<Long> covered) {
        try {
            for (CellRangeAddress ra : sheet.getMergedRegions()) {
                long area = (long) (ra.getLastRow() - ra.getFirstRow() + 1)
                        * (ra.getLastColumn() - ra.getFirstColumn() + 1);
                if (area > 20000) continue;   // 异常大的合并区直接按普通单元格渲染，避免展开占内存
                for (int r = ra.getFirstRow(); r <= ra.getLastRow(); r++) {
                    for (int c = ra.getFirstColumn(); c <= ra.getLastColumn(); c++) {
                        long k = cellKey(r, c);
                        if (r == ra.getFirstRow() && c == ra.getFirstColumn()) starts.put(k, ra);
                        else covered.add(k);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private static long cellKey(int r, int c) {
        return ((long) r << 20) | (c & 0xFFFFF);
    }

    // ---------------- pptx ----------------

    private String pptx(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ss = new XMLSlideShow(in)) {
            List<XSLFSlide> slides = ss.getSlides();
            int n = Math.min(slides.size(), MAX_SLIDES);
            for (int i = 0; i < n; i++) {
                XSLFSlide slide = slides.get(i);
                sb.append("<section class=\"la-slide\"><div class=\"la-slide-no\">第 ").append(i + 1).append(" 页</div>");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        for (XSLFTextParagraph para : ((XSLFTextShape) shape).getTextParagraphs()) {
                            String txt = textOf(para);
                            if (txt.trim().isEmpty()) continue;
                            String cls = para.getIndentLevel() > 0 ? "la-p la-indent" : "la-p";
                            sb.append("<p class=\"").append(cls).append("\">").append(txt).append("</p>");
                        }
                    } else if (shape instanceof XSLFTable) {
                        sb.append("<table class=\"la-table\">");
                        for (XSLFTableRow row : ((XSLFTable) shape).getRows()) {
                            sb.append("<tr>");
                            for (XSLFTableCell cell : row.getCells()) {
                                sb.append("<td>").append(esc(cell.getText()).replace("\n", "<br>")).append("</td>");
                            }
                            sb.append("</tr>");
                        }
                        sb.append("</table>");
                    } else if (shape instanceof XSLFPictureShape) {
                        sb.append("<div class=\"la-pic\">[图片]</div>");
                    }
                }
                sb.append("</section>");
            }
            if (n == 0) sb.append("<p class=\"la-empty\">演示文稿没有幻灯片</p>");
        }
        return sb.toString();
    }

    private String textOf(XSLFTextParagraph para) {
        StringBuilder inner = new StringBuilder();
        for (XSLFTextRun r : para.getTextRuns()) {
            String t = r.getRawText();
            if (t == null || t.isEmpty()) continue;
            String s = esc(t);
            if (r.isBold()) s = "<strong>" + s + "</strong>";
            if (r.isItalic()) s = "<em>" + s + "</em>";
            inner.append(s);
        }
        return inner.toString();
    }

    // ---------------- 公共 ----------------

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CSS =
            "html,body{margin:0;padding:0;background:#fff;}"
            + "body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;}"
            + ".la-doc{max-width:900px;margin:0 auto;padding:28px 32px 48px;font-size:15px;line-height:1.85;}"
            + ".la-p{margin:0 0 10px;white-space:pre-wrap;word-break:break-word;}"
            + ".la-center{text-align:center;}.la-right{text-align:right;}.la-justify{text-align:justify;}"
            + ".la-indent{padding-left:22px;}"
            + ".la-img{max-width:100%;height:auto;display:block;margin:8px 0;}"
            + ".la-table{border-collapse:collapse;width:100%;margin:12px 0;font-size:13.5px;}"
            + ".la-table th,.la-table td{border:1px solid #d0d7de;padding:6px 10px;text-align:left;vertical-align:top;word-break:break-word;}"
            + ".la-table th{background:#f5f7fa;font-weight:600;}"
            + ".la-table tr:nth-child(even) td{background:#fafbfc;}"
            + ".la-sheet th{position:sticky;top:0;z-index:1;}"
            // 表格按内容自适应宽度并允许横向滚动，否则列一多每列只剩一个字宽（竖排文字）
            + ".la-sheet{width:auto;min-width:100%;table-layout:auto;}"
            + ".la-sheet th,.la-sheet td{white-space:nowrap;max-width:420px;overflow:hidden;text-overflow:ellipsis;}"
            + ".la-h3{margin:22px 0 10px;font-size:15px;color:#0958d9;}"
            // 工作表页签（radio + label 纯 CSS，无脚本）
            + ".la-xls input{position:absolute;width:1px;height:1px;opacity:0;pointer-events:none;}"
            + ".la-tabs{display:flex;flex-wrap:wrap;gap:6px;align-items:center;border-bottom:1px solid #e3e8ef;padding:0 0 10px;margin:0 0 14px;}"
            + ".la-tabs label{max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:5px 12px;"
            + "border:1px solid #d0d7de;border-radius:6px;background:#f7f9fc;color:#3c4a5c;font-size:13px;cursor:pointer;}"
            + ".la-tabs label:hover{border-color:#0958d9;color:#0958d9;}"
            + ".la-tab-more{font-size:12px;color:#8a94a6;margin-left:4px;}"
            + ".la-panel{display:none;}"
            + ".la-panel .la-table{margin-top:0;}"
            + ".la-slide{border:1px solid #e3e8ef;border-radius:8px;padding:18px 22px;margin:0 0 16px;}"
            + ".la-slide-no{font-size:12px;color:#8a94a6;margin-bottom:8px;}"
            + ".la-pic{color:#8a94a6;font-size:13px;margin:6px 0;}"
            + ".la-tip{color:#b26a00;background:#fff7e6;border:1px solid #ffe0a3;padding:8px 12px;border-radius:6px;font-size:13px;}"
            + ".la-empty{color:#8a94a6;}";
}
