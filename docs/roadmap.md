# LockIn — Product Roadmap

## Scope 1 — Current (View System, completed)

The initial release uses the classic Android View system (XML layouts + ViewBinding).
All core functionality is implemented and tested.

**Delivered:**
- Device Owner provisioning + lockdown (DevicePolicyManager)
- VPN-based content filter (TUN interface, DNS NXDOMAIN injection, TLS SNI blocking)
- Bloom filter + Room/SQLCipher blocklist database
- Argon2id PIN with brute-force lockout
- AccessibilityService watchdog
- Weekly blocklist sync (WorkManager)
- XML-based UI: Setup flow, PIN setup, Permissions, Dashboard

---

## Scope 2 — Jetpack Compose UI Migration

> **Goal:** Replace all XML layouts and View-based fragments with a fully declarative
> Jetpack Compose UI while keeping all business logic, ViewModels, and backend modules
> untouched.

### What will change

| Area | Scope 1 (current) | Scope 2 (planned) |
|---|---|---|
| UI toolkit | XML layouts + ViewBinding | Jetpack Compose |
| Navigation | `NavHostFragment` + nav graph XML | `NavHost` + `composable()` routes |
| Theming | `themes.xml` + Material Components | `MaterialTheme` + `ColorScheme` in Kotlin |
| Launcher icons | Adaptive XML drawables | Same assets, referenced via `painterResource` |
| PIN overlay | Programmatic `LinearLayout` in AccessibilityService | Compose `Dialog` or `Popup` via `AbstractComposeView` |
| Fragment lifecycle | `Fragment` + `onViewCreated` | `@Composable` functions + `collectAsStateWithLifecycle` |

### What will NOT change

- All `:core:*` modules (vpn, filter, security, admin, sync, accessibility)
- ViewModels (`SetupViewModel`, `DashboardViewModel`) — Compose observes them via `collectAsStateWithLifecycle`
- Hilt DI setup
- All unit tests

### Screens to migrate

| Screen | Current file | Scope 2 target |
|---|---|---|
| Setup (Device Owner) | `SetupFragment.kt` + `fragment_setup.xml` | `SetupScreen.kt` composable |
| PIN Setup | `PinSetupFragment.kt` + `fragment_pin_setup.xml` | `PinSetupScreen.kt` composable |
| Permissions | `PermissionsFragment.kt` + `fragment_permissions.xml` | `PermissionsScreen.kt` composable |
| Dashboard | `DashboardFragment.kt` + `fragment_dashboard.xml` | `DashboardScreen.kt` composable |
| PIN overlay (Accessibility) | `BlockedActivityOverlay.kt` (programmatic View) | Compose-based overlay via `AbstractComposeView` |

### Dependencies to add in Scope 2

```toml
# gradle/libs.versions.toml additions
compose-bom        = "2024.05.00"
compose-activity   = "1.9.0"
compose-navigation = "2.7.7"
compose-hilt-nav   = "1.2.0"

[libraries]
compose-bom        = { group = "androidx.compose", name = "compose-bom",            version.ref = "compose-bom" }
compose-ui         = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3  = { group = "androidx.compose.material3", name = "material3" }
compose-activity   = { group = "androidx.activity", name = "activity-compose",      version.ref = "compose-activity" }
compose-navigation = { group = "androidx.navigation", name = "navigation-compose",  version.ref = "compose-navigation" }
compose-hilt-nav   = { group = "androidx.hilt", name = "hilt-navigation-compose",  version.ref = "compose-hilt-nav" }
compose-viewmodel  = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose" }
```

### Migration strategy

1. Enable Compose in `app/build.gradle.kts`:
   ```kotlin
   buildFeatures {
       compose = true
       viewBinding = false   // remove after migration complete
   }
   composeOptions {
       kotlinCompilerExtensionVersion = "1.5.14"
   }
   ```

2. Migrate one screen at a time (bottom-up: Dashboard → Permissions → PIN Setup → Setup).
   Each screen can coexist with the remaining XML fragments via `ComposeView` inside the
   existing fragment until the full migration is done.

3. Replace `NavHostFragment` + nav graph XML with `NavHost` in `MainActivity` as the
   last step, once all screens are composable.

4. Remove `fragment_*.xml` layout files and ViewBinding after each screen is migrated.

### Acceptance criteria for Scope 2

- [ ] All 4 setup/dashboard screens are pure Compose — no XML layouts remain in `:app`
- [ ] Navigation uses `NavHost` + `composable()` routes
- [ ] `MaterialTheme` with dark mode support replaces `themes.xml`
- [ ] PIN overlay in `BlockedActivityOverlay` uses `AbstractComposeView`
- [ ] All existing unit tests still pass unchanged
- [ ] No `ViewBinding` imports remain in `:app`
- [ ] Compose UI tests added for at least: Setup screen ADB command display, PIN validation
  error messages, Dashboard filter status indicator

---

## Scope 3 — Candidates (not yet scheduled)

- Multi-profile support (separate PINs per child device)
- Custom blocklist URLs (parent-configurable)
- Per-app filtering rules
- Usage reports / block log UI
- Remote management via companion app
