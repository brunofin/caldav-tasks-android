# CalDAV Tasks Android

Native Kotlin/Compose Material 3 app; CalDAV only. App ID `io.github.brunofin.caldavtasks`.
See README for architecture, build prerequisites and current verification limits.

- User explicitly chose **source code only**. Do not install SDK/JDK/emulator or
  accept Android licenses without fresh authorization. Android compilation and
  on-device/UI/widget behavior remain UNVERIFIED. Never claim the APK works based
  solely on the SDK-free checks.
- Use existing Kotlin/JVM tooling for `tools/test-core.sh` and `tools/test-dav.sh`.
  `python3 tools/check-resources.py` checks XML/references, NOT Android compilation.
- No account passwords, task content, signing keys or `local.properties` in git.
  Credentials/cache are device-bound encrypted, no-backup, atomic. Missing keys
  must fail closed, never regenerate over existing data.
- No real CalDAV mutations without explicit user authorization. Fixture clients
  use an OkHttp interceptor and never contact an account server.
- Keep requests HTTPS and same-origin, redirects disabled, standard TLS intact.
  No credential-bearing URLs/query tokens. DTDs/external entities are prohibited.
- Preserve calendar metadata and VALARM. Only mutate one non-recurring VTODO
  resource with an ETag; never guess recurrence behavior or delete VEVENTs.
  New tasks use If-None-Match; changes/deletes use If-Match.
- Only delete explicitly task-only lists after fresh verification and event check.
  Delete requires explicit confirmation in the full app. Disconnect is local only.
- Repository is the single source of truth, shared with widget/WorkManager.
  Serialize mutations/refresh; no fake local-only success or offline mutation queue.
- Every account/list appears together. No global active account/list selection.
  Filters/folds never change sync/counts/sorting. Children survive parent folds.
- Accounts sort by aggregate open tasks; lists by own open count, alphabetical ties.
  `core/Rows.kt` must remain shared by the app and home-screen widget.
- Compose drafts must retain explicit list/edit/parent identity when folded.
  Native Material controls and 48dp touch targets; support font scaling/RTL.
- Drawer is opened by header hamburger, with Tasks/Settings. No Evolution/GOA UI.
- Widgets use native RemoteViews. Network writes run in WorkManager, not a broadcast
  receiver. Intents/work data contain IDs only, never passwords or calendar bodies.
- Existing DMS plugin and registry checkouts are separate. Do not modify, publish
  or push either as a side effect of work here.
- No GitHub repo has been created for this Android app yet. Ask public/private
  before publishing; use personal `brunofin`, not the work GitHub account.
