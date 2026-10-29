# CalDAV Tasks for Android

A native **Kotlin + Jetpack Compose Material 3** companion to
[CalDAV Tasks for DMS](https://github.com/brunofin/dms-caldav-tasks).
The same task-list approach, with Android-native components rather than a WebView:
all accounts together, account headings, list headings, compact task cards, inline
composers, and independent persistent folds. **CalDAV only; no Evolution or GOA.**

## Status

**Initial development build, 0.1.0.** Verified on 2026-10-29:

- Debug APK and instrumentation APK compile/package successfully with SDK 35/JDK 21.
- **28 JVM checks pass**, through the Gradle JUnit suites as well as SDK-free checks.
- **6 Android instrumentation tests pass** in Waydroid Android 11/API 30: drawer and
  settings, grouped accounts/folds/counts, draft retention and conditional creation,
  completion/Done filter, Keystore-encrypted storage, and a real RemoteViews widget
  test host exercising folds, filters and completion through WorkManager.
- Android lint: **0 errors, 19 advisory warnings** (dependency updates/KTX suggestions).
- XML resource/reference checks pass. SDK/JDK are user-local; no emulator needed.

The fixture runner intercepts every request and uses isolated encrypted storage.
**No real accounts or servers were used.** Test widgets bind to a temporary test
host, not the user's launcher. Modern Android/physical devices, actual launcher
behavior, accessibility and real-server interoperability still need testing before
using important data. This is a debug APK, not a signed release/store submission.

## App behavior

- Multiple named HTTPS CalDAV accounts, username/app-password authentication.
- Native Material 3, system light/dark mode and wallpaper-derived colors on
  Android 12+. Edge-to-edge UI and standard accessible Material touch targets.
- Header hamburger menu opens a navigation drawer with **Tasks** and **Settings**.
  The Settings screen manages connections; no Evolution settings appear.
- One scrolling list containing **every enabled account and every task list**.
  Connection-overview chips jump to headings without selecting/filtering an account.
- Global **Active / All / Done** filters and counts.
- Accounts sorted by their aggregate open-task count; lists sorted by their own
  open-task counts, descending, with alphabetical/stable-ID tie breakers.
- Independently collapsible account/list headings. Child folds survive parent
  folds, refresh and restart; folds never change synchronization or counts.
- Per-list inline add/edit/subtask composers. Drafts survive folding and activity
  recreation in the current process. They are not restored after process death.
- Task title editing, completion/reopening, `RELATED-TO` subtasks, and confirmed
  permanent deletion. Deleting a parent leaves its children intact.
- Task-only list creation, rename and confirmed deletion. Mixed/unknown-component
  collections cannot be deleted. A fresh VEVENT check also precedes list deletion.
- Refresh on opening, manual refresh, and every two minutes while visible.
- WorkManager refresh approximately every 15 minutes when Android permits it.
  Doze, battery policies and force-stop can delay/stop background work.
- Persistent encrypted last-good cache. Account and list failures are isolated;
  stale/unavailable lists stay visible but cannot be edited until refresh succeeds.

## Home-screen widget

Long-press the Android home screen → **Widgets → CalDAV Tasks**. The resizable,
scrolling native RemoteViews widget shares the app's model, ordering, filters and
folds, and uses Material-style light/dark surfaces.

- Account and list headings expand/collapse independently.
- Active / All / Done controls affect the same global presentation state as the app.
- Tap a task's completion control to request a server-confirmed change through
  WorkManager; failed writes are shown, not silently treated as complete.
- A list's **+** opens the app's composer for that list. Tap the widget title/task
  text to open the app. Editing, subtasks, settings and destructive actions take
  place in the full app, not a cramped launcher dialog.
- Refresh explicitly using **↻**, or wait for background refresh.
- Widgets expose cached task titles to the launcher. Add only to a trusted home
  screen. All widget instances share filters/folds; there is no per-widget account
  selection. Collection actions are verified in an Android 11 test host; actual
  launcher layouts/resizing remain to be tested. Android 15's collection-adapter deprecations are acknowledged; this
  initial implementation uses the still-supported RemoteViewsService API.

## Security and calendar safety

- Credentials **and cached task content** are encrypted with AES-GCM using a
  device-bound Android Keystore key. Writes are atomic, backups/device transfer are
  disabled, and no credentials enter intents, WorkManager input, URLs or logs.
- Keystore loss/corruption fails closed. Existing encrypted data is never silently
  replaced by a new key or an empty database. Uninstall/clear-data loses the local
  connection setup; server data is unaffected.
- HTTPS only, platform TLS validation, no redirect following, and same-origin
  discovery/resource requests only. Configure the server's final CalDAV endpoint;
  redirects or cross-origin home collections require a corrected account URL.
- CalDAV `PROPFIND` principal/home discovery and `REPORT` VTODO fetching. Failed
  `propstat` statuses are not interpreted as successful properties. DTDs/external
  entities are rejected and XML size/depth/node counts are bounded.
- New resources use `If-None-Match: *`; edits/deletions use `If-Match` ETags.
  A conflict asks for refresh rather than overwriting someone else's changes.
- Unknown iCalendar fields, dates, time zones and alarms survive title/status
  edits. UTF-8 lines fold by octets. Events are never presented as tasks.
- Recurring tasks, recurrence overrides, resources containing multiple components,
  and resources without ETags are **read-only** in this initial version. No guessed
  recurrence arithmetic, component-wide deletion, or offline mutation queue.
- No analytics, credential import from the DMS plugin, OAuth flow, certificate
  exceptions, reminders, due-date editing, cross-list moves or drag ordering.
- A disconnected account removes only local credentials/cache. It never deletes
  an account or tasks on the server.

## Open/build later

Requires **JDK 17 or 21**, Android SDK platform **35**, and Build Tools supported by
Android Gradle Plugin **8.9.2**. Minimum device: Android **8.0 / API 26**.
Gradle **8.11.1** is pinned via the checked-in official wrapper, with distribution
SHA-256 verification. The wrapper JAR was verified against Gradle's published hash.

Open this directory in Android Studio and configure your SDK, or set `ANDROID_HOME`
and `JAVA_HOME` and run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

This downloads build dependencies and may prompt for SDK components/licenses.
On the original development machine, the SDK is installed at `~/Android/Sdk` and
Studio's gitignored `.gradle/config.properties` selects the user-local JDK 21.
The helper uses those local settings without changing global Java or shell PATH:

```sh
./tools/gradle.sh --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Do not commit
`local.properties`, account passwords, signing keys, device logs or screenshots
containing private tasks. This source is not a Play Store release configuration.

### Native tests (Waydroid or an Android device)

Authorize ADB, then run:

```sh
ANDROID_SERIAL=192.168.240.112:5555 ./tools/gradle.sh --max-workers=2 :app:connectedDebugAndroidTest
```

Use your actual device serial from `~/Android/Sdk/platform-tools/adb devices`.
The custom test runner installs only synthetic accounts in a separate cache-backed
encrypted store, with an interceptor refusing non-fixture hosts. Production
credentials/cache are not imported or overwritten. The widget test temporarily
adopts the test framework's BIND_APPWIDGET permission, creates a private test host,
then deletes its widget and drops the permission. It does not alter the home screen.
The Waydroid freeform lifecycle workaround removes only the test activity's task.

Reports: `app/build/reports/androidTests/connected/debug/index.html` and
`app/build/reports/lint-results-debug.html`.

### SDK-free checks

With a Kotlin/JVM compiler and Java 17+:

```sh
./tools/test-core.sh
python3 tools/check-resources.py
```

Set `KOTLINC=/path/to/kotlinc` if it is not on PATH. `test-core.sh` exercises model
ordering, filtering, folds, trees/cycles, account-scoped identity, VTODO parsing,
lossless edits, recurrence guards, Unicode folding and input validation.

For CalDAV fixture checks, provide existing dependency JARs (no Android SDK):

```sh
DAV_TEST_CLASSPATH=/path/okhttp-4.12.0.jar:/path/okio-jvm.jar:/path/kxml2-2.3.0.jar \
  ./tools/test-dav.sh
```

Those checks cover discovery, CDATA/XML, origin isolation, DTD rejection, failed
properties, conditional writes/conflicts, mixed-list deletion and error redaction.
JUnit wrappers run the same checks through Gradle.

## Project map

| Path under `app/src/main/` | Purpose |
|---|---|
| `java/…/core/` | Platform-independent model, shared list rows, iCalendar safety |
| `java/…/data/CalDavClient.kt` | HTTPS CalDAV discovery and conditional mutations |
| `java/…/data/SecureStore.kt` | Device-bound encrypted atomic connection/cache store |
| `java/…/data/TasksRepository.kt` | Serialized refresh/mutations and failure isolation |
| `java/…/data/PresentationStore.kt` | Shared persistent filters/folds |
| `java/…/ui/` | Material task list, inline composers, drawer/settings |
| `java/…/widget/` | Launcher collection, row actions, refresh |
| `java/…/TasksApplication.kt` | Shared repository and WorkManager synchronization |

## Before a first release

1. Build, lint and instrument on API 26, 31 and 35+; test rotation/process death,
   large fonts, TalkBack, narrow screens and edge-to-edge system bars.
2. Test widgets on Pixel Launcher and another launcher, including resizing,
   process restart, fold state, rapid clicks, offline errors, Doze and force-stop.
3. Use disposable Radicale/Nextcloud/OpenCloud accounts to verify discovery,
   read-only lists, 412 conflicts, mixed-collection protection, task/list CRUD,
   malformed resources and metadata preservation. Never test deletion on real data.
4. Review encrypted persistence/key loss, signing, target SDK requirements and app
   identity before publishing. Add screenshots only after a real Android run.

MIT. List interaction design follows the DMS companion; this is an independent
Android implementation, with no Python runtime or Evolution integration.
