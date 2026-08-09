package com.heartpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.app.RelationshipAiClient;
import com.heartpilot.domain.AiMessage;
import com.heartpilot.domain.EmotionReport;
import com.heartpilot.domain.GeneratedFile;
import com.heartpilot.domain.RelationshipProfile;
import com.heartpilot.repository.ConversationRepository;
import com.heartpilot.repository.GeneratedFileRepository;
import com.heartpilot.repository.MessageRepository;
import com.heartpilot.repository.ProfileRepository;
import com.heartpilot.repository.ReportRepository;
import com.heartpilot.web.ApiException;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ReportRepository reports;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final GeneratedFileRepository files;
    private final StorageService storage;
    private final RelationshipAiClient ai;
    private final ObjectMapper json;
    private final ProfileRepository profiles;

    public ReportService(
            ReportRepository reports,
            ConversationRepository conversations,
            MessageRepository messages,
            GeneratedFileRepository files,
            StorageService storage,
            RelationshipAiClient ai,
            ObjectMapper json,
            ProfileRepository profiles) {
        this.reports = reports;
        this.conversations = conversations;
        this.messages = messages;
        this.files = files;
        this.storage = storage;
        this.ai = ai;
        this.json = json;
        this.profiles = profiles;
    }

    public Page<EmotionReport> list(Long userId, Pageable pageable) {
        return reports.findByUserId(userId, pageable);
    }

    public EmotionReport get(Long id, Long userId) {
        return reports.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("报告不存在"));
    }

    @Transactional
    public void delete(Long id, Long userId) {
        EmotionReport report = get(id, userId);
        for (GeneratedFile file :
                files.findByUserIdAndBusinessTypeAndBusinessId(userId, "EMOTION_REPORT", id)) {
            try {
                storage.delete(file.getStorageKey());
            } catch (Exception ignored) {
            }
            files.delete(file);
        }
        reports.delete(report);
    }

    @Transactional
    public EmotionReport generate(Long conversationId, Long userId) {
        conversations
                .findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        List<AiMessage> history =
                messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(conversationId, userId);
        if (history.isEmpty()) throw ApiException.badRequest("会话暂无内容");
        String conversationContext =
                history.stream()
                        .map(
                                message ->
                                        ("USER".equals(message.getRole()) ? "用户：" : "顾问：")
                                                + message.getContent())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
        String context =
                "关系档案："
                        + profiles.findByUserId(userId).map(this::profileSummary).orElse("未完善")
                        + "\n请结合档案判断关系阶段、沟通方式、长期关注、偏好和边界，但不得虚构。\n会话：\n"
                        + conversationContext;
        RelationshipAiClient.RelationshipAnalysis analysis = ai.analyze(context);

        EmotionReport report = new EmotionReport();
        report.setUserId(userId);
        report.setConversationId(conversationId);
        report.setTitle(value(analysis.title(), "关系分析报告"));
        report.setProblemSummary(analysis.problemSummary());
        report.setRelationshipStatus(analysis.relationshipStatus());
        report.setConflictType(analysis.conflictType());
        report.setRiskLevel(value(analysis.riskLevel(), "低"));
        report.setAnalysis(analysis.analysis());
        try {
            report.setActionsJson(
                    json.writeValueAsString(
                            analysis.actions() == null ? List.of() : analysis.actions()));
        } catch (Exception e) {
            report.setActionsJson("[]");
        }

        // 复盘时间是产品规则，不再信任模型生成的日期：以最后一次咨询消息时间为准加 1 天。
        Instant consultationTime = history.get(history.size() - 1).getCreatedAt();
        if (consultationTime == null) consultationTime = Instant.now();
        report.setReviewAt(consultationTime.plus(1, ChronoUnit.DAYS));
        return reports.save(report);
    }

    @Transactional
    public GeneratedFile exportPdf(Long reportId, Long userId) {
        EmotionReport report = get(reportId, userId);
        boolean reviewTimeCorrected = correctReviewTime(report, userId);
        if (report.getGeneratedFileId() != null && !reviewTimeCorrected) {
            return files.findByIdAndUserId(report.getGeneratedFileId(), userId).orElseThrow();
        }
        try {
            byte[] bytes = pdf(report);
            var stored =
                    storage.store(
                            bytes,
                            "relationship-report-" + report.getId() + ".pdf",
                            "application/pdf",
                            "reports");
            GeneratedFile file = new GeneratedFile();
            file.setUserId(userId);
            file.setFileName(stored.fileName());
            file.setContentType(stored.contentType());
            file.setStorageKey(stored.key());
            file.setSizeBytes(stored.size());
            file.setBusinessType("EMOTION_REPORT");
            file.setBusinessId(report.getId());
            files.save(file);
            report.setGeneratedFileId(file.getId());
            reports.save(report);
            return file;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF_FAILED", "PDF 生成失败");
        }
    }

    private boolean correctReviewTime(EmotionReport report, Long userId) {
        if (report.getConversationId() == null) return false;
        List<AiMessage> history =
                messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(
                        report.getConversationId(), userId);
        if (history.isEmpty()) return false;
        Instant consultationTime = history.get(history.size() - 1).getCreatedAt();
        if (consultationTime == null) return false;
        Instant expected = consultationTime.plus(1, ChronoUnit.DAYS);
        if (Objects.equals(report.getReviewAt(), expected)) return false;
        report.setReviewAt(expected);
        reports.save(report);
        return true;
    }

    private byte[] pdf(EmotionReport report) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter writer = new PdfWriter(out);
                PdfDocument pdf = new PdfDocument(writer);
                Document doc = new Document(pdf)) {
            PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
            doc.setFont(font);
            doc.add(new Paragraph(pdfSafe(font, report.getTitle())).setFontSize(22));
            doc.add(new Paragraph("生成时间：" + format(report.getCreatedAt())));
            doc.add(new Paragraph("问题概述").setFontSize(16));
            doc.add(new Paragraph(pdfSafe(font, value(report.getProblemSummary(), "暂无"))));
            doc.add(
                    new Paragraph(
                            pdfSafe(
                                    font,
                                    "关系状态："
                                            + value(report.getRelationshipStatus(), "未设置")
                                            + "    冲突类型："
                                            + value(report.getConflictType(), "待分析"))));
            doc.add(new Paragraph(pdfSafe(font, "风险等级：" + report.getRiskLevel())));
            doc.add(new Paragraph("AI 分析").setFontSize(16));
            doc.add(new Paragraph(pdfSafe(font, value(report.getAnalysis(), "暂无"))));
            doc.add(new Paragraph("推荐行动").setFontSize(16));
            List<?> actions = json.readValue(value(report.getActionsJson(), "[]"), List.class);
            int index = 1;
            for (Object action : actions)
                doc.add(new Paragraph(pdfSafe(font, index++ + ". " + action)));
            doc.add(new Paragraph("建议复盘时间：" + format(report.getReviewAt())));
            doc.add(new Paragraph("本报告由 HeartPilot AI 辅助生成，不替代医疗、心理或法律专业意见。").setFontSize(9));
        }
        return out.toByteArray();
    }

    private String format(Instant instant) {
        return instant == null ? "—" : DATE_TIME.format(instant.atZone(CHINA));
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String profileSummary(RelationshipProfile profile) {
        return "状态="
                + value(profile.getRelationshipStatus(), "未设置")
                + "；时长="
                + (profile.getRelationshipMonths() == null
                        ? "未填写"
                        : profile.getRelationshipMonths() + "个月")
                + "；沟通方式="
                + value(profile.getCommunicationStyle(), "未填写")
                + "；关注="
                + value(profile.getConcerns(), "未填写")
                + "；偏好="
                + value(profile.getPreferences(), "未填写")
                + "；边界="
                + value(profile.getBoundaries(), "未填写");
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
