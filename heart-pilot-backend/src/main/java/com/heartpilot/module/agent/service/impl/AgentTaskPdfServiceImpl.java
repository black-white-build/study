package com.heartpilot.module.agent.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.service.AgentTaskPdfService;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.file.repository.GeneratedFileRepository;
import com.heartpilot.module.file.service.StorageService;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import java.io.ByteArrayOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Agent 任务最终报告的 PDF 生成与管理服务。 基于已生成的最终报告正文，用 iText 渲染为 PDF 并上传到对象存储， 同时维护 GeneratedFile
 * 元数据；任务驳回重规划时使旧 PDF 失效。
 *
 * <p>可靠性设计要点： - 幂等：generate 先查是否已有该任务的 PDF，有则直接复用，不重复生成/上传 - invalidate
 * 删除旧文件时，存储删除失败不阻断数据库记录删除（孤儿文件后续可清理） - 中文字体使用 STSongStd-Light + UniGB-UCS2-H 编码；pdfSafe 过滤字体不支持的码点，
 * 避免 iText 因缺字形抛异常
 */
@Service
public class AgentTaskPdfServiceImpl implements AgentTaskPdfService {
    /** 已生成文件元数据 Repository */
    private final GeneratedFileRepository files;

    /** 对象存储服务，上传/删除 PDF 字节 */
    private final StorageService storage;

    /** 构造器注入文件 Repository 与存储服务。 */
    public AgentTaskPdfServiceImpl(GeneratedFileRepository files, StorageService storage) {
        this.files = files;
        this.storage = storage;
    }

    /**
     * 生成（或复用）任务的 PDF。 要求任务已有最终报告正文；若该任务已生成过 PDF 则直接返回最新一条，保证幂等。
     *
     * @param task 任务实体
     * @return 已生成文件元数据
     */
    @Override
    public GeneratedFile generate(AgentTask task) {
        if (task.getFinalResult() == null || task.getFinalResult().isBlank()) {
            throw ApiException.badRequest("最终方案尚未生成");
        }
        // 已有 PDF 直接复用，避免重复渲染与上传
        return files.findFirstByUserIdAndBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
                        task.getUserId(), "AGENT_TASK", task.getId())
                .orElseGet(() -> create(task));
    }

    /** 取任务最新的 PDF 文件元数据，没有则提示先生成。 */
    @Override
    public GeneratedFile get(Long userId, Long taskId) {
        return files.findFirstByUserIdAndBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
                        userId, "AGENT_TASK", taskId)
                .orElseThrow(() -> ApiException.badRequest("请先生成 PDF 文件"));
    }

    /** 使任务关联的全部 PDF 失效：删除存储对象 + 删除数据库记录。 在用户驳回重规划前调用，保证旧版本报告不会被下载。 */
    @Override
    public void invalidate(AgentTask task) {
        for (GeneratedFile file :
                files.findByUserIdAndBusinessTypeAndBusinessId(
                        task.getUserId(), "AGENT_TASK", task.getId())) {
            try {
                storage.delete(file.getStorageKey());
            } catch (Exception ignored) {
                // Database metadata must still be removed; orphan cleanup can retry storage
                // deletion.
            }
            files.delete(file);
        }
    }

    /**
     * 真正渲染 PDF 并上传存储。 标题 22 号、行动目标 11 号、正文按 Markdown # 层级映射字号（# 行 16 号）； 统一捕获异常转成业务异常
     * PDF_FAILED，避免底层堆栈外泄。
     */
    private GeneratedFile create(AgentTask task) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (PdfWriter writer = new PdfWriter(out);
                    PdfDocument pdf = new PdfDocument(writer);
                    Document document = new Document(pdf)) {
                // 注册中文字体，否则中文显示为空白
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setFont(font);
                document.add(new Paragraph(pdfSafe(font, task.getTitle())).setFontSize(22));
                document.add(
                        new Paragraph(pdfSafe(font, "行动目标：" + task.getObjective()))
                                .setFontSize(11));
                for (String line : task.getFinalResult().split("\\R")) {
                    if (line.isBlank()) continue;
                    // 去掉 Markdown 井号前缀后再写入 PDF
                    String clean = pdfSafe(font, line.replaceFirst("^#{1,6}\\s*", ""));
                    document.add(new Paragraph(clean).setFontSize(line.startsWith("#") ? 16 : 11));
                }
            }
            // 上传到对象存储，业务目录 plans
            StorageService.StoredObject stored =
                    storage.store(
                            out.toByteArray(),
                            "action-plan-" + task.getId() + ".pdf",
                            "application/pdf",
                            "plans");
            GeneratedFile file = new GeneratedFile();
            file.setUserId(task.getUserId());
            file.setFileName(stored.fileName());
            file.setContentType(stored.contentType());
            file.setStorageKey(stored.key());
            file.setSizeBytes(stored.size());
            file.setBusinessType("AGENT_TASK");
            file.setBusinessId(task.getId());
            return files.save(file);
        } catch (Exception exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "PDF_FAILED", "PDF 生成失败，请稍后重试");
        }
    }

    /**
     * 过滤掉字体不支持的 Unicode 码点。 STSongStd-Light 字形集有限，遇到 emoji/生僻字会导致 iText 报错， 这里逐个码点检查
     * containsGlyph，不支持的直接丢弃。
     */
    private String pdfSafe(PdfFont font, String value) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints()
                .filter(
                        codePoint ->
                                codePoint <= Character.MAX_VALUE && font.containsGlyph(codePoint))
                .forEach(safe::appendCodePoint);
        return safe.toString();
    }
}
