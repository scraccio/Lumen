# Design: `NewsSource` Abstraction

**Date:** 2026-06-25
**Status:** Approved (pending spec review)

## Problem

The UI already only ever sees the generic `Article` Room entity — that decoupling exists today via
`ArticleMapper`. The real problem is that **each outlet is defined across four scattered code sites**, so
adding or changing a source means editing four files and it is easy to miss one:

| Concern | Current location |
|---|---|
| Network fetch | `NewsRepository.fetchFromGuardian / fetchFromNyt / fetchFromSpiegel` |
| Map network model → `Article` | `ArticleMapper.fromGuardian / fromNyt / fromSpiegel` (+ per-source date parsers) |
| Onboarding name → `Article.source` label | `NewsRepository.selectedSourceLabels()` (hardcoded branches) |
| Article body fetch | `ArticleFetcher.fetchBody()` URL-`contains` branches → `fetchGuardianBody / fetchNytBody / fetchViaJsoup` |

## Goal

Unify everything about one outlet into a single self-contained class implementing a shared interface.
Adding a source = one new file + one registry line. The rest of the app keeps seeing only `Article`.

## Non-Goals

- No change to the network DTOs (`GuardianResponse`, `NytResponse`, `SpiegelResponse`), the Retrofit
  service interfaces, `RetrofitClient`, `SpiegelRssFetcher`, or `SpiegelRssParser`. Sources consume them.
- No change to ML, DB schema, or UI screens.
- `ArticleActivity.sourceColor` stays in the UI layer (Android `Color` does not belong in a network class).

## Design

### Interface

```kotlin
interface NewsSource {
    /** Name as stored in onboarding SharedPreferences "sources" set, e.g. "The New York Times". */
    val selectionName: String

    /** Value written to Article.source, e.g. "New York Times" (may differ from selectionName). */
    val articleLabel: String

    /** Network fetch + map to generic Article. topic = active filter (null = general feed). */
    suspend fun fetchArticles(topic: String?): List<Article>

    /** True if this source produced the given article URL — used to route body fetch. */
    fun handlesUrl(url: String): Boolean

    /** Full article body text, or null if it could not be retrieved. */
    suspend fun fetchBody(url: String, title: String): String?
}
```

### Registry — the single list to edit

```kotlin
object NewsSources {
    val all: List<NewsSource> = listOf(GuardianSource(), NytSource(), SpiegelSource())

    fun selected(names: Set<String>): List<NewsSource> =
        all.filter { it.selectionName in names }

    fun forUrl(url: String): NewsSource? =
        all.firstOrNull { it.handlesUrl(url) }
}
```

### What collapses into each source class

| Old scattered code | Moves into (per source) |
|---|---|
| `ArticleMapper.fromX()` + `parseXDate()` | private `toArticle()` |
| `NewsRepository.fetchFromX()` (+ date-window helpers) | `fetchArticles()` |
| `selectedSourceLabels()` branch | `articleLabel` property |
| `ArticleFetcher.fetchXBody()` URL branch | `fetchBody()` + `handlesUrl()` |

`ArticleMapper.kt` is **deleted** — its mapping + date-parsing logic moves into each source so everything
about an outlet lives in one file.

### File layout

```
network/source/
  NewsSource.kt      — interface + NewsSources registry
  GuardianSource.kt  — fetch + map + dates + body + labels
  NytSource.kt
  SpiegelSource.kt
  JsoupScraper.kt    — shared body-scrape helper, extracted from ArticleFetcher
```

### `ArticleFetcher` becomes a thin router

`ArticleFetcher` has many callers (`ArticleActivity`, `NewsRepository.matchNewArticlesToStories`) and is
injected into `NewsRepository`. To keep the blast radius small it stays as the public entry point and just
delegates to the registry:

```kotlin
suspend fun fetchBody(url: String, title: String? = null): String? =
    NewsSources.forUrl(url)?.fetchBody(url, title.orEmpty())
        ?: JsoupScraper.scrape(url)
```

`JsoupScraper` is the generic paragraph-heuristic scrape extracted verbatim from the current
`ArticleFetcher.fetchViaJsoup` (selector list + `<p>` > 100-char fallback). Guardian and Spiegel reuse it;
NYT calls its search API first and falls back to it.

### `NewsRepository` becomes generic

Fetch loop — no per-source branches:

```kotlin
val all = NewsSources.selected(selectedSources())
    .flatMap { runCatching { it.fetchArticles(topic) }.getOrElse { emptyList() } }
    .distinctBy { it.url }
```

`runCatching` per source preserves current behaviour: one source failing does not kill the whole feed.

Cached-row filter:

```kotlin
private fun selectedSourceLabels(): Set<String> =
    NewsSources.selected(selectedSources()).map { it.articleLabel }.toSet()
```

API keys (`BuildConfig.GUARDIAN_API_KEY`, `BuildConfig.NYT_API_KEY`) move to the source that owns them, so
each key has a single reader instead of duplicate copies in `NewsRepository` and `ArticleFetcher`.

## German-Artifact Filtering in `SpiegelSource`

### Why

Der Spiegel uses the **English** International feed (`spiegel.de/international/index.rss`) on purpose — the
whole ML pipeline (MiniLM, bias BERT, T5) is English-only. But the article *body* is scraped from the page
with Jsoup, and the broad `<p>` fallback pulls in German page chrome (cookie/newsletter prompts, teasers,
footer). That German text then flows into T5 summaries and the bias model, degrading both.

### Approach (chosen: German-stopword ratio filter)

A pure, dependency-free per-paragraph filter applied inside `SpiegelSource.fetchBody()` after scraping:

1. Split the scraped body into paragraphs (already newline-separated by `JsoupScraper`).
2. For each paragraph, compute a "German score":
   - ratio of tokens that are common German function words
     (`der die das und ist für mit auf eine ein wird werden nicht von zu im`), plus
   - presence of German-only characters (`ß ä ö ü`).
3. Drop the paragraph if its German score exceeds a tuned threshold.
4. Re-join the surviving paragraphs.

Properties:
- Works regardless of where the German came from; survives Spiegel DOM redesigns; adds no model/asset.
- Heuristic: threshold is tunable; risk is nicking a rare English paragraph that quotes German.

Rejected alternatives:
- **Tighter Spiegel CSS selectors** — precise but brittle (hardcoded to current DOM).
- **MediaPipe LanguageDetector** — accurate but needs a `.tflite` asset + load/lock plumbing; overkill for
  stripping page chrome.

The filter is a private helper in `SpiegelSource`; nothing else references it.

## English-Only Source Constraint (documented invariant)

The abstraction does not handle translation. Any `NewsSource` added in future **must yield English text**
(or include its own translation step), because MiniLM clustering, bias classification, and T5 summarization
are all English-only. This is called out so a future German source (e.g. Spiegel domestic) is not added
naively. This note belongs in the source-package KDoc / `NewsSource` interface doc.

## Testing

The new logic that is worth testing is pure and Android-free:

- `NewsSources.selected(names)` — returns only sources whose `selectionName` is in the set.
- `NewsSources.forUrl(url)` — routes to the source whose `handlesUrl` matches; null when none match.
- The German-paragraph filter — given a mixed English/German body, returns English-only paragraphs;
  given all-English, returns it unchanged.

These can be exercised with a fake `NewsSource` and plain JUnit (no device). Mapping (`toArticle`) and
date parsing are moved-not-changed code carried over from `ArticleMapper`.

## Migration / Blast Radius

- New: `network/source/` package (5 files).
- Deleted: `ArticleMapper.kt`.
- Edited: `NewsRepository` (fetch loop + `selectedSourceLabels`, drop `fetchFromX` + date helpers + key
  fields), `ArticleFetcher` (becomes router; `fetchViaJsoup` extracted to `JsoupScraper`).
- Unchanged: DTOs, Retrofit services, `RetrofitClient`, `SpiegelRssFetcher`, `SpiegelRssParser`, all ML,
  all UI, DB schema.

## Optional Follow-Up (not in this scope)

Onboarding currently hardcodes the selectable source names. It could instead read
`NewsSources.all.map { it.selectionName }`, removing a fifth scatter point. Confirm during implementation;
include only if it is a clean drop-in.
