# Review and deployment checklist: results (2026-10-27)

This file is the external review checklist (Odoo is the source of truth, money safety, release
identity), with what was actually found and done on branch `claude/apply-review-changes-135845`.
A box is ticked only when code, command output, or a live read-only Odoo query backs it.
Where this file and `CLAUDE.md` disagree, `CLAUDE.md` wins; say so in the commit that changes either.

## 1. Guardrails (from the review, all kept)

- Targeted fixes in the existing architecture. No rewrite, no new libraries.
- Odoo owns business data. The app keeps only device preferences: selected `pos.config` id,
  payment method ids, printer width, kiosk switch. Each server-owned one is revalidated
  against Odoo at every catalogue load (see F3).
- Nothing is reported as done without the API/SDK confirming it. No credentials in source,
  resources or logs.

## 2. Findings and fixes

| # | Severity | Finding (code evidence) | Fix | Test |
|---|---|---|---|---|
| F1 | High | ZZ Cash loyalty program was a fixed id: `ZzCashService.PROGRAM_ID = 4` | `ZzCashService.program(config)` finds the program in Odoo, using the same rule the web POS uses (`pos_ok`, `program_type = loyalty`, company, `pos_config_ids` empty or containing the config). It gives up if 0 or 2+ programs match. Balance and rewards take the id that was found | `ZzCashProgramTest` (5) |
| F2 | High | Double tap on **Pay** could start two pay flows: `pay` only becomes `Working` after `prepareSnapshot` suspends (`PosViewModel.payWithTerminal` / `payCash`) | `launchPay` holds a main-thread `payStarting` flag from the tap until the flow ends. The coordinator mutex and journal checks (I3) are still there underneath | UAT case 10 (needs the device: the ViewModel has no JVM test harness) |
| F3 | High | Saved config and payment methods were not checked again. `CatalogService.config` used `read(...).first()`, which still returns an archived config and crashes with "List is empty" on a deleted one. If the E700 method was removed from the config in Odoo, a card sale would be charged and then sit in the outbox forever, because `sync_from_ui` refuses the method | `config(id)` is now a `search_read` that returns null for an archived, deleted or out-of-rules config. `PosConfig.paymentMethodProblem` checks the saved terminal and cash methods against `payment_method_ids`. Either problem blocks selling with a "Open Setup" message | `PosConfigCheckTest` (7) |
| F4 | Medium | Two taps on **Close session** sent two closes: `confirmClose` had no busy guard. **Open session** could also create two `pos.session` records | `confirmClose` returns early when `busy` or `closed`. `startClose` returns early when the dialog is already open. `openSession` returns early while `loading`, and its button is disabled while loading | UAT case 11 |
| F5 | Medium | Customer search races: with fewer than 4 digits the in-flight search was not cancelled, so stale matches came back. A late search could reopen the list after a pick. `attachCustomer` and the fiscal-position lookup in `pickCustomer` could overwrite a newer customer choice | The search is cancelled on every keystroke, pick, clear and new sale. Results apply only when the typed digits still match. Attach and pick results apply only when the customer or phone has not changed meanwhile. Customers are keyed by `res.partner` id throughout | UAT (ViewModel). The service layer is covered by the existing `CustomerServiceTest` |
| F6 | Low | Odoo URL and DB were literals in `app/build.gradle.kts` | Moved to `gradle.properties` (`odooBaseUrl`, `odooDb`). A build can override them with `-P`. The build fails unless the URL is `https://`. The value stays a build-time constant on purpose: one endpoint per APK (INFOSEC §2), and a cashier cannot point the till at another server | build |

### Checked, no change needed

- **Serial numbers / store binding.** None in code. The store comes from the `pos.config` the
  administrator picks in Setup, from Odoo's live list.
- **Prices, taxes, totals.** Every line is priced by `account.tax.compute_all` (I2). Totals are reset
  after every cart, customer, fiscal-position and reward change. The ZZ Cash reward is a
  negative line priced by Odoo.
- **ZZ Cash value.** Only rewards where `required_points == discount` are offered, so the 1:1
  rate is checked on Odoo's own reward records rather than assumed. Points are deducted by Odoo
  when the order syncs, and the order uuid is deduplicated by Odoo (I7), so an outbox retry cannot
  deduct twice.
- **Payments.** The ambiguous-outcome rules (I3 to I6) are unchanged. See `docs/INVARIANTS.md`.
- **Session close.** Expected cash comes from `get_closing_control_data`. A `{'successful': False}`
  answer is shown and not retried. The session shows as closed only after Odoo confirms.
- **Credentials.** The API key is in `EncryptedSharedPreferences` (AES-256-GCM). TLS uses OkHttp
  defaults with no trust overrides. `retryOnConnectionFailure(false)`. Redaction is in
  `core/Redact.kt` and `CrashLog`.
- **Setup colours.** The API key field and the setup controls use the neutral Material 3 theme.
  Red is used only for `colorScheme.error` text, the void/unresolved bars and the pay buttons
  (brand decision 2026-10-27, `ui/Theme.kt`).
- **Product images.** The till shows none, so there is no image caching to review.

### Recommendations (not built)

- Receipt footer and store-name fallback (`"ZenZebra"`, "Try it. Love it. ZenZebra.") are brand
  text in code. Odoo 19 `pos.config` has `receipt_header` / `receipt_footer`. Read those once the
  printed layout for HTML-to-text is agreed.
- Setup pre-selects the terminal method whose name contains "E700". It is only a default the
  administrator confirms. Replace it with an explicit pick if more terminal methods appear.
- The ViewModel has no JVM tests (it needs `AppContainer` and Android). F2, F4 and F5 are covered
  only by the UAT script. Extract the guards into plain classes if they grow.
- Setup has no supervisor PIN (CLAUDE.md §13). Odoo ACLs are the real control: the E700 user should be
  POS User only.

## 3. Odoo endpoint / model map (JSON-2, verified)

Every call is `POST {odooBaseUrl}/json/2/<model>/<method>` with `Authorization: bearer <key>`
and `X-Odoo-Database`. 401 or 403 means `Auth` (not retried). IO errors mean `Network`. POSTs are
never auto-retried.

| Purpose | Model.method | Mutation / idempotency |
|---|---|---|
| POS list, saved config check | `pos.config.search_read` | read |
| Payment methods | `pos.payment.method.read` | read |
| Catalogue | `product.product.search_read` (paged 500) | read |
| GST / company | `res.company.read`, `res.country.state.search_read` | read |
| Fiscal positions, taxes | `account.fiscal.position.read`, `account.tax.read` | read |
| Line pricing (I2) | `account.tax.compute_all` | read |
| Customer find / create | `res.partner.search_read` / `read` / `create` | create is cashier-initiated, then read back |
| ZZ Cash | `loyalty.program.search_read`, `loyalty.card.search_read`, `loyalty.reward.search_read` | read; points are deducted by Odoo inside `sync_from_ui` |
| Sale / refund sync | `pos.order.sync_from_ui` | idempotent by order `uuid` (outbox retries) |
| E-bill token, refund source | `pos.order.search_read`, `pos.order.line.read` | read |
| Session | `pos.session.create`, `set_opening_control`, `get_closing_control_data`, `post_closing_cash_details`, `update_closing_control_state_session`, `close_session_from_ui` | the UI guards against double submit (F4); Odoo answers `successful: False` rather than closing twice |

Live check, read-only, 2026-10-27: `loyalty.program` fields `pos_ok`, `program_type`,
`pos_config_ids`, `company_id` exist and are stored. The F1 domain returns exactly
`[{id: 4, name: "ZZ Cash"}]` for configs 1, 2 and 8. No custom Odoo modules: Odoo Online, standard
`point_of_sale`, `pos_loyalty`, `l10n_in`.

## 4. Identity and signing

| Item | Verified value | Source |
|---|---|---|
| Android `applicationId` / `namespace` | `com.bohemian.odoo.pos` (no flavor suffix) | `app/build.gradle.kts` |
| Pine **Application ID** (PROD) | `d0b34dd115e8487cb1e20b6706d90186`, issued by Pine 2026-10-27 | `BuildConfig.PINE_APPLICATION_ID`, `prod` flavor |
| Pine Application ID (UAT) | `771aabd360b64cd59d11806bd88d87b4` | `uat` flavor |

The hex value is **not** the Android application id. It is Pine's SDK `application_id`, passed to
`PineBillingSdk` at init (HARD RULE 10). The Android package stays `com.bohemian.odoo.pos`.
Changing it would break in-place updates and the Pine registration.

- Signing: `signingConfigs.release` sets `enableV1Signing = true` and `enableV2Signing = true`.
  Passwords come from the git-ignored `keystore.properties`. `assembleProdRelease` / `bundleProdRelease`
  refuse to run without it. Debug builds never use the release key.
- The last signed PROD APK (versionCode 1 / 0.1.0) was audited in `docs/INFOSEC.md`: v1 and v2 verified,
  cert SHA-256 `5062f7cf…cbda`. This change bumps the build to **versionCode 2 / 0.1.1**.
  Any build that goes to PAXSTORE must be higher than the one already there.

## 5. Commands run (this branch, dev Mac, JDK 21)

| Command | Result |
|---|---|
| `./gradlew testUatDebugUnitTest` (before changes) | pass: 139 tests, 0 failures |
| `./gradlew testUatDebugUnitTest` (after changes) | pass: 151 tests, 0 failures |
| `./gradlew testUatDebugUnitTest` (after merging main #14) | pass: 171 tests, 0 failures. #14 books ZZ Cash with `confirm_coupon_programs`; the sale snapshot now carries the program id that was found (`loyaltyProgramId`), so a sale retried from the outbox is booked on the program it was sold under. Snapshots written before this have no id and book no points. |
| `./gradlew lintProdRelease` | pass: 0 errors, 22 warnings, all of kinds already accepted (dependency versions, AGP, targetSdk, ABI, package visibility, `getIdentifier`). None in the changed code |
| `./gradlew assembleProdRelease` | **refused as designed**: "keystore.properties is missing: a PROD release must be signed with the release key". No APK built on this branch |

## 6. Blockers

- **Signed PROD build / apksigner check:** needs `keystore.properties` and the release keystore.
  They are on the dev Mac's main checkout, not on this branch's worktree. Run the build from there and verify with
  `apksigner verify --print-certs --min-sdk-version 21 <apk>`: expect v1 and v2, and cert SHA-256 `5062f7cf…cbda`.
- **Device:** UAT E700 has no `com.pinelabs.masterapp` yet (CLAUDE.md §13), so no on-device payment run.
- **PAXSTORE:** rollout scope, test group and rollback steps still need confirming with Pine.

## 7. Supplied plan status

- [x] Task 1: Gradle production identity and V1/V2 signing. Verified in source, see §4. The Pine id is not the package name
- [x] Task 2: Dynamic store configuration. No serial numbers. The config is picked from Odoo and now revalidated (F3). ZZ Cash program is discovered (F1)
- [ ] Task 3: Production release APK build and verification. Blocked on the keystore in this worktree, see §6
- [ ] Task 4: E700 installation and persistence test. Blocked on MasterApp provisioning
- [ ] Task 5: PAXSTORE deployment and update documentation. `docs/DEPLOY_E700.md` covers the pipeline; rollout and rollback still need confirming with Pine
