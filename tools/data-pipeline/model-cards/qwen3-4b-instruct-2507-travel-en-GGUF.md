---
license: cc-by-sa-4.0
base_model: Qwen/Qwen3-4B-Instruct-2507
base_model_relation: finetune
language:
- en
pipeline_tag: text-generation
library_name: gguf
tags:
- gguf
- llama.cpp
- travel
- rag
- lora
- pocket-travel
---

# qwen3-4b-instruct-2507-travel-en-GGUF

Offline travel assistant for the [Pocket Travel](https://github.com/miracle091/pocket-travel) Android app:
a LoRA fine-tune of [Qwen/Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), merged and quantized to GGUF Q4_K_M, that
answers **in English, in at most 3 sentences, using only the travel-guide CONTEXT it is given**, and says so
explicitly when the context does not contain the answer.

| File | Size | Quantization | Memory in use | Suggested device RAM |
|---|---|---|---|---|
| `qwen3-4b-instruct-2507-travel-en-Q4_K_M.gguf` | 2,382 MiB | Q4_K_M with importance matrix | about 4.6 GiB | 12 GB or more |

Memory in use: weights, the CPU copy of repacked weights, KV cache and compute buffers, measured with llama.cpp on
CPU with the app's settings (context 4096 tokens, batch 512, f16 KV cache). With about 0.4 GB for the app itself, model
and app take about 40% of the suggested device RAM or less, leaving the rest to the system and other apps.

## Intended use

Retrieval-augmented answers on a phone, fully offline: the app searches the downloaded
Wikivoyage guide of a region (SQLite FTS), puts 1 to 3 sections in the prompt and asks the question. The
model is **not** meant as a general chatbot or knowledge source: without a context it should refuse.
The app uses this model when its interface is in English.

Prompt format (user turn of the model's chat template, as sent by the app):

```text
You are an offline travel guide.
Answer in at most 3 sentences, in English, using only the information in the CONTEXT.
If the context is not enough, say so explicitly.

CONTEXT: <guide sections>

QUESTION: <question>
```

Example with llama.cpp (context 4096, as in the app):

```bash
llama-server -m qwen3-4b-instruct-2507-travel-en-Q4_K_M.gguf -c 4096
```

## Evaluation

Held-out test set of 584 questions over 11 countries never seen in training (and their cities), phrased
outside the training templates and with contexts shaped exactly like the app's: 239 answerable questions on the
countries' guides, 41 paraphrased questions whose topic is missing from the context, 110 off-topic and 44 with
an empty context, 50 answerable and 20 unanswerable city questions, and smaller groups on the history and climate of
cities, nearby places and next departures. This model and its base model were evaluated as GGUF with llama.cpp
(greedy decoding), on the same questions and with the same prompt; the base model is the official
[unsloth/Qwen3-4B-Instruct-2507-GGUF](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF) quantization (UD-Q4_K_XL) that the app offers in the same
memory tier. Refusal rates: higher is better on the first block, lower is better on the second.

| Metric | This model | Base model (Qwen3-4B-Instruct-2507) |
|---|---|---|
| Refuses when the context is empty (no guide text found) | 100% | 100% |
| Refuses off-topic questions | 100% | 94.5% |
| Refuses paraphrased country questions whose topic is missing from the context | 97.6% | 87.8% |
| Refuses city questions whose topic is missing from the page | 90% | 80% |
| Refuses city history or climate questions the context does not cover | 100% | 100% |
| Refuses "what is near me" questions with no matching place nearby | 100% | 80% |
| Refuses departure questions with no matching departure | 100% | 70% |
| Wrongly refuses answerable country questions | 2.5% | 16.7% |
| Wrongly refuses answerable city questions | 0% | 14% |
| Wrongly refuses answerable city history or climate questions | 0% | 0% |
| Wrongly refuses answerable "what is near me" questions | 14.3% | 0% |
| Wrongly refuses answerable departure questions | 7.1% | 14.3% |

A refusal is any recognizable "the context does not say" answer, counted with the same rule for both models (the base
model words it in its own way). The base model's low rate of wrong refusals on some groups comes with few refusals
overall: where it does not refuse, it often answers from its own knowledge or with text from the context that does
not answer the question. This test set is new with this version (more groups, contexts with the app's extra blocks),
so the numbers are not comparable with the previous version's card.

## Training

- Method: LoRA (16-bit, Unsloth) merged into the base weights, 1 epoch, loss on answer tokens only, weighted by
  question category.
- Data: 40,935 synthetic question/answer pairs with extractive answers, about 21% of them refusal examples, built
  from the Wikimedia dumps of Oct 1, 2026: English Wikivoyage (country and city pages, cleaned with the same code as
  the app's guides), English Wikipedia (country articles on cuisine, culture, telecommunications and media; history
  and climate of cities), sections of Italian Wikivoyage machine-translated into English where the English page has
  none (MarianMT `Helsinki-NLP/opus-mt-tc-big-it-en`, CC BY 4.0, marked as such in `ATTRIBUTION.tsv`), the travel
  advice of the Government of Canada (travel.gc.ca) and of the UK Foreign, Commonwealth & Development Office
  (gov.uk) as published in the app's English guides, quick facts from Wikidata (capital, currency, calling code,
  driving side), emergency numbers and vaccination summaries. Contexts are shaped like the app's: 1 to 3 guide sections
  with distractor sections, up to 2000 characters, plus the blocks the app adds (nearby points of interest, next
  departures, distances between cities, emergency numbers, personal notes).
- Questions only (answers are always taken from the context, or are the refusal sentence): user-style travel questions
  (written by a model imitating the user) from [soniawmeyer/travel-conversations-finetuning](https://huggingface.co/datasets/soniawmeyer/travel-conversations-finetuning)
  (UltraChat part, MIT); off-topic questions from
  [truthful_qa](https://huggingface.co/datasets/truthfulqa/truthful_qa) (Apache 2.0) and
  [alpaca-cleaned](https://huggingface.co/datasets/yahma/alpaca-cleaned) (CC BY 4.0 as declared by the copy; the
  original Stanford Alpaca data is CC BY-NC 4.0), always with a refusal as answer.
- Quantization: `llama-quantize` Q4_K_M with an importance matrix calibrated on 400 training prompts.
- Text only: the base model is text only.

## Limitations

- Answers are only as good and as current as the guide text in the context: check prices, opening hours,
  entry requirements and safety information with official sources before traveling.
- Trained to answer in English; questions in other languages get English answers.
- It sometimes answers city questions whose topic is not on the city page instead of refusing (see the table), and
  it may refuse "what is near me" questions that name the place with a synonym of the label in the context.
- It may reproduce sentences of the source texts verbatim (see license below).

## License and attribution

- Base model: [Qwen/Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), Apache-2.0; its license text is included as
  `LICENSE-base-model`.
- Training data: Wikivoyage and Wikipedia (mostly English, some Italian sections machine-translated), CC BY-SA 4.0.
  Because the model can reproduce that text, the fine-tuned weights are released under **CC BY-SA 4.0**. The full list of source pages (title, URL, license) is in `ATTRIBUTION.tsv`.
- Travel advice from travel.gc.ca: contains information licensed under the
  [Open Government Licence - Canada](https://open.canada.ca/en/open-government-licence-canada) (OGL-Canada 2.0).
- Travel advice from the FCDO on gov.uk: contains public sector information licensed under the
  [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/).
- Vaccination summaries from Travel.gc.ca (OGL-Canada 2.0: contains information licensed under the Open Government
  Licence - Canada) and from TravelHealthPro (UKHSA / NaTHNaC, OGL v3.0: contains public sector information licensed
  under the Open Government Licence v3.0); emergency numbers from Travel.gc.ca and gov.uk (same licenses), Wikipedia,
  Wikivoyage and Wikidata.
- Wikidata (quick facts, emergency numbers): CC0 1.0.
- No content from Viaggiare Sicuri (Farnesina) is included.
