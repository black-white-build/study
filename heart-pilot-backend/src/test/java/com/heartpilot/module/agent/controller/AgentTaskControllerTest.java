package com.heartpilot.module.agent.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heartpilot.module.agent.service.impl.AgentTaskService;
import com.heartpilot.module.agent.service.impl.RouteMapService;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.security.CurrentUser;
import org.junit.jupiter.api.Test;

class AgentTaskControllerTest {

    @Test
    void generationAndDownloadAreSeparateOperations() throws Exception {
        AgentTaskService service = mock(AgentTaskService.class);
        CurrentUser current = mock(CurrentUser.class);
        StorageService storage = mock(StorageService.class);
        AgentTaskController controller =
                new AgentTaskController(service, current, storage, mock(RouteMapService.class));
        GeneratedFile file = new GeneratedFile();
        file.setId(11L);
        file.setFileName("report.pdf");
        file.setContentType("application/pdf");
        file.setStorageKey("plans/report.pdf");
        when(current.id()).thenReturn(7L);
        when(service.generatePdf(3L, 7L)).thenReturn(file);
        when(service.getPdf(3L, 7L)).thenReturn(file);
        when(storage.read("plans/report.pdf")).thenReturn(new byte[] {1, 2, 3});

        assertEquals(11L, controller.generatePdf(3L).id());
        verify(service).generatePdf(3L, 7L);
        clearInvocations(service);

        assertArrayEquals(new byte[] {1, 2, 3}, controller.downloadPdf(3L).getBody());
        verify(service).getPdf(3L, 7L);
        verify(service, never()).generatePdf(3L, 7L);
    }
}
