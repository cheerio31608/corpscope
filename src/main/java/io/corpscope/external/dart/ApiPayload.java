package io.corpscope.external.dart;

/** Unmodified response JSON, with no request URL or API key. */
public record ApiPayload<T>(T data, String rawJson) {}
