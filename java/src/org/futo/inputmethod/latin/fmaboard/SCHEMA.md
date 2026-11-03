# FMAboard typing log – schema v2

Saved automatically. Two JSONL files per local day (one JSON object per line):

| file | content |
|---|---|
| `YYYY-MM-DD.messages.jsonl` | **clean dataset**: one record per message/field you typed in |
| `YYYY-MM-DD.keystrokes.jsonl` | every raw event (touches, keys, swipes, commits…) |

Locations:
- App-private (source of truth): `files/fmaboard/` (debug: `adb exec-out run-as org.futo.inputmethod.latin.unstable cat files/fmaboard/<file>`)
- Automatic copy, refreshed every ≤2 min while typing: **`Documents/FMAboard/YYYY-MM/`** on the phone. Survives uninstalling the app. If the app is reinstalled, new copies may be named `… (1).jsonl`.
- Manual: Settings → FMAboard → Exportar datos (.zip)

Fields opened but never typed in leave no trace.

## messages.jsonl

```json
{"v":2,"id":"3f2a9c1b","start":"2026-11-02T19:40:12.034+01:00","end":"2026-11-02T19:40:31.870+01:00",
 "app":"com.whatsapp","field":"short_message","lang":"es-ES",
 "text":"Vale, nos vemos a las 8","prefilled":"(only if the field had text before)",
 "sent":true,"end_reason":"cleared",
 "stats":{"keystrokes":27,"deletes":2,"swipes":0,"suggestions_used":1,"autocorrections":1,
          "chars":23,"words":6,"typing_ms":9120,"wpm":30.3,"avg_dwell_ms":82,"avg_flight_ms":214},
 "corrections":[{"typed":"vemso","final":"vemos"}]}
```

| field | meaning |
|---|---|
| `text` | final text of the field (≤4000 chars before the cursor) |
| `prefilled` | text that was already there when the field opened (diff it from `text` to get what you wrote) |
| `sent` | an editor action (send/search/go) was pressed, or the app cleared the field right after typing (chat "send") |
| `end_reason` | `closed` (keyboard hidden), `cleared` (app emptied the field: usually sent), `restart` (field restarted) |
| `stats.wpm` | new chars / 5 per minute between first and last input |
| `stats.dictated_chars` | only if voice dictation was used: characters that came from dictation, not typing |
| `stats.pasted_chars` | only if something was pasted from the keyboard's clipboard, typed from "Texto de imagen" or finished with a phrase suggestion: characters the keyboard wrote, not typed |
| `avg_dwell_ms` | average time a finger stays on a key |
| `avg_flight_ms` | average time between consecutive key presses |
| `corrections` | words where what you typed letter by letter differs from what ended in the text (autocorrect). Heuristic: swipes and picked suggestions are excluded |
| `ai_edits` | only if "Reescribir con IA" was used: `[{"style","before","after"}]`. `before` is what you wrote, `after` the AI text that replaced it. **`text` then contains AI-written text**: for "how I write" use `before`, or skip these records |

## keystrokes.jsonl

Common fields: `v` version, `t` epoch ms, `type`, `s` session id (= message `id`).

| type | extra fields |
|---|---|
| `field_start` | `app`, `field`, `multiline`, `input_type`, `ime_action`, `lang`, `kb_w`,`kb_h` (keyboard px), `prefilled` |
| `touch` | `a` down/up, `p` pointer, `x`,`y`, `pr` pressure, `sz` size, `key` label under finger, `kx`,`ky` key centre, `kw`,`kh` key size. `t` is the real touch time |
| `key` | `ch` character **or** `fn` function key (delete, shift, enter…), `x`,`y`, `repeat` |
| `text_key` | `text` (emoji, ".com"…) |
| `gesture` | `phase` start/end/cancel, `points` (swipe typing) |
| `suggestion` | `word`, `kind` |
| `commit` | `text` sent to the app (after autocorrection) |
| `delete` | `n` chars deleted |
| `action` | `id` editor action |
| `dictation` | `text` typed by voice dictation (not by hand); also counted in the message's `stats.dictated_chars` |
| `paste` | `text` pasted from the keyboard's clipboard history or notes, from "Texto de imagen", or the rest of a phrase suggestion (it also appears as a `text_key` + `commit` just before); counted in `stats.pasted_chars` |
| `autocorrect_revert` | `typed`, `committed`: you pressed backspace right after an autocorrection to undo it (also teaches the personal autocorrect to leave `typed` alone) |
| `ai_rewrite` | `style` (fix/clear/short/formal/english, `dictation` when a dictation was polished, or `reply` for "Responder con mi estilo", where `before` is the idea you had typed, possibly empty), `before`, `after` |
| `field_end` | `reason`, `text` |

Coordinates are in keyboard pixels (origin top-left of the keyboard, size `kb_w`×`kb_h`).

Never logged: text typed into the keyboard's own boxes (clipboard search, notes), password, OTP/code, non-text (number/phone/date) fields, apps requesting
`IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito), in the keyboard's own screens (settings, "Texto de imagen"), or when the toggle is off.

## Data notes (read before analysing)

- `lang` is the active keyboard language (since 2026-11-02 ~21:15; before that it was the
  system language, `es-ES` in every early record).
- `stats.wpm` is only written when at least 10 new characters were typed.
- Empty `text` with `sent: false` is a discarded draft: you typed and then deleted it all
  (the words are still in that session's `commit` events). Useful as "abandoned message" data.
- Before 2026-11-03 ~14:30, a field the app reset after you deleted everything could be
  marked `sent: true` with empty `text` (e.g. `73705203`, `13edc4b1`): treat as not sent.
- `pr` (pressure) and `sz` (touch size): the Nothing Phone (A059) reports constant values
  (`pr` 1, `sz` ~0.043), so they carry no information on that device.
- Known bad records from 2026-11-02, before fixes applied at ~21:15 local time:
  - `649ca71f`, `69d0d285`: `text` is empty although a message was sent (WhatsApp emptied
    the field without restarting input). The words are still in the `commit` events.
  - `4583fd3a`: `text` is "…propio gblardgboard"; the real text was "…propio gboard"
    (a reopened word was counted twice).
  - `4aaf0e9c`: correction `{"typed":"g","final":"xiling"}` is spurious, same cause.
  Filter these ids out, or rebuild `text` from `commit`/`delete` events, when training.
