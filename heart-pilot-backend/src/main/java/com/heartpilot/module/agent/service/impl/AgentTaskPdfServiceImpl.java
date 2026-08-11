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

@Service
public class AgentTaskPdfServiceImpl implements AgentTaskPdfService {
    private final GeneratedFileRepository files;
    private final StorageService storage;

    public AgentTaskPdfServiceImpl(GeneratedFileRepository files, StorageService storage) {
        this.files = files;
        this.storage = storage;
    }

    @Override
    public GeneratedFile generate(AgentTask task) {
        if (task.getFinalResult() == null || task.getFinalResult().isBlank()) {
            throw ApiException.badRequest("最终方案尚未生成");
        }
        return files.findFirstByUserIdAndBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
                        task.getUserId(), "AGENT_TASK", task.getId())
                .orElseGet(() -> create(task));
    }

    @Override
    public GeneratedFile get(Long userId, Long taskId) {
        return files.findFirstByUserIdAndBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
                        userId, "AGENT_TASK", taskId)
                .orElseThrow(() -> ApiException.badRequest("请先生成 PDF 文件"));
    }

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

    private GeneratedFile create(AgentTask task) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (PdfWriter writer = new PdfWriter(out);
                    PdfDocument pdf = new PdfDocument(writer);
                    Document document = new Document(pdf)) {
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setFont(font);
                document.add(new Paragraph(pdfSafe(font, task.getTitle())).setFontSize(22));
                document.add(
                        new Paragraph(pdfSafe(font, "行动目标：" + task.getObjective()))
                                .setFontSize(11));
                for (String line : task.getFinalResult().split("\\R")) {
                    if (line.isBlank()) continue;
                    String clean = pdfSafe(font, line.replaceFirst("^#{1,6}\\s*", ""));
                    document.add(new Paragraph(clean).setFontSize(line.startsWith("#") ? 16 : 11));
                }
            }
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
