package com.relaypdf;

public sealed interface GenerateResult permits BinaryResult, UrlResult, AsyncResult {
  String kind();
  String id();
}
