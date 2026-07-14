# Shengting Flavor Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `shengting` Android flavor, authenticate after enterprise object lookup, and add `Authorization` to the seven shengting `/ai/*` service calls.

**Architecture:** Keep the new behavior flavor-gated behind `BuildConfig.FLAVOR == "shengting"`. Add focused network/auth classes for AES, auth protocol parsing, token caching, and request authorization; keep endpoint migration in the existing JSONC flavor overlay. Reuse existing callback-style OkHttp flows and page-level navigation patterns instead of introducing coroutines or a global OkHttp interceptor.

**Tech Stack:** Kotlin, Android Gradle product flavors, Gson, OkHttp 4.12.0, JUnit4, Javax Crypto `AES/ECB/PKCS5Padding`, Rokid SDK serial via `RokidSdkManager.getSerialNumber()`.

## Global Constraints

- Code, file, and directory names remain English; user-facing communication and docs are Simplified Chinese.
- `shengting` uses existing flavor dimension `edition`.
- Only these seven AI endpoints are in scope: `/ai/auto`, `/ai/deep`, `/ai/gm`, `/ai/general`, `/ai/general_deep`, `/ai/device`, `/ai/sug_checks`.
- Do not migrate or authorize `/hxy/apis/third/smartGlasses`, hazard save, inspection finish, app update, or `has_hazard_answer`.
- Shengting AI base is `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy`.
- Auth URL is `https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/auth/check`.
- AES protocol follows `scripts/java/AESUtil.java`: `AES/ECB/PKCS5Padding`, `SecretKeySpec(key.getBytes(), "AES")`, Base64 output, secret `Btm/Cb6N6glbcOEvjV8qGnyQELjWFUkD`.
- Auth plaintext JSON is compact: `{"snCode":"<SN>","date":"yyyy-MM-dd"}`.
- Auth response succeeds only when `code == 200` and `data` is a nonblank token string.
- Header format is exactly `Authorization: <token>`.
- SN blank is an auth failure; do not use a fake SN.
- Do not log full SN, AES plaintext, AES ciphertext, or full token.
- Auth failure message is `身份鉴权失败，请检查网络或联系管理员`.
- Auth failure dialog confirmation returns to `MainMenuActivity` and clears enterprise/auth state.

---

## File Structure

- Modify `app/build.gradle`: add `shengting` product flavor.
- Create `app/src/main/assets/inspection_config.shengting.jsonc`: shengting AI endpoint overlay.
- Modify `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`: verify overlay URL behavior.
- Create `app/src/main/java/com/rokid/glass/network/ShengtingAes.kt`: Android-compatible AES encryptor.
- Create `app/src/main/java/com/rokid/glass/network/ShengtingAuthProtocol.kt`: request JSON and response parser.
- Create `app/src/test/java/com/rokid/glass/network/ShengtingAesTest.kt`: fixed AES vector.
- Create `app/src/test/java/com/rokid/glass/network/ShengtingAuthProtocolTest.kt`: auth body and parser tests.
- Create `app/src/main/java/com/rokid/glass/network/ShengtingAuthService.kt`: OkHttp auth API client.
- Create `app/src/main/java/com/rokid/glass/network/ShengtingAuthManager.kt`: flavor-gated token cache, retry, refresh, and clear.
- Create `app/src/test/java/com/rokid/glass/network/ShengtingAuthManagerTest.kt`: manager retry/cache/refresh tests with a fake gateway.
- Modify `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`: run auth after `getObjectMessage` success before navigating.
- Modify `app/src/main/java/com/rokid/glass/hiddenrisk/BaseGlassActivity.kt`: add reusable fatal auth dialog and return-to-main helper.
- Modify `app/src/main/res/values/strings.xml`: add auth failure strings.
- Modify `app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt`: obtain token before the seven AI requests, add header, refresh once on `401/403`.
- Modify `app/src/test/java/com/rokid/glass/hiddenrisk/AiArSseServiceRequestPayloadTest.kt`: add request-header helper tests after extracting a testable helper.
- Modify `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`, `DeviceGuideActivity.kt`, and `HazardRecordActivity.kt`: route fatal auth failures to the blocking dialog when service callbacks return the fatal auth marker.

---

### Task 1: Add shengting Flavor And Endpoint Overlay

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/assets/inspection_config.shengting.jsonc`
- Modify: `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`

**Interfaces:**
- Consumes: Existing `InspectionConfigRepository.buildConfig(baseJsonc, overlayJsonc)`.
- Produces: `BuildConfig.FLAVOR == "shengting"` variant and a flavor overlay with seven AI URLs.

- [ ] **Step 1: Write the failing config test**

Add this test to `InspectionConfigRepositoryTest`:

```kotlin
@Test
fun `shengting overlay points all ai endpoints to formal proxy`() {
    val config = InspectionConfigRepository.buildConfig(
        baseJsonc = null,
        overlayJsonc = """
            {
              "network": {
                "aiAutoApi": {
                  "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/auto"
                },
                "aiDeepApi": {
                  "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/deep"
                },
                "aiGmApi": {
                  "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/gm"
                },
                "aiGeneralApi": {
                  "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general"
                },
                "aiGeneralDeepApi": {
                  "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general_deep"
                },
                "aiDeviceApi": {
                  "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/device"
                },
                "aiSuggestionChecksApi": {
                  "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/sug_checks"
                }
              }
            }
        """.trimIndent(),
    )

    assertEquals("https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/auto", config.network.aiAutoApi.url)
    assertEquals("https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/deep", config.network.aiDeepApi.url)
    assertEquals("https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/gm", config.network.aiGmApi.url)
    assertEquals("https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general", config.network.aiGeneralApi.url)
    assertEquals("https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general_deep", config.network.aiGeneralDeepApi.url)
    assertEquals("https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/device", config.network.aiDeviceApi.url)
    assertEquals("https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/sug_checks", config.network.aiSuggestionChecksApi.url)
}
```

- [ ] **Step 2: Run the test to verify the current parser can support the overlay**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest.shengting overlay points all ai endpoints to formal proxy"`

Expected: PASS. This proves no parser change is needed before adding the asset.

- [ ] **Step 3: Add the flavor**

In `app/build.gradle`, extend `productFlavors`:

```groovy
productFlavors {
    standard {
        dimension "edition"
    }
    dataBackup {
        dimension "edition"
    }
    shengting {
        dimension "edition"
    }
}
```

- [ ] **Step 4: Add the shengting overlay asset**

Create `app/src/main/assets/inspection_config.shengting.jsonc`:

```jsonc
{
  "network": {
    "aiAutoApi": {
      "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/auto"
    },
    "aiDeepApi": {
      "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/deep"
    },
    "aiGmApi": {
      "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/gm"
    },
    "aiGeneralApi": {
      "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general"
    },
    "aiGeneralDeepApi": {
      "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/general_deep"
    },
    "aiDeviceApi": {
      "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/device"
    },
    "aiSuggestionChecksApi": {
      "url": "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/proxy/ai/sug_checks"
    }
  }
}
```

- [ ] **Step 5: Verify flavor tasks exist**

Run: `./gradlew :app:tasks --all | rg "assembleShengtingDebug|testShengtingDebugUnitTest"`

Expected: output contains both `assembleShengtingDebug` and `testShengtingDebugUnitTest`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle app/src/main/assets/inspection_config.shengting.jsonc app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt
git commit -m "feat: add shengting flavor config"
```

---

### Task 2: Add AES And Auth Protocol

**Files:**
- Create: `app/src/main/java/com/rokid/glass/network/ShengtingAes.kt`
- Create: `app/src/main/java/com/rokid/glass/network/ShengtingAuthProtocol.kt`
- Create: `app/src/test/java/com/rokid/glass/network/ShengtingAesTest.kt`
- Create: `app/src/test/java/com/rokid/glass/network/ShengtingAuthProtocolTest.kt`

**Interfaces:**
- Produces: `ShengtingAes.encrypt(text: String, key: String = SECRET): String`
- Produces: `ShengtingAuthProtocol.buildPlaintext(snCode: String, date: LocalDate): String`
- Produces: `ShengtingAuthProtocol.buildRequestJson(encryptedBody: String): String`
- Produces: `ShengtingAuthProtocol.parseToken(body: String): Result<String>`

- [ ] **Step 1: Write AES fixed-vector test**

Create `ShengtingAesTest.kt`:

```kotlin
package com.rokid.glass.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ShengtingAesTest {
    @Test
    fun encrypt_matchesReferenceJavaAesUtilVector() {
        val plaintext = """{"snCode":"GLASS_SN_001","date":"2026-07-09"}"""

        val encrypted = ShengtingAes.encrypt(plaintext)

        assertEquals(
            "S2dDP8YGkC6rbglEsjk2zaT0lUDMoNHNFGTIMhNc2tCPKxf59nhm9vdcDXHoFPrw",
            encrypted,
        )
    }
}
```

- [ ] **Step 2: Write auth protocol tests**

Create `ShengtingAuthProtocolTest.kt`:

```kotlin
package com.rokid.glass.network

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShengtingAuthProtocolTest {
    @Test
    fun buildPlaintext_usesCompactJsonWithSnCodeAndDate() {
        val plaintext = ShengtingAuthProtocol.buildPlaintext(
            snCode = "GLASS_SN_001",
            date = LocalDate.of(2026, 7, 9),
        )

        assertEquals("""{"snCode":"GLASS_SN_001","date":"2026-07-09"}""", plaintext)
    }

    @Test
    fun buildRequestJson_wrapsEncryptedBody() {
        val json = ShengtingAuthProtocol.buildRequestJson("encrypted-value")

        assertEquals("""{"body":"encrypted-value"}""", json)
    }

    @Test
    fun parseToken_acceptsCode200AndDataString() {
        val result = ShengtingAuthProtocol.parseToken(
            """{"code":200,"msg":"操作成功","data":"token-value"}""",
        )

        assertEquals("token-value", result.getOrThrow())
    }

    @Test
    fun parseToken_rejectsNon200Code() {
        val result = ShengtingAuthProtocol.parseToken(
            """{"code":500,"msg":"失败","data":"token-value"}""",
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun parseToken_rejectsBlankData() {
        val result = ShengtingAuthProtocol.parseToken(
            """{"code":200,"msg":"操作成功","data":"   "}""",
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun parseToken_rejectsMalformedJson() {
        val result = ShengtingAuthProtocol.parseToken("not-json")

        assertTrue(result.isFailure)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.ShengtingAesTest" --tests "com.rokid.glass.network.ShengtingAuthProtocolTest"`

Expected: FAIL with unresolved references to `ShengtingAes` and `ShengtingAuthProtocol`.

- [ ] **Step 4: Add AES implementation**

Create `ShengtingAes.kt`:

```kotlin
package com.rokid.glass.network

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object ShengtingAes {
    const val SECRET = "Btm/Cb6N6glbcOEvjV8qGnyQELjWFUkD"

    fun encrypt(text: String, key: String = SECRET): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }
}
```

- [ ] **Step 5: Add protocol implementation**

Create `ShengtingAuthProtocol.kt`:

```kotlin
package com.rokid.glass.network

import com.google.gson.Gson
import java.time.LocalDate

object ShengtingAuthProtocol {
    private val gson = Gson()

    data class PlaintextBody(
        val snCode: String,
        val date: String,
    )

    data class AuthRequest(
        val body: String,
    )

    data class AuthResponse(
        val code: Int? = null,
        val msg: String? = null,
        val data: String? = null,
    )

    fun buildPlaintext(snCode: String, date: LocalDate): String {
        return gson.toJson(PlaintextBody(snCode = snCode, date = date.toString()))
    }

    fun buildRequestJson(encryptedBody: String): String {
        return gson.toJson(AuthRequest(body = encryptedBody))
    }

    fun parseToken(body: String): Result<String> {
        return runCatching {
            val response = gson.fromJson(body, AuthResponse::class.java)
                ?: throw IllegalStateException("shengting auth response is empty")
            if (response.code != 200) {
                throw IllegalStateException("shengting auth code=${response.code} msg=${response.msg.orEmpty()}")
            }
            response.data?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("shengting auth token is blank")
        }
    }
}
```

- [ ] **Step 6: Run tests to verify pass**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.ShengtingAesTest" --tests "com.rokid.glass.network.ShengtingAuthProtocolTest"`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/glass/network/ShengtingAes.kt app/src/main/java/com/rokid/glass/network/ShengtingAuthProtocol.kt app/src/test/java/com/rokid/glass/network/ShengtingAesTest.kt app/src/test/java/com/rokid/glass/network/ShengtingAuthProtocolTest.kt
git commit -m "feat: add shengting auth protocol"
```

---

### Task 3: Add Auth Service And Token Manager

**Files:**
- Create: `app/src/main/java/com/rokid/glass/network/ShengtingAuthService.kt`
- Create: `app/src/main/java/com/rokid/glass/network/ShengtingAuthManager.kt`
- Create: `app/src/test/java/com/rokid/glass/network/ShengtingAuthManagerTest.kt`

**Interfaces:**
- Consumes: `ShengtingAuthProtocol.buildPlaintext`, `ShengtingAuthProtocol.buildRequestJson`, `ShengtingAuthProtocol.parseToken`, `ShengtingAes.encrypt`.
- Produces: `ShengtingAuthManager.isEnabled(): Boolean`
- Produces: `ShengtingAuthManager.requireToken(forceRefresh: Boolean = false, callback: (ShengtingAuthResult) -> Unit): ShengtingAuthCall`
- Produces: `ShengtingAuthManager.clear()`
- Produces: `ShengtingAuthResult.Success(token: String)` and `ShengtingAuthResult.Failure(message: String, cause: Throwable? = null)`.

- [ ] **Step 1: Write manager tests with a fake gateway**

Create `ShengtingAuthManagerTest.kt`:

```kotlin
package com.rokid.glass.network

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShengtingAuthManagerTest {
    @Test
    fun requireToken_returnsBlankSuccessWhenDisabled() {
        val gateway = FakeGateway(listOf(Result.success("token-1")))
        val manager = newManager(enabled = false, gateway = gateway)
        var result: ShengtingAuthResult? = null

        manager.requireToken { result = it }

        assertEquals(0, gateway.calls)
        assertEquals("", (result as ShengtingAuthResult.Success).token)
    }

    @Test
    fun requireToken_fetchesAndCachesTokenWhenEnabled() {
        val gateway = FakeGateway(listOf(Result.success("token-1")))
        val manager = newManager(enabled = true, gateway = gateway)
        val results = mutableListOf<ShengtingAuthResult>()

        manager.requireToken { results += it }
        manager.requireToken { results += it }

        assertEquals(1, gateway.calls)
        assertEquals("token-1", (results[0] as ShengtingAuthResult.Success).token)
        assertEquals("token-1", (results[1] as ShengtingAuthResult.Success).token)
    }

    @Test
    fun requireToken_retriesOnceAfterFailure() {
        val gateway = FakeGateway(
            listOf(
                Result.failure(IllegalStateException("first failed")),
                Result.success("token-2"),
            ),
        )
        val manager = newManager(enabled = true, gateway = gateway)
        var result: ShengtingAuthResult? = null

        manager.requireToken { result = it }

        assertEquals(2, gateway.calls)
        assertEquals("token-2", (result as ShengtingAuthResult.Success).token)
    }

    @Test
    fun requireToken_failsAfterTwoFailures() {
        val gateway = FakeGateway(
            listOf(
                Result.failure(IllegalStateException("first failed")),
                Result.failure(IllegalStateException("second failed")),
            ),
        )
        val manager = newManager(enabled = true, gateway = gateway)
        var result: ShengtingAuthResult? = null

        manager.requireToken { result = it }

        assertEquals(2, gateway.calls)
        assertTrue(result is ShengtingAuthResult.Failure)
    }

    @Test
    fun forceRefresh_ignoresCachedToken() {
        val gateway = FakeGateway(
            listOf(
                Result.success("token-1"),
                Result.success("token-2"),
            ),
        )
        val manager = newManager(enabled = true, gateway = gateway)
        val results = mutableListOf<ShengtingAuthResult>()

        manager.requireToken { results += it }
        manager.requireToken(forceRefresh = true) { results += it }

        assertEquals(2, gateway.calls)
        assertEquals("token-1", (results[0] as ShengtingAuthResult.Success).token)
        assertEquals("token-2", (results[1] as ShengtingAuthResult.Success).token)
    }

    @Test
    fun blankSnFailsWithoutCallingGateway() {
        val gateway = FakeGateway(listOf(Result.success("token-1")))
        val manager = ShengtingAuthManager(
            enabledProvider = { true },
            snCodeProvider = { "" },
            dateProvider = { LocalDate.of(2026, 7, 9) },
            gateway = gateway,
        )
        var result: ShengtingAuthResult? = null

        manager.requireToken { result = it }

        assertEquals(0, gateway.calls)
        assertTrue(result is ShengtingAuthResult.Failure)
    }

    private fun newManager(
        enabled: Boolean,
        gateway: FakeGateway,
    ): ShengtingAuthManager {
        return ShengtingAuthManager(
            enabledProvider = { enabled },
            snCodeProvider = { "GLASS_SN_001" },
            dateProvider = { LocalDate.of(2026, 7, 9) },
            gateway = gateway,
        )
    }

    private class FakeGateway(
        private val results: List<Result<String>>,
    ) : ShengtingAuthGateway {
        var calls = 0
            private set

        override fun requestToken(
            snCode: String,
            date: LocalDate,
            callback: (Result<String>) -> Unit,
        ): ShengtingAuthCall {
            val result = results.getOrElse(calls) { results.last() }
            calls += 1
            callback(result)
            return ShengtingAuthCall { }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.ShengtingAuthManagerTest"`

Expected: FAIL with unresolved references to manager/gateway/result types.

- [ ] **Step 3: Add service implementation**

Create `ShengtingAuthService.kt`:

```kotlin
package com.rokid.glass.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate

fun interface ShengtingAuthCall {
    fun cancel()
}

interface ShengtingAuthGateway {
    fun requestToken(
        snCode: String,
        date: LocalDate,
        callback: (Result<String>) -> Unit,
    ): ShengtingAuthCall
}

class ShengtingAuthService(
    private val client: OkHttpClient = HttpClientProvider.inspectionClient,
    private val authUrl: String = AUTH_URL,
) : ShengtingAuthGateway {
    override fun requestToken(
        snCode: String,
        date: LocalDate,
        callback: (Result<String>) -> Unit,
    ): ShengtingAuthCall {
        val plaintext = ShengtingAuthProtocol.buildPlaintext(snCode = snCode, date = date)
        val encrypted = ShengtingAes.encrypt(plaintext)
        val requestBody = ShengtingAuthProtocol.buildRequestJson(encrypted)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(authUrl)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback(Result.failure(IOException("shengting auth HTTP ${response.code}")))
                        return
                    }
                    callback(ShengtingAuthProtocol.parseToken(response.body?.string().orEmpty()))
                }
            }
        })
        return ShengtingAuthCall { call.cancel() }
    }

    companion object {
        const val AUTH_URL = "https://jcyxar.yjt.zj.gov.cn:7443/glasses/apis/auth/check"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
```

- [ ] **Step 4: Add manager implementation**

Create `ShengtingAuthManager.kt`:

```kotlin
package com.rokid.glass.network

import com.rokid.glesse.BuildConfig
import com.rokid.glass.hiddenrisk.RokidSdkManager
import java.time.LocalDate

sealed class ShengtingAuthResult {
    data class Success(val token: String) : ShengtingAuthResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ShengtingAuthResult()
}

class ShengtingAuthManager(
    private val enabledProvider: () -> Boolean = { BuildConfig.FLAVOR == SHENGTING_FLAVOR },
    private val snCodeProvider: () -> String = { RokidSdkManager.getSerialNumber() },
    private val dateProvider: () -> LocalDate = { LocalDate.now() },
    private val gateway: ShengtingAuthGateway = ShengtingAuthService(),
) {
    private val lock = Any()
    private var cachedToken: String? = null
    private var inFlight = false
    private val pendingCallbacks = mutableListOf<(ShengtingAuthResult) -> Unit>()
    private var activeCall: ShengtingAuthCall? = null

    fun isEnabled(): Boolean = enabledProvider()

    fun clear() {
        synchronized(lock) {
            cachedToken = null
            activeCall?.cancel()
            activeCall = null
            inFlight = false
            pendingCallbacks.clear()
        }
    }

    fun requireToken(
        forceRefresh: Boolean = false,
        callback: (ShengtingAuthResult) -> Unit,
    ): ShengtingAuthCall {
        if (!isEnabled()) {
            callback(ShengtingAuthResult.Success(""))
            return ShengtingAuthCall { }
        }
        synchronized(lock) {
            val token = cachedToken
            if (!forceRefresh && !token.isNullOrBlank()) {
                callback(ShengtingAuthResult.Success(token))
                return ShengtingAuthCall { }
            }
            pendingCallbacks += callback
            if (inFlight) {
                return ShengtingAuthCall { }
            }
            inFlight = true
        }
        startRequest(remainingRetries = 1)
        return ShengtingAuthCall { }
    }

    private fun startRequest(remainingRetries: Int) {
        val snCode = snCodeProvider().trim()
        if (snCode.isBlank()) {
            deliver(ShengtingAuthResult.Failure("Rokid SN is blank"))
            return
        }
        activeCall = gateway.requestToken(
            snCode = snCode,
            date = dateProvider(),
        ) { result ->
            result
                .onSuccess { token ->
                    synchronized(lock) { cachedToken = token }
                    deliver(ShengtingAuthResult.Success(token))
                }
                .onFailure { error ->
                    if (remainingRetries > 0) {
                        startRequest(remainingRetries - 1)
                    } else {
                        deliver(ShengtingAuthResult.Failure("shengting auth failed", error))
                    }
                }
        }
    }

    private fun deliver(result: ShengtingAuthResult) {
        val callbacks = synchronized(lock) {
            inFlight = false
            activeCall = null
            val snapshot = pendingCallbacks.toList()
            pendingCallbacks.clear()
            snapshot
        }
        callbacks.forEach { it(result) }
    }

    companion object {
        const val SHENGTING_FLAVOR = "shengting"
    }
}
```

- [ ] **Step 5: Run manager test**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.ShengtingAuthManagerTest"`

Expected: PASS.

- [ ] **Step 6: Add singleton provider for app use**

At the end of `ShengtingAuthManager.kt`, add:

```kotlin
object ShengtingAuth {
    val manager: ShengtingAuthManager by lazy { ShengtingAuthManager() }
}
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/glass/network/ShengtingAuthService.kt app/src/main/java/com/rokid/glass/network/ShengtingAuthManager.kt app/src/test/java/com/rokid/glass/network/ShengtingAuthManagerTest.kt
git commit -m "feat: add shengting token manager"
```

---

### Task 4: Add Blocking Auth Failure UI Helper

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/BaseGlassActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `protected fun showShengtingAuthFailureAndReturnToMain()`
- Produces: `protected fun isShengtingAuthFailureDialogVisible(): Boolean`
- Produces: `protected open fun onShengtingAuthFailureDialogVisibilityChanged(visible: Boolean)`

- [ ] **Step 1: Keep this UI helper build-verified, not Robolectric-tested**

Do not add Robolectric or a new Android framework test dependency for this small page helper. The helper touches `window.decorView`, navigation, and Android widgets, so verification for this task is `compileStandardDebugKotlin`; end-to-end behavior is covered by the shengting manual checks in Task 8.

- [ ] **Step 2: Add strings**

Add to `strings.xml`:

```xml
<string name="shengting_auth_failure_message">身份鉴权失败，请检查网络或联系管理员</string>
<string name="shengting_auth_failure_confirm">确认</string>
```

- [ ] **Step 3: Add helper fields and methods to BaseGlassActivity**

In `BaseGlassActivity`, add imports:

```kotlin
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glesse.R
import com.rokid.glass.MainMenuActivity
import com.rokid.glass.network.ShengtingAuth
import com.rokid.glass.workflow.InspectionWorkflowSession
```

Add fields:

```kotlin
private var shengtingAuthFailureOverlay: View? = null
private var shengtingAuthFailureVisible = false
```

Add methods:

```kotlin
protected fun isShengtingAuthFailureDialogVisible(): Boolean = shengtingAuthFailureVisible

protected open fun onShengtingAuthFailureDialogVisibilityChanged(visible: Boolean) = Unit

protected fun showShengtingAuthFailureAndReturnToMain() {
    if (shengtingAuthFailureVisible) return
    shengtingAuthFailureVisible = true
    val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)
    val overlay = buildShengtingAuthFailureOverlay()
    shengtingAuthFailureOverlay = overlay
    root.addView(overlay)
    onShengtingAuthFailureDialogVisibilityChanged(true)
}

private fun buildShengtingAuthFailureOverlay(): View {
    return FrameLayout(this).apply {
        setBackgroundColor(Color.argb(210, 0, 0, 0))
        isClickable = true
        isFocusable = true
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 20, 24, 20)
            background = getDrawable(R.drawable.glass_card_outline)
            addView(TextView(context).apply {
                text = getString(R.string.shengting_auth_failure_message)
                setTextColor(getColor(R.color.green))
                textSize = 15f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = getString(R.string.shengting_auth_failure_confirm)
                setTextColor(getColor(R.color.green))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 14, 0, 0)
            })
        }
        addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = 24
                rightMargin = 24
            },
        )
        setOnClickListener { confirmShengtingAuthFailureDialog() }
    }
}

protected fun confirmShengtingAuthFailureDialog() {
    if (!shengtingAuthFailureVisible) return
    shengtingAuthFailureVisible = false
    shengtingAuthFailureOverlay?.let { overlay ->
        (overlay.parent as? FrameLayout)?.removeView(overlay)
    }
    shengtingAuthFailureOverlay = null
    onShengtingAuthFailureDialogVisibilityChanged(false)
    InspectionWorkflowSession.clearEnterpriseData()
    ShengtingAuth.manager.clear()
    startActivity(Intent(this, MainMenuActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    })
    finish()
}
```

- [ ] **Step 4: Let click/back confirm while dialog is visible**

At the top of `BaseGlassActivity.onGlassKeyEvent`, add:

```kotlin
if (shengtingAuthFailureVisible) {
    if (keyEvent == GlassKeyEvent.KEYCODE_CLICK ||
        keyEvent == GlassKeyEvent.KEYCODE_BACK ||
        keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK
    ) {
        confirmShengtingAuthFailureDialog()
        return true
    }
    return true
}
```

- [ ] **Step 5: Build**

Run: `./gradlew :app:compileStandardDebugKotlin`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/BaseGlassActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat: add shengting auth failure dialog"
```

---

### Task 5: Gate Enterprise QR Success On shengting Auth

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`

**Interfaces:**
- Consumes: `ShengtingAuth.manager.requireToken(callback)`
- Consumes: `showShengtingAuthFailureAndReturnToMain()`
- Produces: QR success proceeds to `EnterpriseInfoActivity` only after shengting token success.

- [ ] **Step 1: Add imports**

```kotlin
import com.rokid.glass.network.ShengtingAuth
import com.rokid.glass.network.ShengtingAuthResult
```

- [ ] **Step 2: Add an auth-in-flight flag**

Near `objectMessageRequest`, add:

```kotlin
private var shengtingAuthRequest: com.rokid.glass.network.ShengtingAuthCall? = null
```

- [ ] **Step 3: Replace direct navigation after object success**

In `onSuccess(data)`, replace:

```kotlin
Log.i(TAG, "enterprise object fetch navigate requestId=$objectFetchRequestId target=EnterpriseInfoActivity")
navigateToEnterpriseInfo()
```

with:

```kotlin
Log.i(TAG, "enterprise object fetch success requestId=$objectFetchRequestId shengtingAuthEnabled=${ShengtingAuth.manager.isEnabled()}")
continueAfterEnterpriseObjectFetch(objectFetchRequestId)
```

- [ ] **Step 4: Add helper method**

Add this method in `EnterpriseQrScanActivity`:

```kotlin
private fun continueAfterEnterpriseObjectFetch(requestId: String) {
    if (!ShengtingAuth.manager.isEnabled()) {
        Log.i(TAG, "enterprise object fetch navigate requestId=$requestId target=EnterpriseInfoActivity auth=disabled")
        navigateToEnterpriseInfo()
        return
    }
    tvStatus.visibility = View.VISIBLE
    tvStatus.text = getString(R.string.enterprise_qr_object_fetch_loading)
    shengtingAuthRequest?.cancel()
    shengtingAuthRequest = ShengtingAuth.manager.requireToken { result ->
        runOnUiThread {
            if (destroyed || isFinishing) return@runOnUiThread
            shengtingAuthRequest = null
            when (result) {
                is ShengtingAuthResult.Success -> {
                    Log.i(TAG, "shengting auth success after enterprise object requestId=$requestId tokenBlank=${result.token.isBlank()}")
                    navigateToEnterpriseInfo()
                }
                is ShengtingAuthResult.Failure -> {
                    Log.w(TAG, "shengting auth failed after enterprise object requestId=$requestId message=${result.message}")
                    showShengtingAuthFailureAndReturnToMain()
                }
            }
        }
    }
}
```

- [ ] **Step 5: Cancel auth in cleanup**

In `onDestroy` or existing cleanup section where `objectMessageRequest?.cancel()` is called, add:

```kotlin
shengtingAuthRequest?.cancel()
shengtingAuthRequest = null
```

- [ ] **Step 6: Ensure input is blocked while auth dialog visible**

In `isPrimaryActionEnabled()`, add the dialog guard:

```kotlin
if (isShengtingAuthFailureDialogVisible()) {
    return false
}
```

Override visibility callback:

```kotlin
override fun onShengtingAuthFailureDialogVisibilityChanged(visible: Boolean) {
    refreshInputActions()
}
```

- [ ] **Step 7: Compile**

Run: `./gradlew :app:compileShengtingDebugKotlin`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt
git commit -m "feat: require shengting auth after enterprise lookup"
```

---

### Task 6: Authorize AI Requests And Refresh On 401 Or 403

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/AiArSseServiceRequestPayloadTest.kt`

**Interfaces:**
- Consumes: `ShengtingAuth.manager.requireToken(forceRefresh, callback)`.
- Produces: `AiArSseService.FATAL_AUTH_FAILURE_MESSAGE`.
- Produces: `internal fun Request.Builder.applyShengtingAuthorization(token: String?): Request.Builder`.
- Produces: `internal fun isShengtingAuthExpired(responseCode: Int): Boolean`.

- [ ] **Step 1: Add helper tests**

Append to `AiArSseServiceRequestPayloadTest.kt`:

```kotlin
@Test
fun applyShengtingAuthorization_addsHeaderWhenTokenPresent() {
    val request = okhttp3.Request.Builder()
        .url("http://example.test/ai/auto")
        .applyShengtingAuthorization("token-value")
        .build()

    assertEquals("token-value", request.header("Authorization"))
}

@Test
fun applyShengtingAuthorization_omitsHeaderWhenTokenBlank() {
    val request = okhttp3.Request.Builder()
        .url("http://example.test/ai/auto")
        .applyShengtingAuthorization(" ")
        .build()

    assertNull(request.header("Authorization"))
}

@Test
fun isShengtingAuthExpired_onlyMatches401And403() {
    assertTrue(AiArSseService.isShengtingAuthExpired(401))
    assertTrue(AiArSseService.isShengtingAuthExpired(403))
    assertFalse(AiArSseService.isShengtingAuthExpired(500))
}
```

- [ ] **Step 2: Run tests to verify helper references fail**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.AiArSseServiceRequestPayloadTest"`

Expected: FAIL with unresolved helper references.

- [ ] **Step 3: Add imports and constants**

In `AiArSseService.kt`, add imports:

```kotlin
import com.rokid.glass.network.ShengtingAuth
import com.rokid.glass.network.ShengtingAuthResult
```

In companion object, add:

```kotlin
const val FATAL_AUTH_FAILURE_MESSAGE = "SHENGTING_AUTH_FAILURE"
```

- [ ] **Step 4: Add request helper functions**

Add near other internal helpers:

```kotlin
internal fun Request.Builder.applyShengtingAuthorization(token: String?): Request.Builder {
    if (!token.isNullOrBlank()) {
        header("Authorization", token)
    }
    return this
}
```

In companion object:

```kotlin
internal fun isShengtingAuthExpired(responseCode: Int): Boolean {
    return responseCode == 401 || responseCode == 403
}
```

- [ ] **Step 5: Add token wrapper**

Inside `AiArSseService`, add:

```kotlin
private fun withShengtingToken(
    forceRefresh: Boolean = false,
    onFailure: (String) -> Unit,
    onToken: (String?) -> Unit,
) {
    if (!ShengtingAuth.manager.isEnabled()) {
        onToken(null)
        return
    }
    ShengtingAuth.manager.requireToken(forceRefresh = forceRefresh) { result ->
        when (result) {
            is ShengtingAuthResult.Success -> onToken(result.token)
            is ShengtingAuthResult.Failure -> onFailure(FATAL_AUTH_FAILURE_MESSAGE)
        }
    }
}
```

- [ ] **Step 6: Wrap JSON request starters**

For `requestHazardDetection`, move the existing request creation and `client.newCall(request).enqueue(...)` body into a local function:

```kotlin
fun startRequest(token: String?, retriedAfterAuthFailure: Boolean) {
    val request = Request.Builder()
        .url(url)
        .applyShengtingAuthorization(token)
        .tag(RequestTimingTag::class.java, RequestTimingTag(taskId, lane, requestStartedElapsedMs))
        .post(requestBody)
        .build()
    // keep existing enqueue body
}
```

Before creating the request, call:

```kotlin
withShengtingToken(
    onFailure = { message ->
        mainHandler.post {
            if (!handle.isCanceled()) callback.onFailure(handle, message)
        }
    },
) { token ->
    startRequest(token = token, retriedAfterAuthFailure = false)
}
```

In the non-success response branch, before delivering failure, add:

```kotlin
if (ShengtingAuth.manager.isEnabled() && isShengtingAuthExpired(response.code) && !retriedAfterAuthFailure) {
    response.close()
    withShengtingToken(
        forceRefresh = true,
        onFailure = { message ->
            mainHandler.post {
                if (!handle.isCanceled()) callback.onFailure(handle, message)
            }
        },
    ) { refreshedToken ->
        startRequest(token = refreshedToken, retriedAfterAuthFailure = true)
    }
    return
}
```

- [ ] **Step 7: Apply the same wrapper pattern to device and suggestion JSON calls**

Apply Step 6 to `fetchInspectionGuide` and `fetchSuggestionChecks`. Use each method's existing callback failure type:

```kotlin
callback.onFailure(handle, FATAL_AUTH_FAILURE_MESSAGE)
```

- [ ] **Step 8: Apply token and refresh logic to SSE openStream**

Change `openStream` to obtain a token before building the `Request`. The request builder must include:

```kotlin
.applyShengtingAuthorization(token)
.header("Accept", "text/event-stream")
```

In `EventSourceListener.onFailure`, if `response?.code` is `401` or `403` and this stream has not retried, refresh token and call `openStream` again with `retriedAfterAuthFailure = true`. Add a private parameter:

```kotlin
retriedAfterAuthFailure: Boolean = false
```

When refresh fails, call:

```kotlin
mainHandler.post {
    if (!handle.isCanceled()) {
        onFailure(FATAL_AUTH_FAILURE_MESSAGE)
    }
}
```

- [ ] **Step 9: Run tests and compile**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.AiArSseServiceRequestPayloadTest"
./gradlew :app:compileShengtingDebugKotlin
```

Expected: both PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt app/src/test/java/com/rokid/glass/hiddenrisk/AiArSseServiceRequestPayloadTest.kt
git commit -m "feat: authorize shengting ai requests"
```

---

### Task 7: Route Fatal AI Auth Failures To Dialog

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt`

**Interfaces:**
- Consumes: `AiArSseService.FATAL_AUTH_FAILURE_MESSAGE`.
- Consumes: `showShengtingAuthFailureAndReturnToMain()`.
- Produces: All direct AI pages display the blocking dialog on fatal auth failure.

- [ ] **Step 1: Add a helper to each page**

In each of the three Activities, add:

```kotlin
private fun handleAiServiceFailure(message: String): Boolean {
    if (message == AiArSseService.FATAL_AUTH_FAILURE_MESSAGE) {
        showShengtingAuthFailureAndReturnToMain()
        return true
    }
    return false
}
```

- [ ] **Step 2: Use helper in AiInspectionActivity callbacks**

In `AiInspectionActivity`, update every `AiArSseService` or `OnlineHazardDetectionService` failure callback that receives a `message: String` from the AI service:

```kotlin
if (handleAiServiceFailure(message)) return
```

Place the guard before existing toast/status/error handling. Target call sites include suggestion checks, manual deep analysis, and online detail callbacks found around current `fetchSuggestionChecks`, `requestDeepAnalysis`, and `onlineHazardDetectionService.requestDeepAnalysis` usages.

- [ ] **Step 3: Use helper in DeviceGuideActivity callbacks**

In `DeviceGuideActivity`, update failures from `identifyItemHazard` and `fetchInspectionGuide`:

```kotlin
if (handleAiServiceFailure(message)) return
```

- [ ] **Step 4: Use helper in HazardRecordActivity callbacks**

In `HazardRecordActivity`, update the `requestDeepAnalysis` failure callback:

```kotlin
if (handleAiServiceFailure(message)) return
```

- [ ] **Step 5: Refresh input actions when dialog appears**

All three pages already have `private fun refreshInputActions()`. In `AiInspectionActivity`, `DeviceGuideActivity`, and `HazardRecordActivity`, override:

```kotlin
override fun onShengtingAuthFailureDialogVisibilityChanged(visible: Boolean) {
    refreshInputActions()
}
```

Do not create a second `UnifiedInputSession`.

- [ ] **Step 6: Block normal input when dialog is visible**

In each page's input action enabled predicate or top-level input handler, add:

```kotlin
if (isShengtingAuthFailureDialogVisible()) return false
```

For methods that return `Boolean` from a key/input handler, use:

```kotlin
if (isShengtingAuthFailureDialogVisible()) return super.onGlassKeyEvent(keyEvent)
```

- [ ] **Step 7: Compile**

Run: `./gradlew :app:compileShengtingDebugKotlin`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt
git commit -m "feat: handle shengting ai auth failures"
```

---

### Task 8: Final Verification And Cleanup

**Files:**
- Verify all touched files.
- Do not add `scripts/java/` unless the user explicitly asks to track those reference files.

**Interfaces:**
- Consumes: All previous task outputs.
- Produces: Verified shengting flavor implementation.

- [ ] **Step 1: Run focused unit tests**

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.ShengtingAesTest"
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.ShengtingAuthProtocolTest"
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.ShengtingAuthManagerTest"
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.AiArSseServiceRequestPayloadTest"
```

Expected: all PASS.

- [ ] **Step 2: Run flavor build checks**

```bash
./gradlew :app:testShengtingDebugUnitTest
./gradlew :app:assembleShengtingDebug
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:assembleStandardDebug
```

Expected: all PASS.

- [ ] **Step 3: Search for accidental broad migration**

Run:

```bash
rg -n "jcyxar|glasses/apis/proxy|Authorization|ShengtingAuth|183\\.147\\.142\\.133" app/src/main/java app/src/main/assets app/src/test
```

Expected:

- `jcyxar` appears only in shengting AI config and auth service.
- `Authorization` appears in AI request authorization code and existing unrelated code.
- `183.147.142.133` remains in base config, dataBackup backup config, updater defaults, tests, and non-scope endpoints.

- [ ] **Step 4: Inspect git status**

Run: `git status --short`

Expected: only intentional implementation files are modified. `?? scripts/java/` may still appear because those reference files were pre-existing untracked files; do not add them.

- [ ] **Step 5: Commit final verification fixes only when Step 1-4 changed source files**

When Step 1-4 required small fixes, commit them:

```bash
git add app/build.gradle app/src/main app/src/test
git commit -m "test: verify shengting auth flow"
```

When Step 1-4 passed without source changes, leave the git history at the task commits from Task 1-7.
