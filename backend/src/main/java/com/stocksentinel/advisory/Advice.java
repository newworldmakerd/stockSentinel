package com.stocksentinel.advisory;

import java.util.List;

public record Advice(String suggestion, List<String> reasons, List<String> risks, String invalidation) {
    public Advice {
        if (suggestion == null || suggestion.isBlank() || suggestion.length() > 2000
                || invalidation == null || invalidation.isBlank() || invalidation.length() > 2000
                || reasons == null || reasons.isEmpty() || risks == null || risks.isEmpty()
                || reasons.size() > 20 || risks.size() > 20) {
            throw new IllegalArgumentException("Invalid advisory content");
        }
        reasons = List.copyOf(reasons);
        risks = List.copyOf(risks);
        if (java.util.stream.Stream.concat(reasons.stream(), risks.stream())
                .anyMatch(value -> value.isBlank() || value.length() > 2000)) {
            throw new IllegalArgumentException("Invalid advisory explanation");
        }
    }
}