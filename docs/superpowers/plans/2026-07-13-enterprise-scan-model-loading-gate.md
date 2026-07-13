# Enterprise Scan Model Loading Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `InspectionLoadingActivity` the only production entry that loads the NCNN model, after enterprise information confirmation and before the AI secondary menu.

**Architecture:** Add a pure Kotlin `InspectionModelLoadPolicy` that decides whether the current configuration needs a local model and whether the session is ready. Navigation pages consume the policy; only `InspectionLoadingActivity` calls `InspectionSession.ensureModelLoaded()`.

**Tech Stack:** Kotlin, Android Activities, JUnit 4, Gradle `localTrigerDebug`, NCNN through `InspectionSession`.

## Global Constraints

- The production business flow may load the model only from `InspectionLoadingActivity`.
- App startup, `MainMenuActivity`, `AiInspectionMenuActivity`, and `AiInspectionActivity` must not call `InspectionSession.ensureModelLoaded()`.
- `HiddenRiskProbeActivity` remains an independent diagnostic exception.
- Enterprise QR parsing, NCNN assets, Vulkan settings, thresholds, and `/ai/deep` behavior remain unchanged.

---

### Task 1: Add the model loading policy

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionModelLoadPolicy.kt`
- Test: `app/src/test/java/com/rokid/glass/hiddenrisk/InspectionModelLoadPolicyTest.kt`

**Interfaces:**
- Consumes: `AiInspectionConfig`, `AutoDetectProvider`, `AutoInferenceMode`, `AutoHazardRoutingMode`.
- Produces: `InspectionModelLoadPolicy.requiresModel(config)` and `InspectionModelLoadPolicy.isSessionReady(config, isInitialized, isModelLoaded)`.

- [ ] **Step 1: Write failing tests** covering `LOCAL_TRIGGER`, both `LOCAL_ONLY` modes, local fallback, no-local configuration, and initialized-without-required-model readiness.
- [ ] **Step 2: Run** `gradlew.bat :app:testLocalTrigerDebugUnitTest --tests "com.rokid.glass.hiddenrisk.InspectionModelLoadPolicyTest" --no-daemon --console=plain` and verify compilation fails because the policy does not exist.
- [ ] **Step 3: Implement the minimal pure Kotlin policy** with OR semantics for the four model requirements and readiness requiring both initialization and model availability when needed.
- [ ] **Step 4: Re-run the focused test** and verify it passes.

### Task 2: Move navigation behind the loading gate

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/EnterpriseInfoActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`

**Interfaces:**
- Consumes: `InspectionModelLoadPolicy.isSessionReady(...)` and `InspectionLoadingActivity.EXTRA_NEXT_HOME_ACTIVITY`.
- Produces: enterprise confirmation route `EnterpriseInfoActivity -> InspectionLoadingActivity -> AiInspectionMenuActivity`.

- [ ] **Step 1: Change enterprise confirmation** to start `InspectionLoadingActivity` with `AiInspectionMenuActivity` as the next home activity.
- [ ] **Step 2: Add the secondary-menu guard** after enterprise data validation: if the policy reports not ready, route to `InspectionLoadingActivity` once and return.
- [ ] **Step 3: Remove the secondary-menu `preloadInspectionSession()` call, implementation, and now-unused imports.**
- [ ] **Step 4: Compile** `:app:compileLocalTrigerDebugKotlin` and verify the navigation changes compile.

### Task 3: Make the loading page the only production loader

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/MainMenuActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`

**Interfaces:**
- Consumes: `InspectionModelLoadPolicy` and `InspectionSession.isModelLoaded`.
- Produces: no production `ensureModelLoaded()` caller outside `InspectionLoadingActivity`.

- [ ] **Step 1: Remove app-home preload** from `MainMenuActivity`, including imports, invocation, and method.
- [ ] **Step 2: Update loading-page policy** to call `InspectionModelLoadPolicy.requiresModel()` so `LOCAL_TRIGGER` blocks until model load succeeds.
- [ ] **Step 3: Replace inline inspection loading** with a redirect to `InspectionLoadingActivity`; do not load inside `AiInspectionActivity`.
- [ ] **Step 4: Replace fallback loading** with a readiness check. If the preloaded model is missing, log the invariant violation and redirect to the loading page.
- [ ] **Step 5: Remove obsolete loading executor state/imports** from `InspectionLoadingActivity` if no longer used.
- [ ] **Step 6: Search** `ensureModelLoaded(` and verify the only production Activity caller is `InspectionLoadingActivity`.

### Task 4: Build and real-device verification

**Files:**
- Verify only; no planned production edits.

**Interfaces:**
- Consumes: `localTrigerDebug` APK and device `1901092544019017`.
- Produces: build and log evidence for the loading gate.

- [ ] **Step 1: Run the focused policy test** and existing local inference lifecycle tests.
- [ ] **Step 2: Compile and assemble** `localTrigerDebug`.
- [ ] **Step 3: Install** `app/build/outputs/apk/localTriger/debug/app-localTriger-debug.apk` on device `1901092544019017`.
- [ ] **Step 4: Clear logcat and launch the app.** Verify no `preload local NCNN model start` or native `loadModel` appears before enterprise confirmation.
- [ ] **Step 5: Reproduce enterprise scan and confirmation.** Verify `InspectionLoadingActivity` starts, model load succeeds, and only then `AiInspectionMenuActivity` resumes.
- [ ] **Step 6: Enter real-time analysis.** Verify the first accepted local request has low `queueWaitMs` and no startup model-load timeout.
