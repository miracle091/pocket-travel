---
license: cc-by-sa-4.0
base_model: Qwen/Qwen3-4B-Instruct-2507
base_model_relation: finetune
language:
- it
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

# qwen3-4b-instruct-2507-travel-it-GGUF

Offline travel assistant for the [Pocket Travel](https://github.com/miracle091/pocket-travel) Android app:
a LoRA fine-tune of [Qwen/Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), merged and quantized to GGUF Q4_K_M, that
answers **in Italian, in at most 3 sentences, using only the travel-guide CONTEXT it is given**, and says so
explicitly when the context does not contain the answer.

> *In italiano:* assistente di viaggio offline per l'app Pocket Travel. Risponde in italiano, in poche frasi,
> solo con le informazioni della guida passate nel CONTESTO, e dice chiaramente quando mancano.

| File | Size | Quantization | Suggested device RAM |
|---|---|---|---|
| `qwen3-4b-instruct-2507-travel-it-Q4_K_M.gguf` | 2382 MiB | Q4_K_M with importance matrix | 12 GB |

## Intended use

Retrieval-augmented answers on a phone, fully offline: the app searches the downloaded
Wikivoyage guide of a region (SQLite FTS), puts 1-3 sections in the prompt and asks the question. The
model is **not** meant as a general chatbot or knowledge source: without a context it should refuse.

Prompt format (user turn of the model's chat template, as sent by the app):

```text
Sei una guida turistica offline.
Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.
Se il contesto non basta, dillo esplicitamente.

CONTESTO: <guide sections>

DOMANDA: <question>
```

Example with llama.cpp (context 8192, as in the app):

```bash
llama-server -m qwen3-4b-instruct-2507-travel-it-Q4_K_M.gguf -c 8192
```

## Evaluation

Held-out test set of 428 hand-written questions over 11 countries never seen in training, phrased outside
the training templates and with contexts shaped exactly like the app's (same text cleaning, line breaks and
subheadings): 358 about the countries' guides and 70 about their cities (city pages of Italian Wikivoyage).
Caveat: one of the 11 test countries, Fiji (Lau Islands), shares its Wikivoyage page with a training region
(Western Fiji), so about 50 of its test rows had a paragraph also seen in training and its scores are slightly
optimistic. The dataset generator now excludes regions that share a page with a test region.
The previous version was measured again on this same set. GGUF evaluated with llama.cpp. Refusal rates (higher is better on negatives, lower is better
on positives) and answer overlap with the expected extract (token F1, 0-1, higher is better):

| Metric | This model | Previous version | Base model (Unsloth UD-Q4_K_XL) |
|---|---|---|---|
| Answer overlap, country questions (F1) | 0.67 | 0.68 | – |
| Answer overlap, city questions (F1) | 0.78 | 0.64 | – |
| Refuses when the context has no useful information | 100% | 100% | 100% |
| Refuses off-topic questions | 100% | 97% | 94% |
| Refuses paraphrased questions whose topic is missing from the context | 98% | 93% | 58% |
| Refuses city questions whose topic is missing from the page | 85% | 85% | – |
| Wrongly refuses answerable country questions | 0% | 4% | 9% |
| Wrongly refuses answerable city questions | 2% | 4% | – |

A refusal is any recognizable "the context does not say" answer. The base model was run with the same
prompt on the earlier 361-question set (contexts as plain text); it rarely uses the exact refusal sentence this model was trained on.

## Training

- Method: LoRA (16-bit, Unsloth) merged into the base weights, 1 epoch, loss on answer tokens only.
- Data: 15,307 synthetic question/answer pairs with extractive answers, built from Italian Wikivoyage
  (country and city pages, cleaned with the same code as the app's guides) and Italian Wikipedia (country articles on cuisine, culture,
  telecommunications, media) from the Wikimedia dumps of 2026-09-01, about one quarter refusal examples,
  some questions in English, contexts shaped like the app's (1-3 sections, distractor sections, up to 2000
  characters), plus questions on the quick facts the app shows (language, plugs, time zone, emergency
  numbers) and on short personal notes added to the context. Off-topic questions for refusal examples come from
  [truthful_qa_italian](https://huggingface.co/datasets/sapienzanlp/truthful_qa_italian) (Apache 2.0) and
  [alpaca-cleaned-italian](https://huggingface.co/datasets/DanielSc4/alpaca-cleaned-italian) (CC BY 4.0);
  only their questions are used, always with a refusal as answer.
- Quantization: `llama-quantize` Q4_K_M with an importance matrix calibrated on 400 training prompts.
- Text only: the base model is text only.

## Limitations

- Answers are only as good and as current as the guide text in the context: check prices, opening hours,
  entry requirements and safety information with official sources before travelling.
- Trained to answer in Italian; English questions get Italian answers.
- It may reproduce sentences of the source guides verbatim (see licence below).

## Licence and attribution

- Base model: [Qwen/Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), Apache-2.0; its licence text is included as
  `LICENSE-base-model`.
- Training data: Wikivoyage and Wikipedia (Italian), CC BY-SA 4.0. Because the model can reproduce that
  text, the fine-tuned weights are released under **CC BY-SA 4.0**. The full list of source pages
  (title, URL, licence) is in `ATTRIBUTION.tsv`. English Wikivoyage was used only as context for refusal
  examples, never as an answer target.
- No content from Viaggiare Sicuri (Farnesina) is included.
