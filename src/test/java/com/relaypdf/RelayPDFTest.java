package com.relaypdf;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RelayPDFTest {
  @Test
  void uploadsWithDefaultHttpTransport() throws Exception {
    var receivedLength = new AtomicReference<String>();
    var receivedBody = new AtomicReference<byte[]>();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/files", exchange -> {
      receivedLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
      receivedBody.set(exchange.getRequestBody().readAllBytes());
      byte[] response = "{\"id\":\"local-upload\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      try (var output = exchange.getResponseBody()) { output.write(response); }
    });
    server.start();
    try {
      var client = new RelayPDF("local-test", "http://127.0.0.1:" + server.getAddress().getPort());
      byte[] bytes = "%PDF-local-fixture".getBytes(StandardCharsets.UTF_8);
      assertEquals("local-upload", client.files.upload(bytes, "scan.pdf").get("id"));
      assertEquals(Integer.toString(bytes.length), receivedLength.get());
      assertArrayEquals(bytes, receivedBody.get());
    } finally { server.stop(0); }
  }

  @Test
  void htmlPdfBinary() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> ua = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    RelayPDF client = client((method, u, headers, payload) -> {
      url.set(u);
      auth.set(headers.get("Authorization"));
      ua.set(headers.get("User-Agent"));
      body.set(new String(payload, StandardCharsets.UTF_8));
      return pdf("%PDF-1.4", "hello.pdf", "pdf_test");
    });
    GenerateResult result = client.pdf.fromHtml("<h1>Hi</h1>", Map.of("filename", "hello.pdf"));
    assertEquals("https://api.relaypdf.com/v1/pdf", url.get());
    assertEquals("Bearer pdf_live_test", auth.get());
    assertTrue(ua.get().startsWith("relaypdf-java/"));
    assertTrue(body.get().contains("\"html\":\"<h1>Hi</h1>\""));
    BinaryResult binary = (BinaryResult) result;
    assertEquals("hello.pdf", binary.filename());
    assertEquals("%PDF-1.4", new String(binary.bytes(), StandardCharsets.UTF_8));
  }

  @Test
  void encodesFileBytes() throws Exception {
    AtomicReference<List<String>> bodies = new AtomicReference<>(List.of());
    java.util.ArrayList<String> captured = new java.util.ArrayList<>();
    RelayPDF client = client((method, url, headers, payload) -> {
      captured.add(new String(payload, StandardCharsets.UTF_8));
      return pdf("%PDF", "out.pdf", "pdf_1");
    });
    client.pdf.mergePdfs(List.of(
        Map.of("url", "https://example.com/a.pdf"),
        Map.of("file", "PDFB".getBytes(StandardCharsets.UTF_8))
    ), Map.of());
    client.convert.create(Map.of("file", "DOCX".getBytes(StandardCharsets.UTF_8), "sourceFilename", "letter.docx", "to", "pdf"));
    String b64a = Base64.getEncoder().encodeToString("PDFB".getBytes(StandardCharsets.UTF_8));
    String b64b = Base64.getEncoder().encodeToString("DOCX".getBytes(StandardCharsets.UTF_8));
    assertTrue(captured.get(0).contains(b64a));
    assertTrue(captured.get(1).contains(b64b));
  }

  @Test
  void rateLimitError() {
    RelayPDF client = client((method, url, headers, payload) ->
        json(429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"Slow down.\"}}", Map.of("retry-after", "10")));
    RelayPDFException err = assertThrows(RelayPDFException.class, () -> client.barcodes.qr("https://example.com"));
    assertEquals(429, err.getStatus());
    assertEquals("rate_limited", err.getCode());
    assertEquals(10, err.getRetryAfter());
  }

  @Test
  void account() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    RelayPDF client = client((method, u, headers, payload) -> {
      url.set(u);
      return json(200, "{\"plan\":\"free\",\"wallet\":{\"balanceMillicents\":500000}}", Map.of());
    });
    Map<String, Object> account = client.account();
    assertEquals("https://api.relaypdf.com/v1/account", url.get());
    @SuppressWarnings("unchecked") Map<String, Object> wallet = (Map<String, Object>) account.get("wallet");
    assertEquals(500000, ((Number) wallet.get("balanceMillicents")).intValue());
  }

  @Test
  void webhook() throws Exception {
    String secret = "whsec_test";
    String body = "{\"type\":\"job.completed\"}";
    long timestamp = 1_700_000_000L;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    String header = "t=" + timestamp + ",v1=" + hex;
    assertTrue(Webhook.verify(secret, body, header, Integer.MAX_VALUE));
    assertFalse(Webhook.verify("wrong", body, header, Integer.MAX_VALUE));
  }

  @Test
  void requiresKey() {
    assertThrows(IllegalArgumentException.class, () -> new RelayPDF(""));
  }

  @Test
  void processAndUpload() throws Exception {
    java.util.ArrayList<Map<String, String>> captured = new java.util.ArrayList<>();
    java.util.ArrayList<String> urls = new java.util.ArrayList<>();
    java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
    RelayPDF client = client((method, url, headers, payload) -> {
      urls.add(url);
      captured.add(headers);
      if (n.incrementAndGet() == 1) {
        return json(201, "{\"id\":\"upload_test\",\"filename\":\"scan.pdf\",\"sizeBytes\":3}", Map.of());
      }
      return json(202, "{\"id\":\"doc_test\",\"status\":\"processing\",\"pollUrl\":\"https://api.relaypdf.com/v1/jobs/doc_test\"}", Map.of());
    });
    Map<String, Object> uploaded = client.files.upload("abc".getBytes(StandardCharsets.UTF_8), "scan.pdf");
    GenerateResult job = client.process("ocr", Map.of("fileId", "upload_test", "response", "async"), Map.of("idempotencyKey", "invoice-1", "maxChargeMicrodollars", 40000));
    assertEquals("upload_test", uploaded.get("id"));
    assertEquals("https://api.relaypdf.com/v1/files", urls.get(0));
    assertEquals("scan.pdf", captured.get(0).get("X-Filename"));
    assertEquals("https://api.relaypdf.com/v1/pdf/ocr", urls.get(1));
    assertEquals("invoice-1", captured.get(1).get("Idempotency-Key"));
    assertEquals("40000", captured.get(1).get("X-RelayPDF-Max-Charge-Microdollars"));
    assertTrue(job instanceof AsyncResult);
  }

  private static RelayPDF client(RelayPDF.Transport transport) {
    return new RelayPDF("pdf_live_test", RelayPDF.DEFAULT_BASE_URL, transport);
  }

  private static RelayPDF.TransportResponse pdf(String body, String filename, String id) {
    return new RelayPDF.TransportResponse(200, Map.of(
        "content-type", "application/pdf",
        "content-disposition", "attachment; filename=\"" + filename + "\"",
        "x-relaypdf-id", id,
        "x-relaypdf-size", Integer.toString(body.length())
    ), body.getBytes(StandardCharsets.UTF_8));
  }

  private static RelayPDF.TransportResponse json(int status, String body, Map<String, String> extraHeaders) {
    java.util.HashMap<String, String> headers = new java.util.HashMap<>(extraHeaders);
    headers.put("content-type", "application/json");
    return new RelayPDF.TransportResponse(status, headers, body.getBytes(StandardCharsets.UTF_8));
  }
}
