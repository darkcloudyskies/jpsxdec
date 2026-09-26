# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

jPSXdec is a Java (Swing GUI + command-line) PlayStation 1 audio/video converter: it indexes a disc image, identifies
videos/audio/TIM images/files, and extracts (and in some cases replaces) them.

## Repository layout

- `jpsxdec/` — the actual project (all build commands run from here)
  - `src/` — jPSXdec itself (`jpsxdec.Main` is the entry point)
  - `src-lib/` — bundled 3rd-party source (argparser, pdfjet, l2fprod, jhlabs, JSR-305 `javax.annotation`)
  - `src-lgpl/` — LGPL code, built into a separate `jpsxdec-lib.jar` (JXTreeTable, image scaling, some jPSXdec mdec/gui code)
  - `test/` — JUnit 4 tests; `lib-tools/` — junit/hamcrest jars plus Ant and launch4j zips
  - `jPSXdec-design.md` — **the design doc and code styleguide; read it before non-trivial changes**
  - `PlayStation1_STR_format.txt` — reference for the STR video/bitstream/MDEC formats
  - `doc/CHANGES.txt` — changelog
- `laintools/` — separate, incomplete Serial Experiments Lain hacking tools that depend on jPSXdec classes

## Build and test

Ant (`jpsxdec/build.xml`) is used for official builds; for development, any IDE with all source dirs in one project works.
Ant is not on PATH here — it ships as `jpsxdec/lib-tools/apache-ant-1.10.17-bin.zip`. Output goes to `jpsxdec/_ant/`.

```
ant compile            # builds src-lgpl -> jpsxdec-lib.jar, then src-lib + src
ant package            # jpsxdec.jar in _ant/release
ant test               # compile + run AllTestsSuite
ant clean
```

- Target is Java 8 (source/target 1.8). Official builds must use a JDK 8 to guarantee no newer APIs are used.
  The machine currently has JDK 21; with a newer javac pass `-Djavac.args=-Xlint:-options`.
- `src/` compiles with `-Xlint:all -Werror` (only `cast` and `serial` lints disabled) — **any new compiler warning breaks the build**.
- `ant test` hard-fails unless running on Java 8. To run tests on another JDK, compile manually and use JUnitCore, e.g.
  `java -ea -cp "_ant/build;_ant/build-lgpl;_ant/build-test;lib-tools/junit-4.13.2.jar;lib-tools/hamcrest-core-1.3.jar" org.junit.runner.JUnitCore jpsxdec.psxvideo.PsxYCbCrTest`
  (run from `jpsxdec/`; tests need assertions enabled).
- Tests only run through `test/AllTestsSuite.java` — **new test classes must be added to its `@Suite.SuiteClasses` list**.
- Several GUI forms (`*.form`) were made with the NetBeans form designer; the generated code blocks in those `.java`
  files should be edited via NetBeans, not by hand.

## Architecture (big picture)

Layers, bottom up (details per package in `jPSXdec-design.md`):

1. `util` (incl. `util.aviwriter`, `util.player` real-time A/V playback), then `i18n`.
2. Independent core format packages: `cdreaders` (disc image sector reading), `psxvideo` (bitstreams → MDEC codes → IDCT → RGB; `MdecInputStream` is the common interface everything plugs into; `mdec.tojpeg`, `encode`), `adpcm` (XA and SPU audio), `tim`, `iso9660`, `formats`.
3. `modules.IIdentifiedSector` — raw `CdSector`s are wrapped into identified sector types. Subclass constructors follow a fixed pattern: `super(cdSector); if (isSuperInvalidElseReset()) return;` … validate header fields, returning early on failure … `setProbability(100);`
4. `modules.SectorClaimSystem` — every sector is passed through a chain of `ISectorClaimer`s in priority order; the first to claim it wins. Claimers that need lookahead pull later sectors recursively through the earlier claimers. Identified sectors then flow to listeners that demux them into frames/audio.
5. `discitems` / `indexing` — a `DiscItem` is something extractable; it produces a `DiscItemSaverBuilder` (model, also parses command-line options) and a `DiscItemSaverBuilderGui` (Swing panel). `DiscIndexer`s listen to the claim system and build `DiscItem`s into a `DiscIndex`, which is a list + tree (ISO files at the top, items inside them as children). Every `DiscItem` must serialize to/from a string line in the `.idx` index file.
6. `modules.*` — mostly independent per-format/per-game modules (`strvideo`, `xa`, `spu`, `crusader`, `aconcagua`, `eavideo`, `policenauts`, …). `modules.video` holds shared logic: `sectorbased` vs `packetbased` videos, `framenumber` (index / header number / start sector, each with duplicate handling), `save` (the `VDP` "video decoding pipeline" + `AutowireVDP`, `AudioVideoSync`), `replace` (frame replacement; really only viable for sector-based video). Timing uses "presentation sectors" rather than timestamps so disc speed (75 vs 150 sectors/s) is decided late.
7. `gui` (Swing) and `cmdline`, on top of everything; `jpsxdec.Main` starts either.

Adding support for a new game's video usually means a new sector class (often under `modules.strvideo`, extending
`SectorStrVideo`/`SectorAbstractVideo`) registered in `VideoSectorIdentifier` — see Appendix 1 of the design doc.

### Localization rule

Any text shown to the user must be an `ILocalizedMessage`, obtained through a method in `jpsxdec/i18n/I.java`
(never raw strings). Internal logs and internal exception messages stay in English. Adding a user-facing message means
adding an `I.java` method (key + English default) and the same key in `i18n/Translations.properties`
(`_es`, `_it`, `_ja` variants exist). Methods take an `ILocalizedLogger` parameter to report user-visible issues;
`LoggedError` logs then throws. Prefer checked exceptions.

## Code style (from `jPSXdec-design.md` — follow it strictly)

- Hungarian-ish prefixes: fields start with `_` (`__` in nested non-static classes); primitives `lng`, `i`, `si`, `b`,
  `dbl`, `flt`, `bln`, optional `e` for enums; arrays `a`+type (`ai`, `ab`), object arrays `ao`; boxed primitives
  add `o` (`io`, `blno`). Interfaces usually start with `I`. Exception variables are `ex`.
- `@Nonnull` / `@CheckForNull` on every object parameter, return type, and field (few exceptions, see design doc).
- `final` fields and immutability wherever possible; final/non-null fields first; field order matches constructor arg order.
- Java 8 target but **no diamond operator, no lambdas** (rare exception: ≤2-line `Supplier`/`Consumer` with explicit
  param types), minimal streams and never `.forEach()`.
- Opening brace on the same line; wrapped lines indented ≥8 and aligned; favor compact vertical layout; ~80+ chars → consider wrapping.
- Alphabetical imports, no wildcards (static imports last). Files ideally 50–1000 lines.
- Every source file carries the existing license header — copy it into new files.

## Contributing

- Commit messages: imperative, capitalized subject ≤50 chars, no trailing period, body wrapped at 72.
- Per `CONTRIBUTING.md`, code changes target the `master` branch (the `readme` branch is for the GitHub README/templates);
  PRs are squash-merged. Contributions must be MIT or LGPL (or similar permissive) licensed.
