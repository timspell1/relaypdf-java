package com.relaypdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record BinaryResult(
    String kind,
    String id,
    String filename,
    int sizeBytes,
    String contentType,
    byte[] bytes
) implements GenerateResult {
  public void save(Path path) throws IOException {
    Files.write(path, bytes);
  }

  public void save(String path) throws IOException {
    save(Path.of(path));
  }
}
