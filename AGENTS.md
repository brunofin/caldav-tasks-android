# CalDAV Tasks Android — agent handover

Native Kotlin + Jetpack Compose Material 3 application, with a RemoteViews
home-screen widget. App ID: `io.github.brunofin.caldavtasks`. Version: `0.1.0`.
Direct CalDAV only; no Python backend, Evolution, EDS, or GOA integration.

## Project map

Kotlin paths below are relative to `app/src/main/java/io/github/brunofin/caldavtasks/`.

| Path | Responsibility |
|---|---|
| `MainActivity.kt` | Android lifecycle, native light/dark/dynamic theme, refresh loop |
| `TasksViewModel.kt` | UI actions, error messages, explicitly targeted inline drafts |
| `TasksApplication.kt` | Shared repository/presentation state, WorkManager synchronization |
| `core/Models.kt` | Accounts, lists, tasks, counts, sorting, cycle-safe task trees |
| `core/Rows.kt` | Shared account/list/task row projection for app and widget |
| `core/ICalendar.kt` | VTODO parsing, metadata-preserving edits, Unicode folding, safety guards |
| `data/CalDavClient.kt` | HTTPS discovery, XML parsing, conditional task/list operations |
| `data/SecureStore.kt` | Atomic AES-GCM encrypted credentials and task cache |
| `data/TasksRepository.kt` | Serialized refresh/mutations, per-account/list failure isolation |
| `data/PresentationStore.kt` | Persistent shared filters and independent folds |
| `ui/TasksApp.kt` | Drawer, grouped list, task cards, composers, confirmations |
| `ui/SettingsScreen.kt` | Connection creation/editing and local-only disconnection |
| `widget/TasksWidget.kt` | Widget updates, PendingIntents, short-lived action activity |
| `widget/TasksWidgetService.kt` | Scrolling RemoteViews collection and row bindings |

Other important paths:

- `app/src/main/res/`: widget layouts, Material-style colors, API-specific metadata.
- `app/src/test/`: 28 SDK-independent model/iCalendar/CalDAV fixture checks and JUnit wrappers.
- `app/src/androidTest/`: isolated fixture application, custom runner, 6 native tests.
- `assets/screenshot.jpg`: exact maintainer-supplied physical-phone screenshot in README.
- `tools/gradle.sh`: Gradle helper using machine-local Studio JDK configuration.
- `tools/test-core.sh`, `tools/test-dav.sh`: SDK-free Kotlin/JVM checks.
- `tools/check-resources.py`: XML/local-reference checks, not Android compilation.

## Desktop companion

[CalDAV Tasks for Dux Shell (DMS)](https://github.com/brunofin/dms-caldav-tasks)
is the separate Linux desktop plugin. Locally it is a sibling checkout,
`../dms-caldav-tasks`. The Android app follows its grouped, collapsible list behavior
but has an independent Kotlin implementation and Android-native controls.

Both clients use the same tasks through separately configured CalDAV accounts.
There is no direct device pairing, shared credential store, or automatic import.
The desktop plugin also supports Evolution/EDS/GOA task sources; do not imply
those providers exist in Android. Do not change/publish/push the desktop plugin or
its registry PR as a side effect of work in this repository.

## Build and verification

- Minimum API 26; compile/target API 35. JDK 17 or 21, Gradle 8.11.1, AGP 8.9.2,
  Kotlin/Compose compiler 2.1.20. Do not assume the host's default Java is compatible.
- SDK installation and existing-Waydroid testing are authorized. SDK: `~/Android/Sdk`;
  JDK 21 is user-local. Machine-specific paths belong ONLY in gitignored
  `local.properties` / `.gradle/config.properties`. Do not change global Java/PATH.
- Do not reinstall/reconfigure Waydroid or install an emulator as a side effect.

```sh
./tools/gradle.sh --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=YOUR_DEVICE_SERIAL ./tools/gradle.sh --max-workers=2 :app:connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Reports:
`app/build/reports/tests/`, `app/build/reports/androidTests/connected/debug/`,
and `app/build/reports/lint-results-debug.html`.

SDK-free checks, with Kotlin/JVM compiler on PATH or `KOTLINC=/path/to/kotlinc`:

```sh
./tools/test-core.sh
python3 tools/check-resources.py
DAV_TEST_CLASSPATH=/path/okhttp-4.12.0.jar:/path/okio-jvm.jar:/path/kxml2-2.3.0.jar ./tools/test-dav.sh
```

Verified 2026-10-29: debug/test APK compilation, 28 JVM checks, 6 native tests on
Waydroid Android 11/API 30, lint 0 errors/19 advisory dependency/KTX warnings.
The maintainer also reported successful use on a physical phone with OpenCloud
and supplied the README screenshot. Do not turn that report into claims that all
phone versions, server mutations, launchers, accessibility, or real-server edge
cases were validated. Automated protocol/native tests use synthetic data.

## Test isolation

- Never exercise mutations on real CalDAV accounts without explicit authorization.
  Do not import desktop credentials or seed fixtures into production storage.
- `NativeTestRunner` uses `FixtureApplication`, an isolated cache-backed encrypted
  store, and an interceptor rejecting all non-fixture hosts. Production dependency
  defaults must remain real clients; fixture data belongs only in test sources.
- Widget tests use a private AppWidgetHost, temporarily adopt BIND_APPWIDGET, then
  delete the allocated widget and drop the permission in `finally`. Do not alter
  the user's launcher or leave test permissions/widgets behind.
- Waydroid's freeform lifecycle can leave finished test activities PAUSED. The
  cleanup removes only that test activity's task with `finishAndRemoveTask()`.
- Test screenshots must use synthetic data unless the user explicitly authorizes
  a particular image. `assets/screenshot.jpg` is an explicitly approved exception;
  do not copy its private task text into examples, fixtures, logs, or other assets.

## Data and security invariants

- No passwords, connection exports, task dumps, signing keys, local SDK paths, or
  device logs in git. Keep the checked-in screenshot exception limited to that asset.
- Credentials AND cached task content are device-bound AES-GCM encrypted using
  Android Keystore, with atomic writes and backup/device transfer disabled.
- Missing keys/corrupt storage fail closed: never regenerate a key over existing
  encrypted data or overwrite it with an empty database. Do not clear app data or
  uninstall an existing user install merely to fix a build/signature problem.
- HTTPS only, same-origin discovery/resource URLs, redirects disabled, standard TLS
  validation. Reject embedded credentials/query tokens. Never log request secrets,
  calendar bodies, or server response bodies in error messages.
- DTDs/external entities are prohibited; keep XML size, depth, and node limits.
- Only mutate a single non-recurring VTODO resource with an ETag. Preserve unknown
  properties, parameters, dates/time zones, and nested VALARM. Fold UTF-8 by octets.
- Recurrence/override/multi-component/missing-ETag resources remain read-only.
  Never guess recurrence arithmetic or delete VEVENTs as a side effect.
- New tasks use `If-None-Match: *`; updates/deletions use `If-Match`. Surface 412
  conflicts instead of overwriting newer server state.
- Delete lists only after confirmation, a fresh explicit VTODO-only component
  check, and a VEVENT resource check. Disconnect removes local data only.
- Repository is the single source of truth for app/widget/WorkManager. Serialize
  mutations and refresh; no fake local-only success or offline write queue.
- Retain last-good cached data on source/list failure and disable writes until
  successful refresh. Do not silently drop failed sources or enable stale editing.

## UI and synchronization invariants

- Every enabled account/list appears together. No global active account/list.
  Connection chips jump/expand; they must not filter away other connections.
- Counts/filters span enabled accounts. Accounts sort by aggregate incomplete task
  counts; lists by their own incomplete counts, descending, with alphabetical/ID ties.
- Filters/folds never affect synchronization, counts, or sorting. Child folds survive
  parent folding; new groups begin expanded. Keep `core/Rows.kt` shared by app/widget.
- Drafts retain explicit account/list/edit/parent identity and text when folded.
  Explicit compose actions expand their targets. Preserve orphaned/cyclic tasks.
- Native Material controls, accessible 48dp touch targets, font scaling and RTL.
  Settings live behind header hamburger → navigation drawer → Settings.
- Widgets use native RemoteViews, not WebViews. Network work runs in WorkManager,
  not a broadcast receiver/action activity. Intents/work input contain IDs only.
- Background sync has Android's approximately 15-minute minimum; visible-app
  refresh is two minutes. Do not promise exact scheduling or bypass battery policy.

## Publication

- Public repository: `https://github.com/brunofin/caldav-tasks-android`, published
  with the user's explicit approval. Use the personal `brunofin` account. No APK
  release/store listing has been published; do not publish releases as a side effect.
- Keep README's Dux companion link and distinguish user-reported physical-phone
  use from automated verification. Do not invent download/store links.
- Preserve MIT licensing. Do not commit APKs, generated reports, or signing material.
- Default local commit signing previously failed through 1Password. Do not change
  global signing configuration; if needed, use an explicit one-command override.
