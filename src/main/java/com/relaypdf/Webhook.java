package com.relaypdf;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public final class Webhook {
  public static final String SIGNATURE_HEADER = "RelayPDF-Signature";
  public static final String EVENT_HEADER = "RelayPDF-Event";

  private Webhook() {}

  public static boolean verify(String secret, String body, String header) {
    return verify(secret, body, header, 300);
  }

  public static boolean verify(String secret, String body, String header, int toleranceSec) {
    Map<String, String> parts = new HashMap<>();
    for (String item : header.split(",")) {
      int eq = item.indexOf('=');
      if (eq < 0) continue;
      parts.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
    }
    String tRaw = parts.get("t");
    String expected = parts.get("v1");
    if (tRaw == null || expected == null) return false;
    long timestamp;
    try { timestamp = Long.parseLong(tRaw); }
    catch (NumberFormatException e) { return false; }
    if (Math.abs(Instant.now().getEpochSecond() - timestamp) > toleranceSec) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
      String hex = toHex(digest);
      return MessageDigest.isEqual(hex.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return false;
    }
  }

  public static String toBase64(byte[] data) {
    return Base64.getEncoder().encodeToString(data);
  }

  public static String toBase64(String data) {
    if (data.startsWith("data:") && data.contains(";base64,")) {
      return data.split(";base64,", 2)[1];
    }
    return Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}
