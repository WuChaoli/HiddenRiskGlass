---
name: android-apk-packaging
description: Rebuild, sign, and stage Android APK outputs for local Android projects. Use when Codex needs to package an APK, rerun Gradle assemble tasks, handle unsigned `release` outputs, apply local debug-keystore signing for device install or demo delivery, or copy the final APK into a requested `release` path with a specific filename.
---

# Android APK Packaging

Use this skill for narrow Android APK packaging work: inspect the project's Gradle setup, build the requested variant, confirm whether the output is signed, optionally add local signing for installable demo packages, and place the final APK at the path the user asked for.

## Workflow

1. Read the Android project before building.

- Inspect `app/build.gradle`, `settings.gradle`, `gradle.properties`, `local.properties`, and any project `AGENTS.md`.
- Confirm the module name, variant, output path, and whether `release` already has a `signingConfig`.
- Check whether the user asked for a formal release-signed package or only an installable local/demo package.

2. Choose the build task from project facts.

- Prefer the exact task that matches the project, such as `:app:assembleRelease` or a variant-specific task.
- Do not assume `assembleDebug` or `compileDebugKotlin` is the right target without reading the project.
- If the project uses flavors or custom build types, use the concrete task implied by the actual Gradle files.

3. Run the build and identify the real APK output.

- Build first, then enumerate `app/build/outputs/apk` to locate the real generated file.
- Do not assume the output is named `app-release.apk`; many projects emit `app-release-unsigned.apk` or variant-specific names.
- When the user wants a renamed artifact, copy the generated APK to the requested destination instead of renaming the original build output in place.

4. Handle signing based on the user's actual need.

- If the project already produces a signed APK, use that file directly.
- If `release` is unsigned and the user only needs a local installable or demo package, it is acceptable to sign a copied APK with the local debug keystore.
- If the user needs a durable delivery package, stable upgrade path, or formal distribution artifact, stop and ask for the project keystore instead of silently using debug signing.

5. Stage and verify the final artifact.

- Copy the final APK into the requested `release` directory and filename.
- Verify the file exists and record size and timestamp.
- If the user asked to install it, install the signed APK to the connected device and report install success or the exact failure.

## Signing Policy

- Treat `app-release-unsigned.apk` as expected behavior unless the project explicitly configures `signingConfig`.
- Prefer project-managed signing in Gradle when the team wants all future `release` builds to be signed by default.
- Use `~/.android/debug.keystore` only as a local fallback for demo or device-install scenarios.
- State clearly when a package is debug-signed rather than formally release-signed.

## Common Failure Modes

- `INSTALL_PARSE_FAILED_NO_CERTIFICATES`: the APK is unsigned; sign a copied APK before installing.
- `adb server version ... doesn't match this client`: another bundled `adb` is occupying port `5037`; stop the conflicting process and retry with the intended `adb`.
- Gradle output path mismatch: enumerate the actual files under `app/build/outputs/apk` instead of guessing the filename.
- Wrapper download blocked: if `gradlew` tries to download Gradle and the environment blocks network access, report that verification is blocked by the environment rather than claiming the packaging path is proven.

## Script

- Use `scripts/package-android-apk.ps1` when a deterministic local packaging flow is helpful.
- The script builds a chosen Gradle task, locates the newest APK, optionally debug-signs a copied output, and stages it at the destination path.
- Read the script before patching it for project-specific behavior.

## Output Discipline

- Report the exact build task used.
- Report whether the final APK is project-signed, unsigned, or debug-signed.
- Report the final staged path.
- If verification was blocked by sandbox, network, or device state, say so explicitly.
