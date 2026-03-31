# LockIn — Testing Manual

This document describes how to run, understand, and extend the test suite for the
LockIn Android parental-control application.

---

## Prerequisites

| Tool | Version |
|------|---------|
| JDK  | 17+ (`java -version`) |
| Android SDK | API 34 (set `ANDROID_HOME`) |
| Gradle wrapper | 8.7 (use `./gradlew`) |

No device or emulator is required for the unit tests described here.

---

## Running All Unit Tests

```bash
./gradlew test
```

This runs the JVM unit tests for every module. Output is written to:

```
<module>/build/reports/tests/testDebugUnitTest/index.html
```

To run tests for a single module:

```bash
./gradlew :core:vpn:test
./gradlew :core:security:test
./gradlew :core:filter:test
./gradlew :core:sync:test
```

To run a single test class:

```bash
./gradlew :core:vpn:test --tests "com.lockin.vpn.DnsResponseBuilderTest"
```

To run a single test method:

```bash
./gradlew :core:vpn:test --tests "com.lockin.vpn.PacketParserTest.IPv4 UDP port 53 is parsed as DnsQuery"
```

---

## Test Modules and Coverage

### `:core:vpn`

| Test Class | What it covers |
|---|---|
| `DnsPacketParserTest` | RFC 1035 DNS query parsing — labels, multi-label, QR bit, truncated/empty |
| `TlsClientHelloParserTest` | TLS 1.x ClientHello SNI extraction — ext walk, no SNI, truncated |
| `HttpHostParserTest` | HTTP Host header extraction — GET/POST/HEAD, port stripping, lowercase, non-HTTP rejection |
| `DnsResponseBuilderTest` | NXDOMAIN synthesis — flags (QR, RA, RCODE=3), zero-ANCOUNT, TX ID preservation; zero-IP response — ANCOUNT=1, 0.0.0.0 RDATA, TTL=60, compression pointer |
| `PacketParserTest` | IPv4/IPv6 packet dispatch — UDP/53→DNS, TCP/443→TLS, TCP/80→HTTP, other protocols/ports→Unknown |
| `UdpPacketWrapperTest` | IPv4/UDP framing for DNS responses — IP/port swap, checksum validity (RFC 791), payload preservation, TTL, DF flag |

**Running VPN tests:**

```bash
./gradlew :core:vpn:test
```

**How to read a test failure:** Each test builds raw byte arrays by hand (no Android SDK). If
a parser test fails, the most likely cause is an off-by-one error in byte offsets. Print
`rawPacket.toHex()` using the helper below:

```kotlin
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
```

---

### `:core:security`

| Test Class | What it covers |
|---|---|
| `Argon2HasherTest` | PHC string format, verify correct/wrong, two hashes differ (random salt), raw PIN not stored, empty PIN throws |
| `BruteForceGuardTest` | Lockout schedule (no lockout for ≤3 failures; 30s/2m/10m/30m/1h thresholds), failure counter, success resets, persistence across reconstruction |
| `PinManagerTest` | Validation rules (min 6 chars, all-same, sequential), hash stored not raw PIN, verify delegates to hasher, lockout blocks verify, failure/success counters |

**Running security tests:**

```bash
./gradlew :core:security:test
```

**Note on Argon2HasherTest:** This test computes a real Argon2id hash (64 MB memory,
3 iterations). On a slow CI machine this takes ~3–5 seconds per test. This is expected;
the KDF is intentionally slow.

**Test doubles used:**

- `FakeEncryptedPrefsStore` (`test-support` module) — pure in-memory implementation of
  `PrefsStore`; requires no Android context or Keystore.
- `Argon2Hasher` is mocked via MockK in `PinManagerTest` — crypto correctness is already
  covered by `Argon2HasherTest`.

---

### `:core:filter`

| Test Class | What it covers |
|---|---|
| `BloomFilterTest` | Zero false negatives for 100k domains, FPP < 2% for non-members, serialize/deserialize round-trip, insertion count, empty filter |
| `FilterEngineTest` | BLOCK/ALLOW decisions, subdomain matching (`ads.example.com` → block if `example.com` blocked), allowlist wins over blocklist, bloom false positive fallback to DB, trailing dot normalisation |

**Running filter tests:**

```bash
./gradlew :core:filter:test
```

---

### `:core:sync`

| Test Class | What it covers |
|---|---|
| `HostsFileParserTest` | All three hosts-file formats (`0.0.0.0`, `127.0.0.1`, bare domain), CRLF/LF line endings, comment stripping, localhost/broadcasthost exclusion, invalid domain rejection, 100k-line performance, deduplication |

**Running sync tests:**

```bash
./gradlew :core:sync:test
```

---

## Test Doubles (`:test-support`)

The `test-support` library provides reusable fakes:

| Class | Replaces | Notes |
|---|---|---|
| `FakeFilterEngine` | `FilterEngine` | Configurable blocked domains; `blockDomain()` / `clearBlocklist()` |
| `FakeEncryptedPrefsStore` | `PrefsStore` / `EncryptedPrefsStore` | In-memory maps; `clear()` to reset between tests |
| `TestBloomFilter` | `BloomFilter` construction | Pre-seeded filter for filter engine tests |

To use in a test:

```kotlin
val store = FakeEncryptedPrefsStore()
val guard = BruteForceGuard(store)
```

---

## Instrumented Tests (Android Device / Emulator)

The following require a running device or emulator (API 26+):

- `EncryptedPrefsStore` — requires Android Keystore hardware
- `LockInVpnService` — requires `VpnService` system call
- `LockInAccessibilityService` — requires Accessibility framework
- `DeviceOwnerManager` — requires Device Owner provisioning (see setup guide)

Run instrumented tests:

```bash
./gradlew connectedAndroidTest
```

> Currently no instrumented tests are written. These are candidates for future addition
> using Robolectric (`@RunWith(RobolectricTestRunner::class)`) or Espresso.

---

## What Is NOT Covered (and Why)

| Area | Reason |
|---|---|
| `LockInVpnService` packet loop | Android service; requires VpnService framework. Covered indirectly via `PacketParser`, `DnsResponseBuilder`, and `UdpPacketWrapper` unit tests |
| `BlocklistSyncWorker` | WorkManager integration test; `ListenableWorker` requires Android context |
| `LockInAccessibilityService` | Requires Accessibility framework; use Espresso UI tests |
| `TamperDetector` | Environment-dependent (reads `/proc/self/maps`); testing it would require mocking the OS |
| `PermissionsFragment` | UI state polling; use Espresso + mock `Settings.Secure` |

---

## Adding a New Test

1. Choose the correct module (`core:vpn`, `core:security`, etc.)
2. Place the test under `src/test/kotlin/com/lockin/<module>/`
3. For pure logic: extend `Any`, use `@Test` from `org.junit.Test`
4. For coroutine tests: use `runTest` from `kotlinx.coroutines.test`
5. For Android-aware JVM tests: add `@RunWith(RobolectricTestRunner::class)` (Robolectric is already a `testImplementation` dependency in security and filter modules)
6. If your test needs a controllable `PrefsStore`, use `FakeEncryptedPrefsStore` from `test-support`

---

## Continuous Integration

Tests run automatically on every commit via GitHub Actions (when configured). To validate
locally before pushing:

```bash
./gradlew test --continue   # run all tests, don't stop on first failure
```

The `--continue` flag ensures all modules are tested even if one fails.

---

## Quick Reference: Key Test Commands

```bash
# All unit tests
./gradlew test

# Specific module
./gradlew :core:vpn:test
./gradlew :core:security:test
./gradlew :core:filter:test
./gradlew :core:sync:test

# Single class
./gradlew :core:vpn:test --tests "com.lockin.vpn.UdpPacketWrapperTest"

# With live output
./gradlew :core:security:test --info

# HTML report (open in browser)
open core/vpn/build/reports/tests/testDebugUnitTest/index.html
```
