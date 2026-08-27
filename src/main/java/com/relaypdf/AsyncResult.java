package com.relaypdf;

public record AsyncResult(
    String kind,
    String id,
    String status,
    String pollUrl
) implements GenerateResult {}
