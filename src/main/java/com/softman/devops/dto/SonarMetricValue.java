package com.softman.devops.dto;

public record SonarMetricValue(String metric, String value, boolean bestValue) {
}
