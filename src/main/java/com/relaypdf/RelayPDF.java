package com.relaypdf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RelayPDF {
  public static final String DEFAULT_BASE_URL = "https://api.relaypdf.com";
  public static final String VERSION = "0.1.1";
  public static final String USER_AGENT = "relaypdf-java/" + VERSION + " (+https://relaypdf.com)";

  @FunctionalInterface
  public interface Transport {
    TransportResponse send(String method, String url, Map<String, String> headers, byte[] body) throws IOException, InterruptedException;
  }

  public record TransportResponse(int status, Map<String, String> headers, byte[] body) {}

  public final PdfResource pdf;
  public final ImagesResource images;
  public final BarcodesResource barcodes;
  public final ZipResource zip;
  public final ConvertResource convert;
  public final TemplatesResource templates;
  public final JobsResource jobs;
  public final FilesResource files;
  public final WebhooksResource webhooks;

  private final String apiKey;
  private final String baseUrl;
  private final Transport transport;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public RelayPDF(String apiKey) {
    this(apiKey, DEFAULT_BASE_URL, null);
  }

  public RelayPDF(String apiKey, String baseUrl) {
    this(apiKey, baseUrl, null);
  }

  public RelayPDF(String apiKey, String baseUrl, Transport transport) {
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalArgumentException("apiKey is required.");
    }
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.transport = transport != null ? transport : defaultTransport();
    this.pdf = new PdfResource(this);
    this.images = new ImagesResource(this);
    this.barcodes = new BarcodesResource(this);
    this.zip = new ZipResource(this);
    this.convert = new ConvertResource(this);
    this.templates = new TemplatesResource(this);
    this.jobs = new JobsResource(this);
    this.files = new FilesResource(this);
    this.webhooks = new WebhooksResource(this);
  }

  /** Input accepts url, base64 file or an uploaded fileId. */
  public GenerateResult process(String operation, Map<String, Object> input) {
    return process(operation, input, Map.of());
  }

  public GenerateResult process(String operation, Map<String, Object> input, Map<String, Object> billing) {
    String path = switch (operation) {
      case "ocr" -> "/v1/pdf/ocr";
      case "pdfa" -> "/v1/pdf/pdfa";
      case "crop" -> "/v1/pdf/crop";
      case "resize" -> "/v1/pdf/resize";
      case "repair" -> "/v1/pdf/repair";
      case "optimize" -> "/v1/pdf/optimize";
      case "attachments" -> "/v1/pdf/attachments";
      case "extract-images" -> "/v1/pdf/extract-images";
      case "compress" -> "/v1/pdf/compress-advanced";
      case "image-convert" -> "/v1/images/convert";
      case "email" -> "/v1/email";
      default -> throw new IllegalArgumentException("Unknown document operation");
    };
    Map<String, String> headers = new LinkedHashMap<>();
    if (billing != null) {
      Object key = billing.get("idempotencyKey");
      if (key != null && !key.toString().isEmpty()) headers.put("Idempotency-Key", key.toString());
      Object cap = billing.get("maxChargeMicrodollars");
      if (cap != null) headers.put("X-RelayPDF-Max-Charge-Microdollars", cap.toString());
    }
    return generate(path, input, headers);
  }

  public Map<String, Object> billingUsage() {
    return sendJson("GET", "/v1/billing/usage", null, true);
  }

  public Map<String, Object> billingLimits() {
    return sendJson("GET", "/v1/billing/limits", null, true);
  }

  public Map<String, Object> billingLimits(Integer maxJobMicrodollars) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("maxJobMicrodollars", maxJobMicrodollars);
    return sendJson("PATCH", "/v1/billing/limits", body, true);
  }

  public Map<String, Object> health() {
    return sendJson("GET", "/health", null, false);
  }

  public Map<String, Object> account() {
    return sendJson("GET", "/v1/account", null, true);
  }

  TransportResponse request(String method, String path, Object body, boolean auth) {
    return request(method, path, body, auth, Map.of(), false);
  }

  TransportResponse request(String method, String path, Object body, boolean auth, Map<String, String> extraHeaders, boolean raw) {
    try {
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("User-Agent", USER_AGENT);
      if (extraHeaders != null) headers.putAll(extraHeaders);
      byte[] payload = null;
      if (auth) headers.put("Authorization", "Bearer " + apiKey);
      if (raw && body instanceof byte[] bytes) {
        payload = bytes;
      } else if (body != null) {
        headers.put("Content-Type", "application/json");
        payload = MAPPER.writeValueAsBytes(body);
      }
      TransportResponse response = transport.send(method, baseUrl + path, headers, payload);
      if (response.status() >= 400) throw errorFrom(response);
      return response;
    } catch (RelayPDFException e) {
      throw e;
    } catch (Exception e) {
      throw new RelayPDFException(503, "internal_error", e.getMessage() == null ? "HTTP request failed." : e.getMessage(), null, null);
    }
  }

  GenerateResult generate(String path, Map<String, Object> body) {
    return generate(path, body, Map.of());
  }

  GenerateResult generate(String path, Map<String, Object> body, Map<String, String> extraHeaders) {
    TransportResponse response = request("POST", path, body, true, extraHeaders, false);
    if (response.status() == 202) {
      Map<String, Object> payload = readMap(response.body());
      return new AsyncResult("async", str(payload.get("id")), "processing", str(payload.get("pollUrl")));
    }
    String contentType = header(response.headers(), "content-type");
    if (contentType.contains("application/json")) {
      Map<String, Object> payload = readMap(response.body());
      if (payload.get("url") != null && "completed".equals(payload.get("status"))) {
        return new UrlResult(
            "url",
            str(payload.get("id")),
            str(payload.get("status")),
            str(payload.get("url")),
            str(payload.get("filename")),
            asInt(payload.get("sizeBytes"), response.body().length),
            str(payload.get("expiresAt"))
        );
      }
      return new BinaryResult(
          "binary",
          firstNonEmpty(header(response.headers(), "x-relaypdf-id"), str(payload.get("id"))),
          filenameFromDisposition(header(response.headers(), "content-disposition")),
          response.body().length,
          contentType,
          response.body()
      );
    }
    String size = header(response.headers(), "x-relaypdf-size");
    return new BinaryResult(
        "binary",
        header(response.headers(), "x-relaypdf-id"),
        filenameFromDisposition(header(response.headers(), "content-disposition")),
        size.isEmpty() ? response.body().length : Integer.parseInt(size),
        contentType.isEmpty() ? "application/octet-stream" : contentType,
        response.body()
    );
  }

  Map<String, Object> sendJson(String method, String path, Object body, boolean auth) {
    return readMap(request(method, path, body, auth).body());
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> readMap(byte[] body) {
    try {
      if (body == null || body.length == 0) return Map.of();
      return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new RelayPDFException(500, "internal_error", "Invalid JSON response.", null, null);
    }
  }

  static RelayPDFException errorFrom(TransportResponse response) {
    String code = "internal_error";
    String message = "Request failed.";
    Object details = null;
    Integer retryAfter = null;
    String retryRaw = header(response.headers(), "retry-after");
    if (!retryRaw.isEmpty()) {
      try { retryAfter = Integer.parseInt(retryRaw); } catch (NumberFormatException ignored) {}
    }
    try {
      JsonNode root = MAPPER.readTree(response.body());
      JsonNode err = root.path("error");
      if (!err.isMissingNode()) {
        if (err.path("code").isTextual()) code = err.path("code").asText();
        if (err.path("message").isTextual()) message = err.path("message").asText();
        if (!err.path("details").isMissingNode()) details = MAPPER.convertValue(err.get("details"), Object.class);
      }
    } catch (Exception ignored) {}
    return new RelayPDFException(response.status(), code, message, retryAfter, details);
  }

  static String header(Map<String, String> headers, String name) {
    for (var e : headers.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
    }
    return "";
  }

  static String filenameFromDisposition(String header) {
    if (header == null || header.isEmpty()) return null;
    Matcher star = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(header);
    if (star.find()) return java.net.URLDecoder.decode(star.group(1), StandardCharsets.UTF_8);
    Matcher quoted = Pattern.compile("filename=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(header);
    if (quoted.find()) return quoted.group(1);
    Matcher plain = Pattern.compile("filename=([^;]+)", Pattern.CASE_INSENSITIVE).matcher(header);
    if (plain.find()) return plain.group(1).trim();
    return null;
  }

  static String encodeFile(Object value) {
    if (value instanceof byte[] bytes) return Webhook.toBase64(bytes);
    if (value instanceof String s) return Webhook.toBase64(s);
    throw new IllegalArgumentException("file must be byte[] or String.");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> encodeRef(Map<String, Object> item) {
    if (item.get("url") != null && !item.get("url").toString().isEmpty()) {
      return Map.of("url", item.get("url"));
    }
    if (item.get("file") != null) {
      Map<String, Object> encoded = new LinkedHashMap<>();
      encoded.put("file", encodeFile(item.get("file")));
      if (item.get("filename") != null) encoded.put("filename", item.get("filename"));
      return encoded;
    }
    throw new IllegalArgumentException("Provide exactly one of `url` or `file`.");
  }

  static Map<String, Object> merge(Map<String, Object> extra, Object... kv) {
    Map<String, Object> body = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
    for (int i = 0; i < kv.length; i += 2) body.put((String) kv[i], kv[i + 1]);
    return body;
  }

  static void encodeFileField(Map<String, Object> body) {
    if (body.get("file") != null) body.put("file", encodeFile(body.get("file")));
  }

  static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  static String firstNonEmpty(String a, String b) {
    return a != null && !a.isEmpty() ? a : b;
  }

  static int asInt(Object value, int fallback) {
    if (value instanceof Number n) return n.intValue();
    return fallback;
  }

  private static Transport defaultTransport() {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    return (method, url, headers, body) -> {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2));
      headers.forEach(b::header);
      HttpRequest.BodyPublisher publisher = body == null
          ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofByteArray(body);
      b.method(method, publisher);
      HttpResponse<byte[]> response;
      try {
        response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted", e);
      }
      Map<String, String> responseHeaders = new LinkedHashMap<>();
      response.headers().map().forEach((k, v) -> {
        if (!v.isEmpty()) responseHeaders.put(k, v.get(0));
      });
      return new TransportResponse(response.statusCode(), responseHeaders, response.body());
    };
  }

  public static final class PdfResource {
    private final RelayPDF client;
    PdfResource(RelayPDF client) { this.client = client; }

    public GenerateResult create(Map<String, Object> input) { return client.generate("/v1/pdf", input); }
    public GenerateResult fromHtml(String html, Map<String, Object> extra) { return create(merge(extra, "html", html)); }
    public GenerateResult fromHtml(String html) { return fromHtml(html, Map.of()); }
    public GenerateResult fromUrl(String url, Map<String, Object> extra) { return create(merge(extra, "url", url)); }
    public GenerateResult fromUrl(String url) { return fromUrl(url, Map.of()); }
    public GenerateResult fromMarkdown(String markdown, Map<String, Object> extra) { return create(merge(extra, "markdown", markdown)); }
    public GenerateResult fromMarkdown(String markdown) { return fromMarkdown(markdown, Map.of()); }
    public GenerateResult fromTemplate(String templateId, Object templateData, Map<String, Object> extra) {
      return create(merge(extra, "templateId", templateId, "templateData", templateData));
    }
    public GenerateResult mergePdfs(List<Map<String, Object>> files, Map<String, Object> extra) {
      List<Map<String, Object>> encoded = new ArrayList<>();
      for (Map<String, Object> f : files) encoded.add(encodeRef(f));
      return client.generate("/v1/pdf/merge", merge(extra, "files", encoded));
    }
    public GenerateResult extract(Object pages, Map<String, Object> input) {
      Map<String, Object> body = merge(input, "pages", pages);
      encodeFileField(body);
      return client.generate("/v1/pdf/extract", body);
    }
    public GenerateResult protect(String userPassword, Map<String, Object> input) {
      Map<String, Object> body = merge(input, "userPassword", userPassword);
      encodeFileField(body);
      return client.generate("/v1/pdf/protect", body);
    }
    public GenerateResult bookmarks(Object bookmarks, Map<String, Object> input) {
      Map<String, Object> body = merge(input, "bookmarks", bookmarks);
      encodeFileField(body);
      return client.generate("/v1/pdf/bookmarks", body);
    }
    public GenerateResult raster(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      return client.generate("/v1/pdf/raster", body);
    }
    public GenerateResult fromImages(List<Map<String, Object>> files, Map<String, Object> extra) {
      List<Map<String, Object>> encoded = new ArrayList<>();
      for (Map<String, Object> f : files) encoded.add(encodeRef(f));
      return client.generate("/v1/pdf/from-images", merge(extra, "files", encoded));
    }
    public GenerateResult stamp(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      Object image = body.get("image");
      if (image instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked") Map<String, Object> img = (Map<String, Object>) map;
        body.put("image", encodeRef(img));
      }
      return client.generate("/v1/pdf/stamp", body);
    }
    public GenerateResult rotate(int degrees, Map<String, Object> input) {
      Map<String, Object> body = merge(input, "degrees", degrees);
      encodeFileField(body);
      return client.generate("/v1/pdf/rotate", body);
    }
    public GenerateResult deletePages(Object pages, Map<String, Object> input) {
      Map<String, Object> body = merge(input, "pages", pages);
      encodeFileField(body);
      return client.generate("/v1/pdf/delete-pages", body);
    }
    public GenerateResult compress(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      return client.generate("/v1/pdf/compress", body);
    }
    public GenerateResult info(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      return client.generate("/v1/pdf/info", body);
    }
    public GenerateResult unlock(String password, Map<String, Object> input) {
      Map<String, Object> body = merge(input, "password", password);
      encodeFileField(body);
      return client.generate("/v1/pdf/unlock", body);
    }
    public GenerateResult text(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      return client.generate("/v1/pdf/text", body);
    }
    public GenerateResult data(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      return client.generate("/v1/pdf/data", body);
    }
    public GenerateResult formFields(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      return client.generate("/v1/pdf/form/fields", body);
    }
    public GenerateResult formFill(Object fields, Map<String, Object> input) {
      Map<String, Object> body = merge(input, "fields", fields);
      encodeFileField(body);
      return client.generate("/v1/pdf/form/fill", body);
    }
  }

  public static final class ImagesResource {
    private final RelayPDF client;
    ImagesResource(RelayPDF client) { this.client = client; }
    public GenerateResult create(Map<String, Object> input) { return client.generate("/v1/images", input); }
    public GenerateResult fromHtml(String html, Map<String, Object> extra) { return create(merge(extra, "html", html)); }
    public GenerateResult fromUrl(String url, Map<String, Object> extra) { return create(merge(extra, "url", url)); }
  }

  public static final class BarcodesResource {
    private final RelayPDF client;
    BarcodesResource(RelayPDF client) { this.client = client; }
    public GenerateResult create(Map<String, Object> input) { return client.generate("/v1/barcodes", input); }
    public GenerateResult qr(String text, Map<String, Object> extra) { return create(merge(extra, "type", "qr", "text", text)); }
    public GenerateResult qr(String text) { return qr(text, Map.of()); }
  }

  public static final class ZipResource {
    private final RelayPDF client;
    ZipResource(RelayPDF client) { this.client = client; }
    public GenerateResult create(List<Map<String, Object>> files, Map<String, Object> extra) {
      List<Map<String, Object>> encoded = new ArrayList<>();
      for (Map<String, Object> item : files) {
        Map<String, Object> row = new LinkedHashMap<>(encodeRef(item));
        if (item.get("filename") != null) row.put("filename", item.get("filename"));
        encoded.add(row);
      }
      return client.generate("/v1/zip", merge(extra, "files", encoded));
    }
  }

  public static final class ConvertResource {
    private final RelayPDF client;
    ConvertResource(RelayPDF client) { this.client = client; }
    public GenerateResult create(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      encodeFileField(body);
      return client.generate("/v1/convert", body);
    }
    public GenerateResult fromHtml(String html, Map<String, Object> extra) { return create(merge(extra, "html", html)); }
    public GenerateResult fromPath(String path, Map<String, Object> extra) {
      Map<String, Object> body = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
      body.putIfAbsent("sourceFilename", Path.of(path).getFileName().toString());
      try { body.put("file", Files.readAllBytes(Path.of(path))); }
      catch (IOException e) { throw new IllegalArgumentException("Unable to read " + path, e); }
      return create(body);
    }
    public GenerateResult wkhtml(Map<String, Object> input) {
      Map<String, Object> body = new LinkedHashMap<>(input);
      Object toc = body.remove("toc");
      Object optionsObj = body.remove("options");
      Map<String, Object> options = optionsObj instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
      if (toc != null) options.put("toc", toc);
      return create(merge(body, "engine", "wkhtmltopdf", "to", "pdf", "options", options.isEmpty() ? null : options));
    }
  }

  public static final class TemplatesResource {
    private final RelayPDF client;
    TemplatesResource(RelayPDF client) { this.client = client; }
    public Map<String, Object> list() { return client.sendJson("GET", "/v1/templates", null, true); }
    public Map<String, Object> gallery() { return client.sendJson("GET", "/v1/templates/gallery", null, true); }
    public Map<String, Object> get(String id) { return client.sendJson("GET", "/v1/templates/" + id, null, true); }
    public Map<String, Object> create(Map<String, Object> input) { return client.sendJson("POST", "/v1/templates", merge(input, "engine", "handlebars"), true); }
    public Map<String, Object> update(String id, Map<String, Object> input) { return client.sendJson("PATCH", "/v1/templates/" + id, input, true); }
    public Map<String, Object> publish(String id, String comment) { return client.sendJson("POST", "/v1/templates/" + id + "/publish", merge(Map.of(), "comment", comment), true); }
    public Map<String, Object> delete(String id) { return client.sendJson("DELETE", "/v1/templates/" + id, null, true); }
    public Map<String, Object> discard(String id) { return client.sendJson("POST", "/v1/templates/" + id + "/discard", Map.of(), true); }
    public Map<String, Object> duplicate(String id) { return client.sendJson("POST", "/v1/templates/" + id + "/duplicate", Map.of(), true); }
    public Map<String, Object> versions(String id) { return client.sendJson("GET", "/v1/templates/" + id + "/versions", null, true); }
    public Map<String, Object> restore(String id, int version) { return client.sendJson("POST", "/v1/templates/" + id + "/restore", Map.of("version", version), true); }
    public Map<String, Object> validate(Map<String, Object> input) { return client.sendJson("POST", "/v1/templates/validate", merge(input, "engine", "handlebars"), true); }
    public GenerateResult preview(Map<String, Object> input) { return client.generate("/v1/templates/preview", merge(input, "engine", "handlebars")); }
    public Map<String, Object> generate(Map<String, Object> input) { return client.sendJson("POST", "/v1/templates/generate", input, true); }
  }

  public static final class JobsResource {
    private final RelayPDF client;
    JobsResource(RelayPDF client) { this.client = client; }
    public Map<String, Object> get(String id) { return client.sendJson("GET", "/v1/jobs/" + id, null, true); }
    public Map<String, Object> waitFor(String id) { return waitFor(id, 1000, 120_000); }
    public Map<String, Object> waitFor(String id, int intervalMs, int timeoutMs) {
      long deadline = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() < deadline) {
        Map<String, Object> job = get(id);
        String status = str(job.get("status"));
        if ("completed".equals(status)) return job;
        if ("failed".equals(status)) {
          @SuppressWarnings("unchecked") Map<String, Object> err = job.get("error") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
          throw new RelayPDFException(502, str(err.getOrDefault("code", "processing_failed")), str(err.getOrDefault("message", "Job failed.")), null, null);
        }
        try { Thread.sleep(intervalMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RelayPDFException(408, "processing_failed", "Interrupted.", null, null); }
      }
      throw new RelayPDFException(408, "processing_failed", "Timed out waiting for job " + id + ".", null, null);
    }
  }

  public static final class FilesResource {
    private final RelayPDF client;
    FilesResource(RelayPDF client) { this.client = client; }
    public BinaryResult download(String id) {
      TransportResponse response = client.request("GET", "/v1/files/" + id, null, false);
      String size = header(response.headers(), "x-relaypdf-size");
      String headerId = header(response.headers(), "x-relaypdf-id");
      return new BinaryResult(
          "binary",
          headerId.isEmpty() ? id : headerId,
          filenameFromDisposition(header(response.headers(), "content-disposition")),
          size.isEmpty() ? response.body().length : Integer.parseInt(size),
          firstNonEmpty(header(response.headers(), "content-type"), "application/octet-stream"),
          response.body()
      );
    }

    public Map<String, Object> upload(byte[] bytes, String filename) {
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Content-Type", "application/octet-stream");
      headers.put("Content-Length", Integer.toString(bytes.length));
      headers.put("X-Filename", filename == null || filename.isEmpty() ? "upload.bin" : filename);
      return readMap(client.request("POST", "/v1/files", bytes, true, headers, true).body());
    }

    public Map<String, Object> delete(String id) {
      return client.sendJson("DELETE", "/v1/files/" + id, null, true);
    }
  }

  public static final class WebhooksResource {
    private final RelayPDF client;
    WebhooksResource(RelayPDF client) { this.client = client; }
    public Map<String, Object> list() { return client.sendJson("GET", "/v1/webhooks", null, true); }
    public Map<String, Object> create(String url, List<String> events) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("url", url);
      if (events != null) payload.put("events", events);
      return client.sendJson("POST", "/v1/webhooks", payload, true);
    }
    public Map<String, Object> delete(String id) { return client.sendJson("DELETE", "/v1/webhooks/" + id, null, true); }
  }
}
