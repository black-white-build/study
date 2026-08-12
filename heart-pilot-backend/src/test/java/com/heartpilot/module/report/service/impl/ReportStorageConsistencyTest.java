package com.heartpilot.module.report.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.infrastructure.ai.RelationshipAiClient;
import com.heartpilot.module.conversation.repository.ConversationRepository;
import com.heartpilot.module.conversation.repository.MessageRepository;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.file.repository.GeneratedFileRepository;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.module.report.entity.EmotionReport;
import com.heartpilot.module.report.repository.ReportRepository;
import com.heartpilot.module.user.repository.ProfileRepository;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportStorageConsistencyTest {
    @Test
    void failedObjectUploadDoesNotPersistGeneratedFileReference() throws Exception {
        ReportRepository reports = mock(ReportRepository.class);
        GeneratedFileRepository files = mock(GeneratedFileRepository.class);
        StorageService storage = mock(StorageService.class);
        EmotionReport report = new EmotionReport();
        report.setId(41L);
        report.setUserId(7L);
        report.setTitle("脱敏测试报告");
        report.setActionsJson("[]");
        when(reports.findByIdAndUserId(41L, 7L)).thenReturn(Optional.of(report));
        when(storage.store(any(byte[].class), any(), any(), any()))
                .thenThrow(new IOException("simulated storage outage"));

        ReportServiceImpl service =
                new ReportServiceImpl(
                        reports,
                        mock(ConversationRepository.class),
                        mock(MessageRepository.class),
                        files,
                        storage,
                        mock(RelationshipAiClient.class),
                        new ObjectMapper(),
                        mock(ProfileRepository.class));

        assertThrows(ApiException.class, () -> service.exportPdf(41L, 7L));
        verify(files, never()).save(any(GeneratedFile.class));
        verify(reports, never()).save(report);
    }
}
