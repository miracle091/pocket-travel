---
license: cc-by-sa-4.0
base_model: Qwen/Qwen3.5-0.8B
base_model_relation: finetune
language:
- en
pipeline_tag: text-generation
library_name: gguf
datasets:
- soniawmeyer/travel-conversations-finetuning
- truthfulqa/truthful_qa
- yahma/alpaca-cleaned
- PaDaS-Lab/webfaq
- apple/mkqa
tags:
- gguf
- llama.cpp
- travel
- rag
- lora
- pocket-travel
---

# qwen3.5-0.8b-travel-en-GGUF

Offline travel assistant of the [Pocket Travel](https://github.com/miracle091/pocket-travel) Android app, in English:
a LoRA fine-tune of [Qwen/Qwen3.5-0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B) that answers in at most 3 sentences using only the
travel-guide CONTEXT in the prompt, and says so when the context does not contain the answer. It is not a general
chatbot or knowledge source: without a context it refuses.

The six models trained for the app (0.8B, 2B and 4B, in Italian and English) are in the
[Pocket Travel collection](https://huggingface.co/collections/pockettravel/pocket-travel).

## Files

| File | Version | Quantization | Size | Memory in use | Suggested device RAM | Refuses unanswerable questions | Wrongly refuses answerable questions |
|---|---|---|---|---|---|---|---|
| `qwen3.5-0.8b-travel-en-Q5_K_M.gguf` | **current** | Q5_K_M with importance matrix | 528 MiB | 623 MiB | 4 GB or more | 98.2% | 8.7% |
| `qwen3.5-0.8b-travel-en-Q4_K_M.gguf` | outdated | Q4_K_M with importance matrix | 505 MiB | 600 MiB | 4 GB or more | 95.4% | 18.2% |

> **Outdated file, for tests only.** `qwen3.5-0.8b-travel-en-Q4_K_M.gguf` is the previous version of this model, trained on an older
> dataset: it wrongly refuses more of the questions it could answer (see the table) and will not be updated.
> It stays in the repository only because Pocket Travel 0.13 and earlier download it, and to compare
> the two versions. For anything else use `qwen3.5-0.8b-travel-en-Q5_K_M.gguf`.

**Why Q5_K_M.** The previous Pocket Travel models were Q4_K_M. Measured on the same weights (those of the previous version), this Q5_K_M stays closer to the
unquantized 16-bit model: the mean KL divergence on held-out guide text drops from 0.043 to
0.014 (67% less), for a file 5% larger, and the evaluation results are not
worse. The gap in refusal rates between the two files in the table comes from the newer dataset.

The two refusal rates come from the evaluation below: all the questions to refuse, and all the questions whose answer
is in the context. In the Q5_K_M file, besides the Q6_K tensors of the standard Q5_K_M (half of `ffn_down` and part of
`attn_qkv`), the full-attention and `ssm_out` tensors are in Q6_K; the token embeddings, shared with the output layer,
are in Q5_K. Memory in use covers weights, KV cache, recurrent state and compute buffers, measured with llama.cpp on an
x86 CPU with the app's settings (context 4096, batch 512, f16 KV cache). When llama.cpp copies weights into a faster
layout at load time (Q4_K on x86 and ARM, Q5_K and Q6_K too on ARM), the file pages it copied from stay only as cache
that the system can free, and they are not counted. With about 0.4 GB for the app, the Q5_K_M model and the app take
about 24% of the suggested device RAM.

## Usage

The app searches the downloaded Wikivoyage guide of a region (SQLite FTS) and sends 1 to 3 sections with the question
as the user turn:

```text
You are an offline travel guide.
Answer in at most 3 sentences, in English, using only the information in the CONTEXT.
If the context is not enough, say so explicitly.

CONTEXT: <guide sections>

QUESTION: <question>
```

The chat template is plain ChatML without thinking (no `<think>` block, no `enable_thinking`), and the model is text
only (no `mmproj`). With llama.cpp, context 4096 as in the app:

```bash
llama-server -m qwen3.5-0.8b-travel-en-Q5_K_M.gguf -c 4096
```

## Evaluation

Held-out test of 899 questions on 11 countries never seen in training and their cities, phrased outside the
training templates, with contexts built like the app's. Both models run as GGUF in llama.cpp with greedy decoding and
the same prompt; the base model is [unsloth/Qwen3.5-0.8B-GGUF](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF) UD-Q4_K_XL, the Official model the app offers in the same RAM tier. A refusal is any "the context does not say" answer, counted with the
same rule for both models.

| Group (questions) | This model | Base model (Qwen3.5-0.8B) |
|---|---|---|
| **Refuses** (higher is better) | | |
| Empty context, no guide text found (44) | 100% | 9.1% |
| Off-topic questions (110) | 100% | 56.4% |
| Country questions whose topic is missing from the context (50) | 98% | 12% |
| City questions whose topic is missing from the page (130) | 97.7% | 18.5% |
| City history or climate questions the context does not cover (8) | 100% | 0% |
| "What is near me" questions with no matching place nearby (40) | 95% | 32.5% |
| Departure questions with no matching departure (10) | 90% | 50% |
| **Wrongly refuses answerable questions** (lower is better) | | |
| Country questions (279) | 5.7% | 3.9% |
| City questions (70) | 0% | 2.9% |
| City history or climate questions (24) | 0% | 0% |
| "What is near me" questions (120) | 22.5% | 18.3% |
| Departure questions (14) | 7.1% | 21.4% |

The base model's low share of wrong refusals comes with few refusals overall: where it does not refuse, it often
answers from its own knowledge or with text that does not answer the question. With an empty context it still
answered 40 of the 44 questions, all from its own knowledge.

## Training

- LoRA (16-bit, Unsloth; rank 16, alpha 32, learning rate 2e-4, effective batch 16, sequences up to 1024 tokens),
  1 epoch, loss on answer tokens only and weighted by question category, merged into the base weights.
- 42,466 synthetic question/answer pairs, about 22% of them refusals, with answers extracted from the
  Wikimedia dumps of Oct 1, 2026: English Wikivoyage country and city pages (cleaned with the app's code),
  English Wikipedia (cuisine, culture, telecommunications and media of countries; history and climate of cities),
  Italian Wikivoyage sections machine-translated where the English page has none (MarianMT
  `Helsinki-NLP/opus-mt-tc-big-it-en`), the travel advice of the Government of Canada (travel.gc.ca) and of the UK Foreign, Commonwealth
  & Development Office (gov.uk) as published in the app's English guides, Wikidata quick facts (capital, currency, calling code, driving side), emergency
  numbers and vaccination summaries.
- Contexts built like the app's: 1 to 3 guide sections with distractor sections, up to 2000 characters, plus the
  blocks the app adds (nearby points of interest, next departures, distances between cities, emergency numbers,
  personal notes).
- Questions only, never their answers: travel questions written by a model imitating the user, from the UltraChat part of [soniawmeyer/travel-conversations-finetuning](https://huggingface.co/datasets/soniawmeyer/travel-conversations-finetuning);
  off-topic questions from [truthful_qa](https://huggingface.co/datasets/truthfulqa/truthful_qa), [alpaca-cleaned](https://huggingface.co/datasets/yahma/alpaca-cleaned) and
  [MKQA](https://github.com/apple-aiml-research/ml-mkqa); questions from the FAQs of hotel and tourist service sites
  in [WebFAQ](https://huggingface.co/datasets/PaDaS-Lab/webfaq), on matters the guides do not cover (check-in, pets,
  breakfast, cancellations). Off-topic and FAQ questions always get a refusal as answer.

## Limitations

- Answers are only as good and as current as the guide text in the context: check prices, opening hours, entry
  requirements and safety information with official sources before traveling.
- It answers in English, whatever the language of the question.
- It may refuse "what is near me" questions that name the place with a synonym of the label in the
  context (see the table).
- It may reproduce sentences of the source texts verbatim (see the license).

## License and sources

- Weights: **[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)**, because the model can reproduce the [Wikivoyage](https://www.wikivoyage.org/)
  and [Wikipedia](https://www.wikipedia.org/) text it was trained on (CC BY-SA 4.0). Every source page (title, URL, license) and the revision of every dataset are in `ATTRIBUTION.tsv`.
- Base model: [Qwen/Qwen3.5-0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B), [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0), license text in `LICENSE-base-model`.
- Machine translation: MarianMT [`Helsinki-NLP/opus-mt-tc-big-it-en`](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-it-en),
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); translated sections are marked in `ATTRIBUTION.tsv`.
- Travel advice: [travel.gc.ca](https://travel.gc.ca/), "contains information licensed under the
  [Open Government Licence - Canada](https://open.canada.ca/en/open-government-licence-canada)"; FCDO on [gov.uk](https://www.gov.uk/foreign-travel-advice),
  "contains public sector information licensed under the
  [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/)".
- Vaccination summaries: [Travel.gc.ca](https://travel.gc.ca/)
  ([OGL-Canada 2.0](https://open.canada.ca/en/open-government-licence-canada), "contains information licensed under the Open Government
  Licence - Canada") and [TravelHealthPro](https://travelhealthpro.org.uk/) by UKHSA / NaTHNaC
  ([OGL v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/), "contains public sector information licensed
  under the Open Government Licence v3.0"). Emergency numbers: Travel.gc.ca and [gov.uk](https://www.gov.uk/foreign-travel-advice) (same licenses),
  Wikipedia, Wikivoyage and Wikidata.
- [Wikidata](https://www.wikidata.org/): [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
- Questions: [soniawmeyer/travel-conversations-finetuning](https://huggingface.co/datasets/soniawmeyer/travel-conversations-finetuning)
  ([MIT](https://opensource.org/license/mit)),
  [truthful_qa](https://huggingface.co/datasets/truthfulqa/truthful_qa) ([Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)),
  [alpaca-cleaned](https://huggingface.co/datasets/yahma/alpaca-cleaned)
  ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) as declared by the copy; the original [Stanford Alpaca](https://github.com/tatsu-lab/stanford_alpaca) data is
  [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)), [WebFAQ](https://huggingface.co/datasets/PaDaS-Lab/webfaq) by PaDaS-Lab
  ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)), [MKQA](https://github.com/apple-aiml-research/ml-mkqa) by Apple
  ([CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)).
- No content from [Viaggiare Sicuri](https://www.viaggiaresicuri.it/) (Farnesina) is included.
