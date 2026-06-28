package com.bluecode.tui;

import com.bluecode.agent.Event;
import com.bluecode.tui.tea.Message;

public record AgentEventMessage(Event event) implements Message {
}
