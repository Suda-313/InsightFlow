package com.insightflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insightflow.security.JwtAuthenticationFilter;
import com.insightflow.security.JwtTokenService;
import com.insightflow.security.WorkspaceAccessInterceptor;
import com.insightflow.service.WorkspaceTopicPackService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TopicPackController.class)
@AutoConfigureMockMvc(addFilters = false)
class TopicPackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceTopicPackService topicPackService;

    @MockBean
    private WorkspaceAccessInterceptor workspaceAccessInterceptor;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void allowWorkspaceInterceptor() throws Exception {
        when(workspaceAccessInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void listPacksReturnsOk() throws Exception {
        when(topicPackService.listAvailablePacks()).thenReturn(List.of(
                new com.insightflow.service.analysis.TopicPackRegistry.TopicPackSummary(
                        "game-chaoziran", "game-chaoziran:v2", "游戏《超自然》舆情议题包")));

        mockMvc.perform(get("/api/v1/topic-packs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].packId").value("game-chaoziran"));
    }

    @Test
    void getWorkspacePackReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        when(topicPackService.getBinding(workspaceId)).thenReturn(
                new WorkspaceTopicPackService.TopicPackBinding(
                        "game-chaoziran", "game-chaoziran:v2", "游戏《超自然》舆情议题包", false, "game-chaoziran"));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/topic-pack", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId").value("game-chaoziran"))
                .andExpect(jsonPath("$.explicitlyBound").value(false));
    }

    @Test
    void setWorkspacePackReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        when(topicPackService.bindPack(workspaceId, "game-chaoziran")).thenReturn(
                new WorkspaceTopicPackService.TopicPackBinding(
                        "game-chaoziran", "game-chaoziran:v2", "游戏《超自然》舆情议题包", true, "game-chaoziran"));

        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/topic-pack", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"game-chaoziran\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explicitlyBound").value(true));
    }
}
