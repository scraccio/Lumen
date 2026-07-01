# Lumen — Presentation Guide

Everything you need to present the Lumen project, including how the on-device machine learning works.
This document is written to be presentation-ready: it has a suggested slide outline, deep-dive notes on
each ML model, a demo script, and a Q&A cheat sheet.

---

## 1. The 30-Second Pitch

**Lumen is an Android news aggregator that runs machine learning entirely on the phone.** It pulls
articles from multiple newspapers, automatically groups stories about the same event together, detects
the political bias of each article, and can summarize a whole cluster of articles into a single readable
brief — all without sending the user's reading data to a server. Everything ML happens locally with ONNX
Runtime.

The core idea: most news apps show you one outlet's version of an event. Lumen shows you the *same event
across multiple outlets side by side*, tells you where each one sits on the political spectrum, and gives
you one combined summary so you can read across the bias instead of inside it.

---

## 2. Key Selling Points (the "why this is interesting" slide)

- **100% on-device ML.** No cloud inference. Privacy-preserving and works offline once articles are cached.
- **Four ML models cooperating** on a phone: sentence embeddings, text classification, and a
  sequence-to-sequence summarizer.
- **Multi-source aggregation** from three real newspapers: The Guardian, The New York Times, Der Spiegel.
- **Semantic clustering**, not keyword matching — articles are grouped by *meaning*, so "Fed raises rates"
  and "Federal Reserve hikes interest" land in the same cluster.
- **Political bias meter** with left / center / right confidence bars.
- **Story following** — pick a story, and the app keeps matching new articles to it and writes you an
  update summary when the story develops.
- **Reading dashboard** — your reading habits, topic mix, and your personal bias balance over the week.

---

## 3. Tech Stack (one slide)

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Hybrid: Android **Views + XML** (feed, reader, stories, dashboard) and **Jetpack Compose** (onboarding, settings) |
| Local DB | **Room** (SQLite) |
| Networking | **Retrofit** + Gson; **Jsoup** for HTML scraping |
| ML runtime | **ONNX Runtime for Android** (`onnxruntime-android:1.17.0`) |
| Async | Kotlin Coroutines + Flow |
| Images | Glide |
| Min / Target SDK | minSdk 32, targetSdk 34 |

No dependency-injection framework — dependencies are wired by hand. API keys are injected at build time
from `local.properties` into `BuildConfig` (not hardcoded).

---

## 4. App Flow / Screens (one slide + a simple diagram)

```
SplashActivity
  ├─► OnboardingActivity (Compose)   — first launch only; user picks topics + sources
  └─► MainActivity (Views)
        ├─ FeedFragment       — clustered article feed with topic filter chips
        ├─ StoryFragment      — stories the user is following
        └─ DashboardFragment  — reading stats & personal bias balance

ArticleActivity        — the reader; runs the bias model + can follow a story
StoryDetailActivity    — a followed story, its sources and update history
ReadingHistoryActivity — list of everything read
SettingsActivity (Compose)
```

- `SplashActivity` checks `SharedPreferences("lumen").onboarding_done` to decide whether to show onboarding.
- Onboarding saves chosen **topics** and **sources** to `SharedPreferences("user_settings")`.
- Sources the user did not pick are skipped both when fetching *and* filtered out of the feed even if
  already cached.

---

## 5. Data Layer (one slide)

Room database (`LumenDatabase`) with these main entities:

- **`Article`** — primary key is the URL. Stores title, source, topic, publish time, image URL, the three
  bias scores + bias label, `clusterId`, `followedStoryId`, read state (`isRead`, `readAt`), and `fetchedAt`
  for cache control.
- **`UserPrefs`** — single-row table; topics and sources stored as comma-separated strings.
- **`FollowedStory`** — a story the user follows: title, keywords, a **384-dim centroid embedding** (stored
  as JSON), the T5 summary, the member article URLs, and update count.
- **`FollowedStoryUpdate`** — one entry per time the story gets new matching articles, with a freshly
  generated update summary.

`NewsRepository` is the single access point for all data + network + ML-glue operations.

**Cache rules:** an article is "fresh" if fetched within the last hour. Articles older than 30 days are
pruned. The feed shows a 14-day window (wider than 7 days because RSS sources aren't date-filtered at the
network layer).

---

## 6. Network Layer & Sources (one slide)

Three news sources, each integrated differently:

- **The Guardian** — official API (`GuardianApiService`), requests `fields=thumbnail` for images.
- **The New York Times** — official API. The API doesn't return full body text, so the body is
  reconstructed from `lead_paragraph` + `abstract` + `snippet`.
- **Der Spiegel** — no API; an **RSS feed** parsed by `SpiegelRssParser`.

`ArticleFetcher` gets the full article body when the user opens an article:
- Guardian → Jsoup HTML scrape of the page.
- NYT → search API by exact URL, with a Jsoup scrape fallback.
- Everything else → Jsoup with a paragraph heuristic (keep `<p>` blocks > 100 chars).

`ArticleMapper` converts each source's network model into the unified `Article` Room entity.

---

## 7. THE ML PIPELINE — the heart of the presentation

All four models are bundled in `app/src/main/assets/` and loaded through ONNX Runtime. They share one
process-wide `OrtEnvironment` singleton.

> **Important implementation detail worth a sentence on a slide:** running multiple ONNX sessions
> concurrently against the shared environment can crash the app natively (SIGABRT in libonnxruntime).
> So every single `session.run(...)` call across all models is serialized behind one lock (`OnnxGate`).
> Inference happens strictly one at a time.

### On-device footprint (good "wow" slide)

| Model | File | Approx size |
|-------|------|-------------|
| MiniLM sentence embedder | `minilm.onnx` | ~23 MB |
| Bias classifier (BERT) | `bias_bert.onnx` | ~109 MB |
| T5 summarizer — encoder | `t5/encoder_model_quantized.onnx` | ~35 MB |
| T5 summarizer — decoder | `t5/decoder_model_quantized.onnx` | ~58 MB |

That's roughly **225 MB of neural networks running on the phone.** The T5 models are **quantized** (8-bit)
to keep them shippable.

ONNX files are kept uncompressed in the APK (`aaptOptions { noCompress "onnx" }`) so they can be
memory-mapped and loaded fast.

---

### 7a. MiniLM — Sentence Embeddings (the foundation)

**What it does:** turns any piece of text (here, an article title) into a **384-dimensional vector** that
captures its *meaning*. Two texts about the same topic produce vectors that point in nearly the same
direction.

**How it works, step by step:**
1. **Tokenization** (`MiniLMTokenizer`): the text is lowercased, split into words, then into sub-word
   pieces using the **WordPiece** algorithm against a vocabulary loaded from `tokenizer.json`. Special
   tokens `[CLS]` and `[SEP]` wrap the sequence; it's padded/truncated to **128 tokens**. Output is three
   arrays: token IDs, attention mask, token-type IDs.
2. **Model run:** the token IDs go through the MiniLM transformer, producing a hidden state of shape
   `[1, 128, 384]` — one 384-dim vector per token.
3. **Mean pooling:** average those per-token vectors, but only over *real* tokens (using the attention
   mask, so padding is ignored).
4. **L2 normalization:** scale the final vector to length 1.

**Why normalize?** Because once vectors are unit-length, **cosine similarity is just the dot product** —
cheap to compute. The similarity between two titles is one multiply-and-sum.

This single model is reused in three places: clustering the feed, computing a followed story's centroid,
and matching new articles to followed stories.

---

### 7b. ArticleClusterer — Grouping by Meaning

**What it does:** takes the list of articles in the feed and groups together the ones that are about the
same event, so the feed shows one card per story (with all the outlets covering it) instead of dozens of
near-duplicate headlines.

**Algorithm — greedy single-pass clustering:**
1. Embed every article title with MiniLM.
2. Walk the list. For each unassigned article, start a new cluster with it.
3. Compare it against every later unassigned article via cosine similarity.
4. If similarity ≥ **0.50** (the threshold), pull that article into the cluster.
5. Sort clusters so the one with the most recent article is on top.

**Talking points:**
- It's *semantic*, not keyword-based — different wording, same event still clusters.
- It's O(n²) in the number of articles, but feed sizes are small, so it's fine.
- The 0.50 threshold is a tuning knob: higher = stricter/more clusters, lower = looser/fewer clusters.
- Runs off the UI thread (`Dispatchers.Default`).

---

### 7c. BiasAnalyzer — Political Bias Detection (BERT)

**What it does:** reads the article body and classifies its political leaning as **left / center / right**,
with a confidence percentage for each.

**How it works:**
1. The article body text is tokenized by `BiasBertTokenizer` (BERT-style, max 128 tokens) into input IDs,
   attention mask, and token-type IDs.
2. The BERT classifier outputs three raw **logits** — one per class.
3. A **softmax** turns the logits into a probability distribution over `[left, center, right]` that sums
   to 100%.
4. The highest-probability class becomes the label; the three probabilities drive the animated bars in the
   reader.

**Where it runs:** in `ArticleActivity`, after the body text is fetched, on a background dispatcher. The
result is shown as three animated bars plus a "Left · 72% confidence" label, and it's **saved back onto the
article row** so the dashboard can aggregate the user's overall bias exposure.

**Honest caveats (good to pre-empt in Q&A):** it analyzes only the first 128 tokens (~a few sentences), and
political bias is inherently subjective — the model reflects the data it was trained on. On failure it
safely defaults to "center."

---

### 7d. T5Summarizer — Abstractive Summarization (the most advanced piece)

**What it does:** takes a cluster of articles (multiple outlets' coverage of one event) and generates a
single **abstractive summary** — new sentences, not copy-paste — plus a short generated headline. Used when
the user follows a story, and when a followed story gets new articles.

**Architecture:** this is a real **encoder–decoder (seq2seq)** transformer (t5-small), shipped as two
separate quantized ONNX models — an encoder and a decoder.

**The clever part — map-reduce summarization:** T5's input is capped at **512 tokens**, which isn't enough
for several full articles. So:
- **Map stage:** summarize each article individually (title + body) into a short "mini-summary" (~90 tokens).
- **Reduce stage:** concatenate the mini-summaries and summarize *that* into the final brief (~220 tokens).
- A single-article cluster skips the map stage (nothing to combine).

This guarantees every article in a cluster reaches the model despite the token cap.

**The decoding loop (how the text is actually generated):**
- The encoder runs **once** to produce a hidden representation of the input.
- The decoder generates the summary **one token at a time** (greedy / autoregressive): pick the
  highest-probability next token, append it, feed it back, repeat — until it emits the End-Of-Sequence
  token or hits the length cap.

**Quality guards built around the weak quantized model (great "engineering depth" talking points):**
- **No-repeat-3-gram constraint:** greedy decoding loves to loop ("X. X. X."). Any token that would repeat
  a 3-gram already in the output is banned (logit set to −∞), forcing the text forward.
- **Instruction-echo stripping:** t5-small sometimes parrots its task label, sometimes in another language
  ("Zusammenfassung:", "Abrégé:"). A leading single-word `label:` prefix is stripped.
- **Sentence trimming:** if generation stops at the length cap mid-sentence, the trailing fragment is cut
  back to the last full sentence.
- **Headline-only fallback:** if no real body text could be scraped, summarizing a bare headline produces
  gibberish, so the headline itself is used as the summary.

---

### 7e. Story Matching (how following a story stays smart)

When the user follows a cluster:
1. MiniLM embeds every member title; the average (then re-normalized) becomes the story's **centroid
   vector**, saved to the DB.
2. Keywords are extracted from the lead article's title (non-stopword tokens).
3. T5 generates the story's summary + headline.

When new articles arrive on every feed refresh (`matchNewArticlesToStories`), a **two-stage filter** decides
if an article belongs to a followed story:
1. **Cheap keyword pre-filter** — the title must contain ≥ 2 of the story's keywords. (Fast; rejects most.)
2. **Semantic confirmation** — only then embed the title with MiniLM and check cosine similarity to the
   story centroid ≥ 0.50.

If it passes, the article is linked to the story and T5 writes a new **update summary**. This cascade is the
key efficiency trick: the expensive embedding only runs on titles that already survived the cheap keyword
gate.

---

## 8. End-to-End Feed Flow (one diagram slide)

1. `FeedFragment` builds a `FeedViewModel` with the repository + an `ArticleClusterer`.
2. `loadArticles()` calls the repo to fetch from the enabled sources → save to Room.
3. It collects all articles as a `Flow`, runs **MiniLM clustering** on a background thread, and emits a
   `List<ArticleCluster>`.
4. `ClusterAdapter` renders one card per cluster. Tapping opens `ArticleActivity` with the cluster's URLs.
5. `ArticleActivity` loads the articles, fetches body text, runs the **BERT bias model**, animates the bars,
   and offers a Follow button (which triggers **T5**).

---

## 9. Suggested Slide Order (copy/paste this as your outline)

1. Title + the 30-second pitch (Section 1)
2. The problem: single-outlet news, hidden bias
3. What Lumen does (Section 2 selling points)
4. Live demo / screenshots
5. Tech stack (Section 3)
6. App flow & screens (Section 4)
7. Architecture: data + network (Sections 5–6)
8. **ML deep dive** — one slide each:
   - The pipeline overview + on-device footprint (Section 7 intro)
   - MiniLM embeddings (7a)
   - Semantic clustering (7b)
   - Bias detection (7c)
   - T5 summarization + map-reduce (7d)
   - Story matching cascade (7e)
9. End-to-end flow diagram (Section 8)
10. Engineering challenges (Section 10)
11. Limitations & future work (Section 11)
12. Q&A

---

## 10. Engineering Challenges to Highlight (shows depth)

- **Running 4 transformer models on a phone in ~225 MB.** Quantizing T5, memory-mapping ONNX, no
  cloud calls.
- **The ONNX concurrency crash.** Sharing one `OrtEnvironment` across models meant concurrent
  `session.run` calls aborted the process natively — solved by serializing all inference behind one lock.
- **T5's 512-token input cap** solved with map-reduce summarization so no article gets dropped.
- **Taming a weak quantized model** with no-repeat-n-gram, instruction-echo stripping, and sentence
  trimming.
- **Multi-source unification** — three very different sources (two APIs + one RSS feed, NYT with no body
  text) mapped into one consistent `Article` model and body-fetch strategy.
- **Cheap-before-expensive filtering** in story matching (keyword gate before embedding) to keep refreshes
  fast.

---

## 11. Limitations & Future Work (honest closing slide)

- Bias model only reads the first ~128 tokens of an article.
- Clustering is greedy and O(n²); fine for a feed, wouldn't scale to thousands of articles.
- t5-small is small and quantized — summaries are decent, not publication-grade.
- Only three sources today; the architecture (mapper + fetcher) makes adding more straightforward.
- No DI framework — fine at this size, would matter as the app grows.

---

## 12. Likely Audience Questions — Cheat Sheet

- **"Is the ML really on-device?"** Yes. ONNX Runtime, no server inference. Articles are fetched over the
  network, but all embedding / classification / summarization runs locally.
- **"How are articles grouped?"** Semantic similarity of MiniLM embeddings (cosine ≥ 0.50), not keywords.
- **"How does bias detection work?"** A BERT classifier outputs logits over left/center/right, softmaxed
  into percentages.
- **"What makes the summarizer special?"** It's a real encoder–decoder T5 doing abstractive (generated)
  summaries with a map-reduce strategy to fit multiple articles under the 512-token limit.
- **"Why is the app big?"** ~225 MB of bundled neural networks — that's the price of offline, private ML.
- **"What's the threshold 0.50?"** A tuned cosine-similarity cutoff for "same story" / "belongs to this
  followed story."
- **"Does it learn from me?"** No online training. The dashboard aggregates your reading; models are fixed.

---

*Generated as a presentation aid. All technical details verified against the current source
(`ml/`, `data/NewsRepository.kt`, `ArticleActivity.kt`, `build.gradle`).*
