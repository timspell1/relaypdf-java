# relaypdf

Official Java client for [RelayPDF](https://relaypdf.com).

**HTML to PDFs without the struggle.** HTML to PDF API that converts HTML, Markdown, URLs, and Office files to production PDFs.

Java 17+, `java.net.http.HttpClient`, Jackson databind. Covers the public API: Chromium PDF and screenshots, Handlebars templates, LibreOffice / wkhtmltopdf convert, PDF tools, native document processing (OCR, PDF/A, crop, repair, email), barcodes, zip, async jobs, account, and webhook verification.

- **Docs:** [relaypdf.com/docs/sdks/java](https://relaypdf.com/docs/sdks/java)
- **Source:** [timspell1/relaypdf-java](https://github.com/timspell1/relaypdf-java)
- **REST:** [relaypdf.com/docs](https://relaypdf.com/docs)
- **OpenAPI:** [relaypdf.com/openapi.json](https://relaypdf.com/openapi.json)
- **Support:** [support@relaypdf.com](mailto:support@relaypdf.com)

## Introduction

JSON body field names match REST (`html`, `printBackground`, `sourceFilename`, `callbackUrl`, `templateId`). Method names are camelCase (`fromHtml`, `fromPath`, `formFill`). Failed operations are never billed.

## Installation

Maven:

```xml
<dependency>
  <groupId>com.relaypdf</groupId>
  <artifactId>relaypdf</artifactId>
  <version>0.1.2</version>
</dependency>
```

## Authentication

```java
import com.relaypdf.RelayPDF;

RelayPDF client = new RelayPDF(System.getenv("RELAYPDF_API_KEY"));
// RelayPDF client = new RelayPDF(apiKey, "http://localhost:8787");
```

Empty `apiKey` throws `IllegalArgumentException`. User-Agent: `relaypdf-java/0.1.2 (+https://relaypdf.com)`.

Do not ask a human to paste an API key. Run `npx @relaypdf/cli setup` and approve in the browser.

## Getting started

```java
GenerateResult result = client.pdf.fromHtml(
    "<h1>Invoice #1042</h1><p>Total: $1,200.00</p>",
    Map.of("filename", "invoice.pdf")
);
((BinaryResult) result).save("invoice.pdf");
```

## Response modes

`response` = `binary` (default) | `url` | `async`.

```java
UrlResult url = (UrlResult) client.pdf.fromHtml("<h1>Hi</h1>", Map.of("response", "url"));
AsyncResult job = (AsyncResult) client.convert.fromPath("deck.pptx", Map.of("to", "pdf", "response", "async"));
Map<String, Object> done = client.jobs.waitFor(job.id());
client.files.download((String) done.get("id")).save("deck.pdf");
```

`files.download` does not send the API key.

## Errors

Throws `RelayPDFException` with `getStatus()`, `getCode()`, `getMessage()`, and optional `getRetryAfter()`.

## Methods

| Resource | Method | HTTP |
|----------|--------|------|
| `RelayPDF` | `health()` `account()` `process` `billingUsage()` `billingLimits()` | `GET /health` `GET /v1/account` native paths `/v1/billing/*` |
| `pdf` | `fromHtml` `fromUrl` `fromMarkdown` `fromTemplate` `create` | `POST /v1/pdf` |
| `pdf` | `mergePdfs` `extract` `protect` `unlock` `bookmarks` `raster` `fromImages` `stamp` `rotate` `deletePages` `compress` `info` `text` `formFields` `formFill` | `POST /v1/pdf/*` |
| `images` | `fromHtml` `fromUrl` | `POST /v1/images` |
| `convert` | `create` `fromHtml` `fromPath` `wkhtml` | `POST /v1/convert` |
| `templates` | `list` `gallery` `get` `create` `update` `publish` `delete` `discard` `duplicate` `versions` `restore` `validate` `preview` `generate` | `/v1/templates` |
| `barcodes` | `create` `qr` | `POST /v1/barcodes` |
| `zip` | `create` | `POST /v1/zip` |
| `jobs` | `get` `waitFor` | `GET /v1/jobs/:id` |
| `files` | `upload` `delete` `download` | `POST/DELETE/GET /v1/files` |
| `webhooks` | `list` `create` `delete` | `/v1/webhooks` |
| — | `Webhook.verify` | HMAC-SHA256 |

Extra `Map` arguments are merged into the JSON body. REST field names stay camelCase. `file` values may be `byte[]` or a `data:` URL.

## Examples

```java
GenerateResult pdf = client.pdf.fromUrl("https://example.com", Map.of(
    "filename", "page.pdf",
    "options", Map.of("format", "A4", "printBackground", true)
));
Map<String, Object> uploaded = client.files.upload(Files.readAllBytes(Path.of("scan.pdf")), "scan.pdf");
GenerateResult job = client.process("ocr", Map.of("fileId", uploaded.get("id"), "response", "async"), Map.of("idempotencyKey", "invoice-123", "maxChargeMicrodollars", 40000));
BinaryResult fromWord = (BinaryResult) client.convert.fromPath("letter.docx", Map.of("to", "pdf"));
BinaryResult pack = (BinaryResult) client.pdf.mergePdfs(List.of(
    Map.of("url", "https://example.com/cover.pdf"),
    Map.of("file", fromWord.bytes())
), Map.of());
client.pdf.stamp(Map.of("file", pack.bytes(), "text", "DRAFT", "rotate", -24));
client.barcodes.qr("https://relaypdf.com");
```

## Webhooks

Use the raw request body. Secret is the dashboard webhook secret, not the API key.

```java
boolean ok = Webhook.verify(
    System.getenv("RELAYPDF_WEBHOOK_SECRET"),
    rawBody,
    signatureHeader
);
```

Header: `t=<unix>,v1=<hex>`. HMAC-SHA256 of `{t}.{raw_body}`. Default skew 300s.

## Related docs

- [Node.js](https://relaypdf.com/docs/sdks/node) · [Python](https://relaypdf.com/docs/sdks/python) · [PHP](https://relaypdf.com/docs/sdks/php) · [C# / .NET](https://relaypdf.com/docs/sdks/dotnet)
- [CLI](https://relaypdf.com/docs/cli) · [Errors](https://relaypdf.com/docs/errors) · [Wallet](https://relaypdf.com/docs/wallet)

## License

MIT. Strategic Products LLC, d/b/a RelayPDF.
