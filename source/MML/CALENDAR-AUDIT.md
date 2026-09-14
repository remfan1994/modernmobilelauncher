# MML Calendar Audit

## Canonical session data

Every internal journal record and event records:

```
T = Unix epoch milliseconds
Z = contemporaneous IANA timezone identifier
```

`T` is the canonical UTC instant. `Z` preserves the civil-time context that existed when
MML wrote the row. For calendar system `C`, the displayed date is a pure projection:

```
D(C) = project(C, T, Z)
```

Changing `C` changes only the displayed coordinate; it does not mutate `T`, `Z`, Comments or
events.

## Canonical calendar Comments

One editable calendar Comments document is keyed by an intended Gregorian civil date `G` in ISO
`YYYY-MM-DD` form. It also stores the timezone `Zt` in which that date was selected, its creation
instant `Tc`, its creation timezone `Zc`, and exact text `X`:

```
calendar Comment = (G, Zt, Tc, Zc, X)
```

The intended date is not inferred from `Tc`. For display in calendar projection `C`, MML chooses
local noon on `G` in `Zt` as a representative instant and projects that coordinate:

```
R = noon(G, Zt)
Dcomment(C) = project(C, R, Zt)
```

Noon avoids a midnight edge while retaining the intended civil date. A day selected in a Hebrew,
Islamic or Chinese projection is converted through its representative instant to a canonical
Gregorian date. Changing projection later changes where the document appears but does not rewrite
`G` or `Zt`. Editing replaces `X` while preserving the original target and creation metadata. MML
stores at most one such document per Gregorian date.

## Month table

Calendar is the startup/HOME root. MML hides only Android's status bar by default, lets Android
show it transiently by swipe, and preserves the navigation bar. Navigation-bar, display-cutout and
IME insets remain applied. At ordinary font scale Calendar uses a seven-column large-print,
bold-text grid. Each day cell is:

```
day_number
entry_count
```

where:

```
entry_count = closed_session_count + (saved Comments page ? 1 : 0)
```

A closed session belongs to a displayed day if its start, one of its real immutable machine/user
events, or one of its legacy Comment partitions projects onto that day. The administrative boundary
written by the next `MainActivity` startup is excluded. A long process session can therefore appear
on both sides of midnight without being counted twice within either day. Every displayed date is
openable, even when its entry count is zero. Its day page presents the one editable Comments
document separately from Previous Session / Next Session. Session paging covers all matches and
shows each complete closed-session transcript without filtering it to the selected date. The
current OPEN session is never included in counts or paging.

Calendar derives those memberships on a dedicated worker from a compact index. For each archived
session the index retains metadata plus one timestamp/timezone representative per unique Gregorian
civil day/timezone pair, not event details or legacy Comment text. That reduction is equivalent for
all five projections because each implemented calendar changes at civil midnight; projection still
uses the original row timezone. Per-session projected keys remain a set, so multiple rows or
timezone representatives landing on one displayed day count the session once. The selected
projection map is cached across month changes, preserves oldest-start-first session order, and is
invalidated by archived-history deletion or captured-notification redaction. Opening a day loads
and formats only the selected complete transcript off the UI thread.

Calendar has no graphical brightness slider. One outer Calendar gesture surface samples
`Settings.System.SCREEN_BRIGHTNESS` at pointer-down and converts only a clearly horizontal drag
into Android system brightness: right increases, left decreases, and 75 percent of screen width
represents a 100-percentage-point adjustment before clamping to 1 through 100 percent. Without
Modify system settings access, the gesture opens the permission explanation and never creates an
MML-only brightness level. The MML window always uses `BRIGHTNESS_OVERRIDE_NONE`, including after
resume, so it follows Android's actual value. Adaptive brightness remains Android-owned and may
subsequently alter that value. Vertical scrolling, taps and diagonal motion remain child
interactions. The compact horizontal Previous / month / Next control is the sole month-navigation
path. There is no explanatory paragraph above the month, and Calendar uses 3dp side padding.
Natural Android recreation supports both portrait and landscape rather than forcing an
orientation. Landscape reduces vertical padding while retaining the same calendar content.

A date page has no duplicate Calendar or Current Session buttons. Android Back or Escape returns
to Calendar. The editor stays a horizontal `+ / input / Save` row; `+` opens only the app-shortcut
picker directly.

At Android font scale 150% or above, at 130% on phone widths through 360dp, or on a screen narrower
than 320dp, the month becomes a single-column, full-width day list. Below that threshold the grid's
bare total count is capped visually at `99+`, while the full-width list and accessibility
descriptions distinguish saved Comments from closed sessions.

## Today

Today is computed from the current instant and device timezone in the selected projection:

```
today(C) = project(C, now, current_device_timezone)
```

Only a day whose complete `CalendarDateKey` equals `today(C)` receives reverse video. This includes
the calendar system, extended year, month, day, and Chinese leap-month flag, so matching day numbers
cannot be confused. Paired grayscale resources swap foreground and background in light and night
themes. Calendar rerenders when it resumes, refreshing today after an intervening date or timezone
change. While Calendar remains visible, Android date, clock and timezone-change broadcasts also
rerender it. Date text and entry-total text use separate grayscale resources in both normal and
reverse-video cells, with the date perceptually darker than the total while retaining large-text
contrast. Each pair differs by 53 grayscale channel levels. The intermediate `#646464` and
`#9B9B9B` values are exact inverses, as are the `#2F2F2F` and `#D0D0D0` extremes; every relevant
foreground remains above 3:1 against its surface. No raw runtime color is applied that would
undermine Android color inversion.

## Projection choices

1. Gregorian uses ICU `GregorianCalendar`.
2. Hebrew uses ICU `HebrewCalendar`.
3. Islamic civil uses ICU `ISLAMIC_CIVIL`, an arithmetic calendar.
4. Islamic Umm al-Qura uses ICU `ISLAMIC_UMALQURA`.
5. Chinese uses ICU `ChineseCalendar`.

Android's Hebrew calendar deliberately changes date at local midnight rather than at the
traditional sunset boundary. This civil-midnight rule is compatible with MML's local-noon
Gregorian identity mapping and must not be described as observational or sunset reckoning. Titles
use Tishri, Heshvan, Kislev, Tevet, Shevat, Adar I, Adar or Adar II, Nisan, Iyar, Sivan, Tammuz, Av
and Elul. ICU month addition determines whether Shevat advances to Adar I or directly to Adar, so a
non-leap year never exposes a phantom month and a leap year identifies both Adars.

Chinese month identity includes both `EXTENDED_YEAR` and `IS_LEAP_MONTH`. This prevents a
leap month from being merged with the ordinary month carrying the same month number. The
month title says `leap month` whenever that field is set.

Islamic civil and Umm al-Qura are separate selectable projections because they can assign
the same instant to different dates. MML does not claim that either choice substitutes for
a religious authority's local observation.

## Timezone changes

The month containing today uses the device's current timezone. Each historical session record is
classified with its recorded timezone. Each calendar Comments document uses its recorded target
timezone. Therefore travel or later timezone changes do not silently move an old session or plan
to a different remembered date.

## Future events

A calendar Comments page is future at instant `Tnow` exactly when its intended Gregorian date is
after the date of `Tnow` in that page's target timezone:

```
future(G, Zt, Tnow) = G > local_date(Tnow, Zt)
future_event_count = count(nonempty saved pages where future is true)
```

Today and past pages do not count, and closed sessions are unrelated to this total. Calendar's
bottom row assigns width weight 1 to `Future events` and weight 2 to `Current Session`. The Future
events screen loads the full current Comments records off the UI thread and sorts by intended civil
date and then target timezone, so the soonest event appears first. A row preserves full multiline
text and opens its canonical calendar date when selected.

When enabled and permitted, MML posts one low-importance `Future events` notification. Its collapsed
title is `Future events...`; expanding it shows one chronological `M/d. compact text` line per
retained event. Cross-year dates include the year. The stream scan retains at most 20 summaries,
compacts each to 120 code points and bounds the complete body to 3,200 code points while preserving
the exact total for singular/plural and omitted-event reporting. Tapping it opens Future events and
auto-cancels that instance without immediate resume-time reposting. An ordinary resume refreshes the
content only while the notification remains active, so a date, timezone or Comments change can
update a visible list without resurrecting a dismissed one. A later future-event count change or
normal fresh `MainActivity` startup can post again. Android 13 controls posting through
`POST_NOTIFICATIONS` and the channel settings. Android Notification Access for incoming-notification
capture is a separate opt-in facility: captured notifications neither create calendar Comments nor
change `future_event_count`.
