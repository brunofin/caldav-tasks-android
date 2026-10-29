# CalDAV Tasks for Android

A native **Kotlin + Jetpack Compose Material 3** companion to
[CalDAV Tasks for DMS](https://github.com/brunofin/dms-caldav-tasks).
The same task-list approach, with Android-native components rather than a WebView:
all accounts together, account headings, list headings, compact task cards, inline
composers, and independent persistent folds. **CalDAV only; no Evolution or GOA.**

## Status

**Initial source implementation, 0.1.0.** The pure Kotlin model/iCalendar and
CalDAV protocol checks have been compiled and run on the JVM: **28 checks pass**.
XML resources have been checked for well-formedness and local references.

**The Android app has not been built, installed, or visually tested.** No Android
SDK or emulator was installed, and no SDK licenses were accepted. There is no APK
or release yet. Android compilation, launcher compatibility, accessibility and
real-device CalDAV testing remain required before using this with important data.
Protocol checks use intercepted fixture responses, not a real server.

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
  selection. Android 15's collection-adapter deprecations are acknowledged; this
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
and run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

This downloads build dependencies and may prompt for SDK components/licenses;
**it was not run as part of this source-only implementation**. Do not commit
`local.properties`, account passwords, signing keys, device logs or screenshots
containing private tasks. This source is not a Play Store release configuration.

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
JUnit wrappers run the same checks through Gradle once an SDK is configured.

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
