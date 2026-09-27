# MarkdownNotes (Android)

<img width="389" height="395" alt="image" src="https://github.com/user-attachments/assets/2ccef762-7b4c-4f57-832e-98b4bca47fde" />

Offline companion for the MarkdownNotes vault. Reads, searches, and edits the
markdown files that Resilio Sync already mirrors onto the phone.

- **Phase 1** — file list, read-only viewer, full-text search.
- **Phase 2** — raw-text editing with promote/demote, a TODO toggle, atomic
  saves, and a staleness guard. See *Writing* below.
- **Phase 3** — creating notes (a virtual "Today" row and a new-page action) and
  a top-level menu of three categories — Journals, Pages, To Dos — each opening
  its own page. See *Creating notes* and *To Dos* below.

The editor is deliberately **text-only**: it never parses markdown, so what you
read is exactly the bytes that get written. A Logseq-style block outliner would
need a parser port and is not built.

## Why this isn't a TWA / PWA

A Trusted Web Activity needs the domain to prove ownership via a publicly
fetchable `https://…/.well-known/assetlinks.json`. In this deployment the server is
reachable only over plain HTTP on a private LAN address, which TWA cannot verify, and
the public hostname sits behind an authentication proxy, so that file returns an HTML
login page. A TWA was therefore not an option without new server infrastructure.

This app reads the vault **directly off the filesystem** instead, which is the thing
a wrapper could never do: it works with no server reachable at all.

## Build

Requires JDK 17+ and the Android SDK. On this machine the Gradle wrapper uses the JDK
bundled with Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device:

```bash
~/Library/Android/sdk/platform-tools/adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

## First run

The app asks for **All Files Access** (Settings → grant to MarkdownNotes). This is
deliberate: the vault is a folder of plain files, and real `java.io.File` paths allow
atomic writes later. A scoped-storage or SAF grant would force writes through a
`ContentResolver`, where a failure mid-write can leave a truncated vault file.

> This grant is not obtainable through Play Store review. Fine for a personal
> sideloaded build; it would need reworking to publish.

The vault is **discovered, not hardcoded**: the app walks down from `Documents/`
looking for a directory containing both `journals/` and `pages/`. This tolerates the
casing difference between the Mac repo (`Docker/`) and the phone (`docker/`), a
renamed sync root, and a shallower path.

**If more than one vault syncs in, the app asks which to use** rather than picking
one. It used to take the first match it found, so a second vault was resolved by
alphabetical luck and the user got whichever sorted earlier with no way to find out
a choice had been made for them. The chooser appears once, the answer is remembered,
and **⋮ → Change vault** switches afterwards (hidden when there is only one vault,
since a menu entry that opens a list of one is a dead end).

A remembered vault is only honoured while it still looks like a vault, so a folder
Resilio renames or deletes falls back to a fresh search rather than pinning the app
to a directory with no notes in it. A *single* auto-discovered vault is deliberately
not remembered — if a second one syncs in later, the question should get asked.

Note the search root is still `Documents/`. A vault synced into Resilio's own app
directory or onto an SD card will not be found, and there is no picker or path
entry to fix that. That is the outstanding gap.

## Verified on an emulator (API 36, Pixel 6 profile)

| Case | Result |
|---|---|
| Vault discovery at the real Resilio path | 3 files, journals before pages |
| Two vaults synced in | chooser lists both, long paths truncated from the front |
| Pick one, then cold start | not asked again; same vault reopened |
| Switch via Change vault, cold start | new vault sticks |
| Second vault deleted | stale choice ignored, falls back without asking |
| One vault only | no overflow at all, no dead menu entry |
| Single term present in 2 of 3 files | 2 hits with line context |
| Two terms both in one file | 1 hit, correct file only |
| Two terms split across two files | 2 hits, correct file only |
| Term present nowhere | "No matches", no bogus header |
| Tap a hit | opens that file, scrolled to the line |
| Back | returns to results with query intact |
| Access denied | explains why, offers the grant button |
| Today's journal with no file | "Today" row, dated, marked *not written yet* |
| Save a blank new note | refused; no 0-byte file created |
| Write to Today, then return | virtual row becomes an ordinary row, count unchanged |
| New page titled `A/B` | written as `A___B.md` |
| New page titled `..` or empty | refused in the dialog, with a visible reason, dialog stays open |
| New page for an existing title | opens the existing note, does not blank it |
| File created underneath a new note | conflict prompt; nothing overwritten |
| Open a to-do from the dashboard | opens its file scrolled to that line |
| Top level | three rows only; each opens its own page |
| A category page | titled by the toolbar, no duplicate header row |
| A category with nothing in it | a short message instead of a blank screen |
| A to-do with a `#SomeToDo` tag | grouped under `#SomeToDo`; uncategorised ones last |
| A `DONE` or `CANCELED` block | not listed as open |
| A bare `- TODO` with no text | not listed — a block with no content has nothing to show |
| Vault absent (Resilio hasn't synced) | explains, offers Retry |
| Demote / promote on the caret line | one level in / out, other lines untouched |
| Demote on a multi-line selection | every selected line indented |
| Promote at top level | no-op; never eats content |
| `TODO` → `DONE` → unmarked | in place, one space, no `DONE  double` |
| `- TODOlist` toggled | treated as content; gains a real `TODO` |
| Enter after a bullet | continues `- ` / `- TODO ` with indentation |
| Undo | reverts Enter+continuation as one step, then the typing burst |
| Save | bytes on disk match, no `.tmp-` debris |
| Back from the editor | viewer shows the saved text immediately — no trip via the file list |
| Resilio rewrites the file underneath | picked up when the viewer next comes to the foreground |
| Promote / demote | the caret stays on the line it was on |
| File changed on disk, *Keep mine* | theirs → `.conflict-<ISO>.md`, mine → canonical path |
| File changed on disk, file list | the `.conflict-` copy is hidden |
| Back with unsaved edits | prompts; Discard leaves disk untouched |
| FIND, term on the last line of a 462-line note | counted, and the view scrolls to the bottom of the document |
| FIND, one of many matches | `n of N`; next and previous step through them |
| FIND, next on the last match | wraps to the first match and scrolls back |
| FIND, previous on the first match | wraps to the last match |
| FIND, term absent | "No matches", no highlight left behind |
| FIND, ✕ or Back | bar closes, the note stays open, highlights dropped |
| FIND reopened | empty field, `0 of 0` — the previous query does not come back |

Emulator-only test vault lives under the app's own `Documents/` tree — harmless,
delete it whenever.

## Search semantics

Case-insensitive substring. **All** terms must appear somewhere in a file, and a line
is reported if it contains **any** term — so `tag work` narrows to files carrying both
while still showing the most relevant individual lines. This mirrors the implicit
AND that the web app's `toFtsQuery` produces.

No inverted index: the whole vault is a few megabytes, so a linear scan is fast. Files
whose `(size, lastModified)` are unchanged are served from an in-memory cache.

## Find in page

`FIND` in the viewer searches the note you are reading, so a term you already know is in
one long file doesn't mean scrolling it. The count, the highlighted matches and the
scroll position all track the query as you type.

Case-insensitive **literal substring** — what you type is what is matched, so `.` and `[`
are ordinary characters. This is the same rule `VaultSearch` uses, so the two find boxes
in the app agree. Matches are non-overlapping, so `aa` in `aaaa` is two matches, not
three. Previous and next both wrap at the ends: a "next" that stops dead at the last hit
reads as a bug, and there is no other affordance to continue.

Each match scrolls into view a third of the way down rather than jammed against the top
edge, so there is context to read it in.

Two details that are easy to get wrong:

- **The current match is always highlighted, even past the bulk limit.** Beyond
  `FindInPage.HIGHLIGHT_LIMIT` (1000) matches are still counted and still navigable but
  are not painted, because a span per match is enough to stutter the scroll the reader
  is trying to do. The obvious `for (i in 0 until limit)` silently drops the highlight
  once the index passes the limit — the active match is then past the end of the loop.
- **Back belongs to the find bar, but the keyboard gets first refusal.** With the IME up,
  the first Back only dismisses the keyboard; the second closes the bar. Leaving the note
  needs a third. That is the platform's ordering, not an accident, and it is the right one
  — a reader pressing back twice in quick succession least wants to lose their place.

## Writing

Editing makes the phone a **third writer** on files the Mac and Linux instances also
write — and the worst kind of writer, because it is offline and syncs in bursts later.
The web app already produces occasional Resilio conflict debris with only two writers.
Two rules follow, and everything in `SafeWriter` exists to enforce them.

**1. Never truncate in place.** A save writes a sibling `.tmp-<pid>-<millis>` file,
fsyncs it, then renames it over the target. If any step fails the original is
untouched — a half-written note is the one genuinely unacceptable outcome. Verified
by a test that makes the rename fail and asserts the target still exists.

**2. Never silently discard.** The file is hashed when opened and re-hashed on save.
If it changed underneath — almost always Resilio pulling in your Mac edit — the app
asks, and *Keep mine* first moves the on-disk version to
`<name>.conflict-<ISO>.md` before writing. Both versions survive. The naming matches
`server/src/vault/write.ts` exactly, so debris looks the same whichever writer made
it, and the web app's `.conflict-` filter ignores our copies (as does the phone's
own file list).

Leaving with unsaved edits prompts too.

## Creating notes

Two ways in, both of which write a file the web app can find:

**Today** sits at the top of the file list even when the day has no file yet, and
disappears the moment it is written, so there is never both a virtual and a real
row for the same day. This mirrors the web app, which synthesises empty days in
its feed (`loadPage(...) ?? emptyDoc()`) and only creates a file on the first real
save. A file per day per device would be 365 files a year pushed at the Mac and
the Linux box through Resilio, all of them empty.

**New page** takes a title and writes `pages/<title>.md`.

A new note opens as an empty buffer and is materialised by the first save. A
blank note is never written: a 0-byte file is noise to every other writer, and
the empty note stays virtual instead.

### Filenames have to match the server exactly

`VaultNames` mirrors `server/src/vault/files.ts`. The phone and the web app share
one vault with no database between them, so the only thing making a note findable
is both sides deriving the same filename from the same title. Nothing fails
loudly if this drifts — the note saves fine and simply never appears in the web
app. A page written as `My/Page.md` instead of `My___Page.md` is silently
unreachable.

- Journals are `YYYY_MM_DD.md`, **underscores**. `2026-09-26.md` is not a journal;
  reading it as one would invent a second document id for a day.
- A `/` in a page title becomes `___`, so the title stays one path component.
- Empty, `.`, `..`, over 200 characters, or containing NUL–0x1F are rejected. The
  rejection list is copied from the server rather than tightened, because being
  stricter would refuse titles the web app accepts.

### Creating a note is also a race

Between opening an empty buffer and saving, Resilio can create that file — the
Mac may have made the same page a minute earlier. The staleness guard treats
"opened as absent, but a file exists now" as a **conflict**, not a write, so the
phone offers the same three choices as any other external change. Verified: their
version survives, and *Keep mine* files it as a `.conflict-<ISO>.md` sibling.

## Navigation

The top level is only three rows — **Journals**, **Pages**, **To Dos** — and each
opens a page listing just its own contents. `SectionRows` builds both the menu
and the pages, so what a category contains and what the count on the menu said
are the same thing by construction rather than by two pieces of code agreeing.

Two things are deliberately not on the top level any more: the flat file list
(which is what the two file categories replace) and the file count (now a
per-category preview). Search stays, because it is vault-wide and has no natural
home among the three.

Only `journals/` and `pages/` are ever enumerated, matching the two directories
the web app serves. A markdown file in some third directory is invisible to the
phone, and a test pins that so widening the scan stays a conscious change.

## To Dos

The To Dos page is the same dashboard the web app's To Dos tab shows: every open `TODO`/`DOING`/`NOW`/`LATER`/`WAITING` block in the vault,
grouped by the `*ToDo` tag on the block (`#WorkToDo`, `#ProjectAToDo`, …) with an
`Uncategorized` bucket last. Tapping one opens its journal or page scrolled to
that line. `DONE` and `CANCELED` are not open, so they do not appear.

It is a **scan of the markdown, not a second database.** The index the web app
uses cannot help here — the phone has no SQLite, and building a second index on
a vault that Resilio also writes would be a new thing to keep in sync. Reading
the files on refresh is slower than a query but is always exactly as current as
the files, which is the property that matters when the Mac is editing the same
notes.

### The rules are transcribed, not invented

`VaultTodos` is a second implementation of logic the server already has, so every
rule names the file it came from: what counts as a block and where its
continuation lines end (`tokenize.ts`), the **anchored** marker regex
(`derive.ts`), the empty-block skip (`build.ts`), and the open markers plus
category rule (`search.ts`). The anchoring matters more than it looks — an
unanchored copy would treat `write TODO docs` as a to-do and show a different
set from the web app. A regression test removes the continuation-prefix strip
and two tests fail, so that step is load-bearing rather than incidental.

Two consequences worth knowing:

- A bare `- TODO` is **not** a to-do. A block with no text is never indexed on
  the server, so a marker with nothing after it has nothing to show. The phone
  matches, rather than offering to-do items the web app has never heard of.
- Properties are not prose. The tokenizer strips `depth tabs + 2 spaces` from
  continuation lines before the property rules see them, which is what makes
  `  id:: 123` a property instead of a sentence. Without that step a bare
  `- TODO` carrying only properties looks like a real to-do.

Tags inside a fenced code block are ignored, so a to-do *documenting* the
`#WorkToDo` syntax lands in `Uncategorized` instead of polluting the dashboard —
the same trap as the web app's own property parsing, which reads fence contents
as real properties and is a known bug there.

## Keyboard

The editor publishes a plain multi-line text field to the IME
(`TYPE_CLASS_TEXT | CAP_SENTENCES | MULTI_LINE`). It deliberately does **not** set
`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` or `FLAG_NO_SUGGESTIONS`, even though this is
a markdown/code-ish surface where autocorrect is unwanted: those flags make an IME
treat the field as sensitive, and GBoard responds by disabling **glide (swipe) typing**
entirely. Verified with `dumpsys input_method` on the editor — the published
`inputType` went from `0xa0091` to `0x24001`.

Suggestion and autocorrect behaviour is therefore the keyboard's decision, not the
app's. Turn them off in GBoard's own settings per-keyboard if they mangle markdown
terms, and glide typing keeps working. Suppressing them from the app is what
disabled the gesture in the first place.

## Editing

| Action | Effect |
|---|---|
| `⇥` Demote | one more level of indentation on the caret line, or every line a selection touches |
| `⇤` Promote | one level off — a tab, or two/four spaces, so space-indented content behaves |
| `TODO` | `TODO` ⇄ `DONE` in place; a finer marker (`DOING`, `LATER`, …) collapses to `DONE`; an unmarked line gains `TODO` |
| `Undo` | 60 steps, typing coalesced into bursts |
| `Save` | guarded write (above) |

Enter continues the current bullet, keeping its indentation and its `TODO` marker.

There is no Tab key on a phone, so promote/demote are buttons. A button tap blurs
the field, so the caret is tracked via `onSelectionChanged` and reapplied — reading
the selection in the click handler gets the wrong answer, the same class of bug the
web app hit with `focusedBlock` clearing on toolbar clicks.

Markers are matched on **word boundaries**, so `- TODOlist of things` is content,
not a `TODO`, and gains a real marker instead of being mangled to `- DONE list`.

Transforms are applied to the `Editable` in place rather than via `setText`, which
would discard the buffer's undo metadata. `LineOps` returns range edits rather than a
rebuilt string for the same reason, and is covered by JVM unit tests — it is the one
code that can silently reshape an outline.

## Not built, on purpose

- **Block outliner** (drag/reorder blocks). Needs the 187-LOC parser port plus
  round-trip tests. Reordering lines without subtree awareness would orphan
  children, so it is all-or-nothing.
  - **Image previews** from `assets/`, **To Do/tag views**, markdown rendering. Bigger
    everyday wins than editing, and better as a separate milestone.

## License

MIT — see [LICENSE](LICENSE). Forks welcome.
