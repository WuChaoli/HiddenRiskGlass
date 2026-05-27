# AI Menu Entry Guards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `AiInspectionMenuActivity` the app entry page, run WiFi/camera/enterprise checks every time it is entered, and remove the WiFi QR scan pages.

**Architecture:** `AiInspectionMenuActivity` becomes the only startup dispatcher for business flow readiness. `InspectionLoadingActivity` only initializes SDK/camera/model and returns to the menu; enterprise binding remains in `EnterpriseQrScanActivity` and `EnterpriseInfoActivity`.

**Tech Stack:** Kotlin Android Activity flow, XML layouts, existing `UnifiedInputSession`, existing `SystemStateUtils`, existing `InspectionSession`, existing `InspectionWorkflowSession`, Gradle Android build scripts.

---

## File Map

- Modify `app/src/main/AndroidManifest.xml`: move `MAIN/LAUNCHER` from `InspectionLoadingActivity` to `AiInspectionMenuActivity`; remove `WifiQrScanActivity`.
- Modify `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`: add entry guard state machine, WiFi modal, loading redirect, enterprise redirect, and guarded input behavior.
- Modify `app/src/main/res/layout/activity_ai_inspection_menu.xml`: add centered WiFi required overlay with confirm button.
- Modify `app/src/main/res/values/strings.xml`: add WiFi required strings and remove WiFi scan/menu strings that become unused.
- Modify `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`: remove WiFi scan routing and make completion return to menu by default.
- Modify `app/src/main/java/com/rokid/glass/HomeActivity.kt`: remove old WiFi scan menu references if the old page remains compiled.
- Delete `app/src/main/java/com/rokid/glass/WifiQrScanActivity.kt`.
- Delete `app/src/main/res/layout/activity_wifi_qr_scan.xml`.
- Consider deleting `app/src/main/java/com/rokid/glass/utils/WifiQrParser.kt` only after `rg "WifiQrParser|WifiQrPayload"` shows no references.
- Modify `app/build.gradle`: remove `com.rokid.security.glass3.qrcode:scanner:1.0.0` only if no remaining source references `GlassScanner` or `com.rokid.security.glass3.qrcode`.

## Task 1: Make `AiInspectionMenuActivity` the Launcher

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Move launcher intent filter**

In `app/src/main/AndroidManifest.xml`, set `AiInspectionMenuActivity` as exported launcher:

```xml
<activity
    android:name="com.rokid.glass.AiInspectionMenuActivity"
    android:exported="true"
    android:theme="@style/Theme.Glessedemo">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

Keep `InspectionLoadingActivity` exported but remove its launcher filter:

```xml
<activity
    android:name="com.rokid.glass.hiddenrisk.InspectionLoadingActivity"
    android:exported="true"
    android:theme="@style/Theme.Glessedemo" />
```

- [ ] **Step 2: Run manifest/source search**

Run:

```bash
rg -n "MAIN|LAUNCHER|AiInspectionMenuActivity|InspectionLoadingActivity" app/src/main/AndroidManifest.xml
```

Expected:

- `MAIN` and `LAUNCHER` appear only under `AiInspectionMenuActivity`.
- `InspectionLoadingActivity` still has an activity declaration.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: launch into ai inspection menu"
```

## Task 2: Add WiFi Required Overlay to Menu Layout

**Files:**
- Modify: `app/src/main/res/layout/activity_ai_inspection_menu.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

Add these strings to `app/src/main/res/values/strings.xml` near the `ai_entry_menu_*` strings:

```xml
<string name="ai_entry_wifi_required_message">请先连接wifi</string>
<string name="ai_entry_wifi_required_confirm">确定</string>
```

- [ ] **Step 2: Add overlay view**

Add this block as the last child of the root `FrameLayout` in `activity_ai_inspection_menu.xml`:

```xml
<LinearLayout
    android:id="@+id/layoutWifiRequiredDialog"
    android:layout_width="220dp"
    android:layout_height="wrap_content"
    android:layout_gravity="center"
    android:background="@drawable/glass_status_panel"
    android:gravity="center"
    android:orientation="vertical"
    android:paddingHorizontal="20dp"
    android:paddingVertical="18dp"
    android:visibility="gone">

    <TextView
        android:id="@+id/tvWifiRequiredMessage"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:text="@string/ai_entry_wifi_required_message"
        android:textColor="@color/green"
        android:textSize="18sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/tvWifiRequiredConfirm"
        android:layout_width="120dp"
        android:layout_height="42dp"
        android:layout_marginTop="16dp"
        android:background="@drawable/inspection_mode_item_bg_selected"
        android:gravity="center"
        android:text="@string/ai_entry_wifi_required_confirm"
        android:textColor="@color/green"
        android:textSize="16sp"
        android:textStyle="bold" />
</LinearLayout>
```

- [ ] **Step 3: Build resources**

Run:

```bash
bash scripts/android/doctor.sh
bash scripts/android/build-debug.sh
```

Expected: both commands complete successfully and resources compile.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_ai_inspection_menu.xml app/src/main/res/values/strings.xml
git commit -m "feat: add wifi required menu dialog"
```

## Task 3: Implement Menu Entry Guard Flow

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`

- [ ] **Step 1: Add imports**

Add imports:

```kotlin
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.workflow.InspectionWorkflowSession
```

- [ ] **Step 2: Add guard fields**

Add fields inside `AiInspectionMenuActivity`:

```kotlin
private lateinit var layoutWifiRequiredDialog: LinearLayout
private lateinit var tvWifiRequiredConfirm: TextView
private var entryGuardNavigating = false
private var wifiRequiredDialogVisible = false
```

- [ ] **Step 3: Bind overlay views**

In `onCreate()` after existing `findViewById` calls:

```kotlin
layoutWifiRequiredDialog = findViewById(R.id.layoutWifiRequiredDialog)
tvWifiRequiredConfirm = findViewById(R.id.tvWifiRequiredConfirm)
tvWifiRequiredConfirm.setOnClickListener { exitAppFromWifiDialog() }
```

- [ ] **Step 4: Run guard from `onResume()`**

Replace the existing `onResume()` body with:

```kotlin
override fun onResume() {
    super.onResume()
    entryGuardNavigating = false
    inputSession.attach()
    inputSession.updateActions(buildInputActions())
    updateBatteryLevel()
    runEntryGuards()
}
```

- [ ] **Step 5: Add guard methods**

Add these methods before `buildInputActions()`:

```kotlin
private fun runEntryGuards() {
    if (entryGuardNavigating || wifiRequiredDialogVisible) return

    if (SystemStateUtils.getCurrentWifiSsid(this) == null) {
        showWifiRequiredDialog()
        return
    }

    hideWifiRequiredDialog()

    if (!InspectionSession.isInitialized) {
        entryGuardNavigating = true
        startActivity(Intent(this, InspectionLoadingActivity::class.java).apply {
            putExtra(InspectionLoadingActivity.EXTRA_NEXT_HOME_ACTIVITY, AiInspectionMenuActivity::class.java.name)
        })
        return
    }

    if (
        InspectionWorkflowSession.enterpriseQrPayload == null ||
        InspectionWorkflowSession.enterpriseInfo == null
    ) {
        entryGuardNavigating = true
        startActivity(Intent(this, EnterpriseQrScanActivity::class.java))
        return
    }

    startAutoUpdateCheck()
}

private fun showWifiRequiredDialog() {
    wifiRequiredDialogVisible = true
    layoutWifiRequiredDialog.visibility = View.VISIBLE
    recyclerMenu.isEnabled = false
    tvBottomHint.visibility = View.GONE
    inputSession.updateActions(buildInputActions())
}

private fun hideWifiRequiredDialog() {
    wifiRequiredDialogVisible = false
    layoutWifiRequiredDialog.visibility = View.GONE
    recyclerMenu.isEnabled = true
    tvBottomHint.visibility = View.VISIBLE
}

private fun exitAppFromWifiDialog() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        finishAffinity()
        finishAndRemoveTask()
    } else {
        finishAffinity()
        finish()
    }
}
```

- [ ] **Step 6: Guard input actions**

At the top of `buildInputActions()`, return only confirm/exit behavior when the WiFi dialog is visible:

```kotlin
if (wifiRequiredDialogVisible) {
    return listOf(
        UnifiedInputSession.InputActionSpec(
            id = UnifiedInputSession.InputActionId.Confirm,
            label = getString(R.string.ai_entry_wifi_required_confirm),
            triggers = listOf(
                UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_wifi_required_confirm), "que ding"),
            ),
        ) {
            exitAppFromWifiDialog()
        },
    )
}
```

- [ ] **Step 7: Prevent manual feature entry during navigation**

At the top of `onItemConfirmed(index: Int)`:

```kotlin
if (entryGuardNavigating || wifiRequiredDialogVisible) return
```

- [ ] **Step 8: Run compile**

Run:

```bash
bash scripts/android/build-debug.sh
```

Expected: build succeeds.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt
git commit -m "feat: guard ai menu entry readiness"
```

## Task 4: Return Loading Completion to Menu

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`

- [ ] **Step 1: Remove WiFi pre-route from `onCreate()`**

Delete the `if (shouldRouteToWifiBeforeLoading()) { ... }` block from `onCreate()`.

- [ ] **Step 2: Remove `shouldRouteToWifiBeforeLoading()`**

Delete this method entirely:

```kotlin
private fun shouldRouteToWifiBeforeLoading(): Boolean {
    if (intent.getBooleanExtra(EXTRA_FORCE_LOADING_FLOW, false)) {
        return false
    }
    if (!InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled()) {
        return false
    }
    return SystemStateUtils.getCurrentWifiSsid(this) == null
}
```

- [ ] **Step 3: Replace `navigateToInspection()` target selection**

Replace the body of `navigateToInspection()` with:

```kotlin
private fun navigateToInspection() {
    InspectionWorkflowSession.beginInspection(
        InspectionBackendSessionId.create(RokidSdkManager.getSerialNumber(), prefix = "inspection"),
    )
    InspectionWorkflowSession.updateMode(SystemStateUtils.getCurrentWifiSsid(this) != null)
    val nextHomeClassName = intent.getStringExtra(EXTRA_NEXT_HOME_ACTIVITY)
    val nextHomeActivityClass = runCatching {
        nextHomeClassName?.takeIf { it.isNotBlank() }?.let { Class.forName(it) }
    }.getOrNull()
    startActivity(Intent(this, nextHomeActivityClass ?: AiInspectionMenuActivity::class.java))
    finish()
}
```

- [ ] **Step 4: Remove stale imports and constants**

Remove imports or references that become unused:

```kotlin
import com.rokid.glass.WifiQrScanActivity
import com.rokid.glass.InspectionFeatureFlags
```

Keep `EXTRA_FORCE_LOADING_FLOW` only if `rg "EXTRA_FORCE_LOADING_FLOW"` shows external references still need it during this task. If only the removed WiFi flow referenced it, delete the constant.

- [ ] **Step 5: Run source search**

Run:

```bash
rg -n "WifiQrScanActivity|EXTRA_FORCE_LOADING_FLOW|shouldRouteToWifiBeforeLoading" app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt
```

Expected: no matches, unless `EXTRA_FORCE_LOADING_FLOW` was intentionally kept for compatibility.

- [ ] **Step 6: Build**

Run:

```bash
bash scripts/android/build-debug.sh
```

Expected: build succeeds.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt
git commit -m "refactor: return loading flow to menu"
```

## Task 5: Remove WiFi Scan Menu Entries and Page

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/HomeActivity.kt`
- Modify: `app/src/main/res/layout/activity_inspection_mode.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/java/com/rokid/glass/WifiQrScanActivity.kt`
- Delete: `app/src/main/res/layout/activity_wifi_qr_scan.xml`
- Optional delete: `app/src/main/java/com/rokid/glass/utils/WifiQrParser.kt`
- Optional modify: `app/build.gradle`

- [ ] **Step 1: Remove `WifiQrScanActivity` manifest entry**

Delete this block from `AndroidManifest.xml`:

```xml
<activity
    android:name="com.rokid.glass.WifiQrScanActivity"
    android:exported="true"
    android:theme="@style/Theme.Glessedemo" />
```

- [ ] **Step 2: Remove old WiFi cards from layout**

In `activity_inspection_mode.xml`, delete the `FrameLayout` blocks with ids:

```xml
android:id="@+id/itemQrScan"
android:id="@+id/itemRokidWifiDebug"
```

- [ ] **Step 3: Remove old WiFi card fields and actions**

In `HomeActivity.kt`, delete fields and `findViewById` calls:

```kotlin
private lateinit var itemQrScan: FrameLayout
private lateinit var itemRokidWifiDebug: FrameLayout
itemQrScan = findViewById(R.id.itemQrScan)
itemRokidWifiDebug = findViewById(R.id.itemRokidWifiDebug)
```

Change the `items` list to:

```kotlin
items = listOf(
    itemAiInspection,
    itemTaskInspection,
    itemLightshot,
    itemUnifiedInputDebug,
)
```

Delete the input actions with ids:

```kotlin
UnifiedInputSession.InputActionId("inspection_mode_scan")
UnifiedInputSession.InputActionId("inspection_mode_rokid_wifi_debug")
```

Change `onItemConfirmed()` to:

```kotlin
private fun onItemConfirmed(index: Int) {
    when (index) {
        0 -> startActivity(Intent(this, InspectionLoadingActivity::class.java))
        1 -> {
            tvBottomHint.text = getString(R.string.common_feature_in_development)
            tvBottomHint.visibility = android.view.View.VISIBLE
        }
        2 -> startActivity(Intent(this, LightshotActivity::class.java).apply {
            putExtra(LightshotActivity.EXTRA_MODE, LightshotActivity.MODE_LIGHTSHOT)
        })
        3 -> startActivity(Intent(this, UnifiedInputDebugActivity::class.java))
    }
}
```

- [ ] **Step 4: Delete WiFi scan files**

Delete:

```bash
app/src/main/java/com/rokid/glass/WifiQrScanActivity.kt
app/src/main/res/layout/activity_wifi_qr_scan.xml
```

- [ ] **Step 5: Remove unused WiFi QR parser if safe**

Run:

```bash
rg -n "WifiQrParser|WifiQrPayload" app/src/main/java
```

If the only remaining file is `app/src/main/java/com/rokid/glass/utils/WifiQrParser.kt`, delete it.

- [ ] **Step 6: Remove unused scanner dependency if safe**

Run:

```bash
rg -n "GlassScanner|glass3.qrcode|com.rokid.security.glass3.qrcode" app build.gradle app/build.gradle
```

If source references are gone, remove this line from `app/build.gradle`:

```groovy
implementation 'com.rokid.security.glass3.qrcode:scanner:1.0.0'
```

Do not remove ML Kit barcode dependencies because `EnterpriseQrScanActivity` still uses ML Kit QR scanning.

- [ ] **Step 7: Clean WiFi scan strings**

Remove strings that start with:

```xml
wifi_scan_
rokid_wifi_scan_
inspection_mode_rokid_wifi_debug
```

Remove `inspection_mode_scan` only if no source or layout references it after Step 3.

Update `inspection_mode_hint` to remove `"扫一扫"` and `"Rokid扫码配网"`:

```xml
<string name="inspection_mode_hint">说出 "AI识患"、"统一输入调试" 或 前后滑动点击选择</string>
```

- [ ] **Step 8: Search for stale references**

Run:

```bash
rg -n "WifiQrScanActivity|activity_wifi_qr_scan|wifi_scan_|rokid_wifi_scan_|inspection_mode_rokid_wifi_debug|itemQrScan|itemRokidWifiDebug|GlassScanner|glass3.qrcode" app/src/main app/build.gradle
```

Expected: no matches, except unrelated docs are outside this command scope.

- [ ] **Step 9: Build**

Run:

```bash
bash scripts/android/build-debug.sh
```

Expected: build succeeds.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/rokid/glass/HomeActivity.kt app/src/main/res/layout/activity_inspection_mode.xml app/src/main/res/values/strings.xml app/build.gradle
git add -u app/src/main/java/com/rokid/glass/WifiQrScanActivity.kt app/src/main/res/layout/activity_wifi_qr_scan.xml app/src/main/java/com/rokid/glass/utils/WifiQrParser.kt
git commit -m "refactor: remove wifi qr scan flow"
```

## Task 6: Final Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run required Android checks**

Run:

```bash
bash scripts/android/doctor.sh
bash scripts/android/build-debug.sh
```

Expected: both pass.

- [ ] **Step 2: Run targeted stale-reference search**

Run:

```bash
rg -n "WifiQrScanActivity|activity_wifi_qr_scan|wifi_scan_|rokid_wifi_scan_|GlassScanner|glass3.qrcode" app/src/main app/build.gradle
```

Expected: no matches.

- [ ] **Step 3: Optional device validation**

If a Rokid device is connected, run:

```bash
bash scripts/android/doctor.sh --device
bash scripts/android/install-debug.sh -s <serial>
```

Manual expected results:

- With WiFi disconnected, launching the app opens the menu and shows a centered “请先连接wifi” dialog; confirming exits the app.
- With WiFi connected and `InspectionSession.isInitialized == false`, the menu redirects to loading; loading completion returns to the menu.
- With WiFi connected and enterprise info missing, the menu redirects to enterprise QR scan.
- With WiFi connected, initialized, and enterprise info present, the menu remains visible and four cards work: realtime analysis, device guide, hazard record, check update.

- [ ] **Step 4: Commit final verification notes if any docs changed**

If verification required doc updates, commit them:

```bash
git add docs/superpowers/plans/2026-05-27-ai-menu-entry-guards.md
git commit -m "docs: add ai menu entry guard implementation plan"
```

