package com.bluecode.skill;

import java.nio.file.Path;

public record InstallReport(String skillName, Path destination, int fileCount, long totalBytes) {
}
