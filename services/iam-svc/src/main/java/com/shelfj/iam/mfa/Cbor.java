package com.shelfj.iam.mfa;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little of CBOR (RFC 8949) a WebAuthn attestation object and a COSE key use: integers, byte
 * and text strings, arrays, maps, and the simple values false, true and null — definite lengths
 * only. Anything else (tags, floats, indefinite lengths, absurd sizes, nesting past a few levels)
 * is refused rather than guessed at: the bytes come from a browser nobody here controls.
 *
 * <p>The reader keeps its place, because an authenticator's data carries a CBOR key followed by
 * bytes that are not part of it.
 */
public final class Cbor {

  private static final int MAX_DEPTH = 8;
  private static final int MAX_ITEMS = 64;

  private final byte[] data;
  private int at;

  public Cbor(byte[] data, int offset) {
    this.data = data.clone();
    this.at = offset;
  }

  /** Decodes one whole document; trailing bytes are an error. */
  public static Object decode(byte[] data) {
    Cbor reader = new Cbor(data, 0);
    Object value = reader.next();
    if (reader.at != data.length) throw new IllegalArgumentException("bytes after the CBOR value");
    return value;
  }

  /** Where the reader stands: the offset of the first byte not yet read. */
  public int position() {
    return at;
  }

  /** Reads the next value: Long, byte[], String, List, Map, Boolean or null. */
  public Object next() {
    return read(0);
  }

  private Object read(int depth) {
    if (depth > MAX_DEPTH) throw new IllegalArgumentException("CBOR nested too deep");
    int first = u8();
    int major = first >> 5;
    int info = first & 0x1f;
    switch (major) {
      case 0:
        return length(info);
      case 1:
        return -1 - length(info);
      case 2:
        return bytes(length(info));
      case 3:
        return new String(bytes(length(info)), StandardCharsets.UTF_8);
      case 4:
        return list(count(length(info)), depth);
      case 5:
        return map(count(length(info)), depth);
      case 7:
        if (info == 20) return Boolean.FALSE;
        if (info == 21) return Boolean.TRUE;
        if (info == 22) return null;
        throw new IllegalArgumentException(
            "a CBOR simple value or float that is not expected here");
      default:
        throw new IllegalArgumentException("a CBOR tag is not expected here");
    }
  }

  private List<Object> list(int n, int depth) {
    List<Object> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) list.add(read(depth + 1));
    return list;
  }

  private Map<Object, Object> map(int n, int depth) {
    Map<Object, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      Object key = read(depth + 1);
      if (!(key instanceof Long) && !(key instanceof String)) {
        throw new IllegalArgumentException("a CBOR map key that is neither number nor text");
      }
      if (map.put(key, read(depth + 1)) != null) {
        throw new IllegalArgumentException("a CBOR map key given twice");
      }
    }
    return map;
  }

  private long length(int info) {
    if (info < 24) return info;
    if (info == 24) return u8();
    if (info == 25) return ((long) u8() << 8) | u8();
    if (info == 26) return ((long) u8() << 24) | ((long) u8() << 16) | ((long) u8() << 8) | u8();
    if (info == 27) {
      long value = 0;
      for (int i = 0; i < 8; i++) value = (value << 8) | u8();
      if (value < 0) throw new IllegalArgumentException("a CBOR number too large");
      return value;
    }
    throw new IllegalArgumentException("an indefinite or reserved CBOR length");
  }

  private static int count(long n) {
    if (n > MAX_ITEMS) throw new IllegalArgumentException("a CBOR collection too large");
    return (int) n;
  }

  private byte[] bytes(long n) {
    if (n > data.length - at) throw new IllegalArgumentException("a CBOR string runs past the end");
    byte[] out = Arrays.copyOfRange(data, at, at + (int) n);
    at += (int) n;
    return out;
  }

  private int u8() {
    if (at >= data.length) throw new IllegalArgumentException("CBOR ends early");
    return data[at++] & 0xff;
  }
}
