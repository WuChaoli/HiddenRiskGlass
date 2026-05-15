---
name: rokid-glass-sdk
description: Work with Rokid Glass3 SDK integration, changelog-based API auditing, and SDK upgrade tasks in this repository. Use when Codex needs to inspect or modify `com.rokid.security:glass3.open.sdk`, map official Rokid changelog entries to local code, add newly introduced services or listeners, or verify whether repo code matches a target Rokid SDK version.
---

# Rokid Glass SDK

Use the official Rokid changelog as the primary source of truth, then map the version diff onto this repo before editing code.

## Workflow

1. Confirm the target SDK version and exact changelog slice.
   For the changelog section the user linked, read [references/changelog-v2.1.2.md](references/changelog-v2.1.2.md).
   If the user asks for the latest Rokid SDK change, re-open the official changelog instead of assuming the reference file is current.

2. Confirm the repo's current dependency baseline.
   Read `app/build.gradle`.
   This repo currently pins `implementation ('com.rokid.security:glass3.open.sdk:2.1.5-E')`.

3. Map affected APIs to local call sites before editing.
   Start with:
   - `app/src/main/java/com/rokid/glass/utils/GlassSdkUtils.kt`
   - `app/src/main/java/com/rokid/glass/TestMediaActivity.kt`
   - `app/src/main/java/com/rokid/glass/GlassLprTrackActivity.kt`
   - `app/src/main/java/com/rokid/glass/SendMessageActivity.kt`
   - `app/src/main/java/com/rokid/glass/MessageReceiveActivity.kt`
   - `app/src/main/java/com/rokid/glass/hiddenrisk/RokidSdkManager.kt`

4. Search for API drift with targeted patterns instead of broad file reads.

```bash
rg -n "com\\.rokid\\.security|GlassSdk|getGlass.*Service|ICommonInfoListener|PreviewResolution|IMediaStateLister|ICollectService|IOfflineTtsService|IOfflineRecServer" app/src/main/java app/build.gradle
```

5. Update code conservatively.
   When the SDK adds methods to AIDL-style listeners or interfaces, update all affected `Stub()` implementations so the code compiles cleanly.
   Preserve existing service initialization flow around `GlassSdk.bindSecurityService(...)` and `GlassSdk.registerClient(...)`.

6. Validate the integration at the narrowest useful level.
   For source compatibility, build with Gradle.
   For behavior changes, verify the concrete feature path touched by the changelog item, such as media state listening, collect service access, offline TTS, or recognition result submission.

## Repo Notes

- This repo is glass-side, not phone-side only. Treat phone-side changelog entries as secondary unless the task explicitly spans a companion app.
- `GlassSdkUtils.kt` is the central service wiring point for Bluetooth, P2P, message, ring, common-info, and device services.
- `TestMediaActivity.kt` already uses `IMediaStateLister`.
- `GlassLprTrackActivity.kt` already uses `PreviewResolution.ResolutionInfo_1080P_Land`, which is documented in a later changelog slice than `v2.1.2`.
- `GlassSdkUtils.kt` already implements `ICommonInfoListener.onConfig(...)`, which aligns with the later `v2.1.7-E` changelog.

## Version Rules

- Do not assume the official SDK surface from memory. Re-check the Rokid docs when the request is version-sensitive.
- Distinguish the user-linked changelog slice from the latest changelog slice.
  The linked anchor `#眼镜端接口变更-3` resolves to `v2.1.2 (2025-12-15)`, not `v2.1.7-E (2026-04-07)`.
- When upgrading dependency versions, compare:
  - Gradle dependency version
  - Recommended OTA version from docs
  - Any new listener or service methods that force code changes

## References

- Read [references/changelog-v2.1.2.md](references/changelog-v2.1.2.md) when the task involves the user-linked changelog anchor or the `v2.1.2` API additions.
