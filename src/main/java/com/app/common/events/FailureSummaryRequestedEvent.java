package com.app.common.events;

import java.util.List;

/**
 * Requests a queued failure-summary dialog from the active layout. Centralizing
 * display prevents sync/export summaries from opening on top of each other.
 */
public class FailureSummaryRequestedEvent {

    private final String title;
    private final String header;
    private final String content;
    private final String firstColumnName;
    private final String secondColumnName;
    private final List<FailureSummaryRow> rows;
    private final Runnable primaryAction;

    public FailureSummaryRequestedEvent(String title, String header, String content,
            String firstColumnName, String secondColumnName,
            List<FailureSummaryRow> rows, Runnable primaryAction) {
        this.title = title;
        this.header = header;
        this.content = content;
        this.firstColumnName = firstColumnName;
        this.secondColumnName = secondColumnName;
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        this.primaryAction = primaryAction;
    }

    public String getTitle() {
        return title;
    }

    public String getHeader() {
        return header;
    }

    public String getContent() {
        return content;
    }

    public String getFirstColumnName() {
        return firstColumnName;
    }

    public String getSecondColumnName() {
        return secondColumnName;
    }

    public List<FailureSummaryRow> getRows() {
        return rows;
    }

    public Runnable getPrimaryAction() {
        return primaryAction;
    }

    /**
     * JavaBean-style row model for JavaFX table cell value factories.
     */
    public record FailureSummaryRow(String fileName, String reason) {
        public String getFileName() {
            return fileName;
        }

        public String getReason() {
            return reason;
        }
    }
}
