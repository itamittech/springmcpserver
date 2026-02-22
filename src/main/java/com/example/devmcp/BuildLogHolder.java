package com.example.devmcp;

import org.springframework.stereotype.Component;

/**
 * Shared in-memory store for the most recent build output.
 *
 * Written by: BuildTools.runBuildTool (after each build)
 * Read by:    ProjectResources.getBuildLog (@McpResource project://build-log)
 *
 * volatile ensures visibility across threads without synchronization overhead
 * (one writer, many readers pattern).
 */
@Component
public class BuildLogHolder {

    private volatile String lastBuildLog = "No build has been run yet.";

    public String getLastBuildLog() {
        return lastBuildLog;
    }

    public void setLastBuildLog(String log) {
        this.lastBuildLog = log;
    }
}
