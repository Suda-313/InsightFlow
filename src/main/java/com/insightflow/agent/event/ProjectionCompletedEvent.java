package com.insightflow.agent.event;

import org.springframework.context.ApplicationEvent;

public class ProjectionCompletedEvent extends ApplicationEvent {

    private final String projectionId;
    private final String workspaceId;

    public ProjectionCompletedEvent(Object source, String projectionId, String workspaceId) {
        super(source);
        this.projectionId = projectionId;
        this.workspaceId = workspaceId;
    }

    public String getProjectionId() {
        return projectionId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }
}