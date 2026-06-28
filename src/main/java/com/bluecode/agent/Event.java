package com.bluecode.agent;

public sealed interface Event permits Event.Text, Event.Tool, Event.UsageReport, Event.Iter, Event.Notice,
        Event.Approval, Event.Done, Event.Failed, CompactEvent {
    record Text(String delta) implements Event {
    }

    record Tool(ToolEvent event) implements Event {
    }

    record UsageReport(Usage usage) implements Event {
    }

    record Iter(int value) implements Event {
    }

    record Notice(String message) implements Event {
    }

    record Approval(ApprovalRequest request) implements Event {
    }

    record Done() implements Event {
    }

    record Failed(String message) implements Event {
    }
}
