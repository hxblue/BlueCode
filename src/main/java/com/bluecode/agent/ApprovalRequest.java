package com.bluecode.agent;

import com.bluecode.permission.Outcome;

import java.util.concurrent.BlockingQueue;

public record ApprovalRequest(String name, String args, String reason, BlockingQueue<Outcome> respond) {
}
