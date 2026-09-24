# Logging kills

Five kills with capture mode on confirm the ID mappings, the phase signals and the prayer-check tick
that Parts 2–4 rely on. Only you can do this: never automate game input.

## Setup

1. `./gradlew run`, then log in following https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts.
2. Enable **Yama Reviewer**. Under **Development**, turn on **Capture mode**.
3. Write down the real-world time when you do each deliberate action below.

## The kills

| Kill | Mode | Do deliberately |
| --- | --- | --- |
| 1 | Solo (Travel) | Pray correctly all of P3. Use every spec weapon you own at least once, including a purging staff spec on a flare. |
| 2 | Solo (Travel) | In P3: switch one prayer on the tick Yama casts, one on the tick the hit lands, pray the wrong style once, walk into one Shadow Crash line, get hit by one Shadow Wave, let one flare explode, stand next to Yama once to get meleed. Get hit by a Judge fire surge. |
| 3 | Duo host (Travel, partner joins after you) | Normal kill. Note who Yama targets in P3. |
| 4 | Duo joiner (Join) | Normal kill. |
| 5 | Any mode, under a contract | Normal kill. Note the contract's name. |

Raw logs land in `~/.runelite/plugin-data/yama-reviewer/raw/`. Copy the five files somewhere safe right
after each kill: only the newest 20 are kept.

## Reading a log

    ./gradlew captureSummary --args="$HOME/.runelite/plugin-data/yama-reviewer/raw/<file>.jsonl.gz"

Each row is one kind of event on one actor with one ID: count, first and last tick, most common gap,
and the gameval name when the ID has one. Check against spec section 5.5:

- **Attacks:** a `YAMA` animation with gap 8 before P3 and 7 in P3 (`YAMA_STANDARD_ATTACK`), and two
  `YAMA` graphics that alternate (`YAMA_CAST_MAGIC`/`_RANGED`), each followed by an impact graphic on
  `SELF` or `PARTNER`.
- **Prayer-check tick:** in kill 2, which of the two deliberate switches was scored as blocked decides
  `prayerCheck` (`CAST` or `HITSPLAT`).
- **Phases:** which of `varbit` `YAMA_TRANSITION_PHASE`, the overhead lines, the transition graphic and
  the Judge spawn fire at each Judge.
- **Glyphs:** whether glyphs appear as `object` spawns or only as `object-animation` rows, and which
  object ID is fire and which is shadow.
- **Crash lines:** ground graphics that come in groups of three on the same tick (`CRASH_FIREBALL`).
- **Still to capture:** the purging staff spec animation (`SELF` animation when you spec a flare) and
  the Judge fire surge NPC (`npc` row during a Judge phase).
- **Messages:** `game-message` rows: the Shadow Wave message "You've been injured and can't use
  protection prayers!" and the "Yama conjures" glyph message (purple for shadow, orange for fire).
- **Contract:** the `widget-text` row with the contract name, and an `inventory` event with change -1
  for the contract item at tick 0 (`zcat <file> | grep inventory`).

## Afterwards

1. Correct any mapping in `src/main/java/com/yamareviewer/adapter/ids/BuiltInIds.java` that the logs
   contradict, and fill the three roles still to capture.
2. Set `Rules.DEFAULT.prayerCheck` from kill 2.
3. Copy the five logs to `src/test/resources/fixtures/` for the golden tests in Part 3.
