package com.relaypdf;

public record UrlResult(
    String kind,
    String id,
    String status,
    String url,
    String filename,
    int sizeBytes,
    String expiresAt
) implements GenerateResult {}
