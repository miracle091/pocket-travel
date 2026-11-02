---
license: cc-by-sa-4.0
base_model: {{base_model}}
base_model_relation: finetune
language:
- {{language}}
pipeline_tag: text-generation
library_name: gguf
datasets:
- soniawmeyer/travel-conversations-finetuning
<!-- it -->
- sapienzanlp/truthful_qa_italian
- DanielSc4/alpaca-cleaned-italian
<!-- /it -->
<!-- en -->
- truthfulqa/truthful_qa
- yahma/alpaca-cleaned
<!-- /en -->
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

# {{repo}}

Offline travel assistant of the [Pocket Travel](https://github.com/miracle091/pocket-travel) Android app, in {{language_name}}:
a LoRA fine-tune of [{{base_model}}](https://huggingface.co/{{base_model}}) that answers in at most 3 sentences using only the
travel-guide CONTEXT in the prompt, and says so when the context does not contain the answer. It is not a general
chatbot or knowledge source: without a context it refuses.

The six models trained for the app (0.8B, 2B and 4B, in Italian and English) are in the
[Pocket Travel collection]({{collection}}).

<!-- it -->
> *In italiano:* assistente di viaggio offline dell'app Pocket Travel. Risponde in italiano, in massimo 3 frasi,
> solo con le informazioni della guida passate nel CONTESTO, e dice chiaramente quando mancano.

<!-- /it -->
## Files

{{files_table}}

{{outdated_note}}

{{quant_reason}}

The two refusal rates come from the evaluation below: all the questions to refuse, and all the questions whose answer
is in the context. In the Q5_K_M file, besides the Q6_K tensors of the standard Q5_K_M (half of `ffn_down` and part of
`attn_qkv`), the full-attention and `ssm_out` tensors are in Q6_K; the token embeddings, shared with the output layer,
are in Q5_K. Memory in use covers weights, KV cache, recurrent state and compute buffers, measured with llama.cpp on an
x86 CPU with the app's settings (context 4096, batch 512, f16 KV cache). When llama.cpp copies weights into a faster
layout at load time (Q4_K on x86 and ARM, Q5_K and Q6_K too on ARM), the file pages it copied from stay only as cache
that the system can free, and they are not counted. With about 0.4 GB for the app, the Q5_K_M model and the app take
about {{ram_percent}}% of the suggested device RAM.

## Usage

The app searches the downloaded Wikivoyage guide of a region (SQLite FTS) and sends 1 to 3 sections with the question
as the user turn:

```text
<!-- it -->
Sei una guida turistica offline.
Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.
Se il contesto non basta, dillo esplicitamente.

CONTESTO: <guide sections>

DOMANDA: <question>
<!-- /it -->
<!-- en -->
You are an offline travel guide.
Answer in at most 3 sentences, in English, using only the information in the CONTEXT.
If the context is not enough, say so explicitly.

CONTEXT: <guide sections>

QUESTION: <question>
<!-- /en -->
```

The chat template is plain ChatML without thinking (no `<think>` block, no `enable_thinking`), and the model is text
only (no `mmproj`). With llama.cpp, context 4096 as in the app:

```bash
llama-server -m {{file}} -c 4096
```

## Evaluation

Held-out test of {{test_questions}} questions on 11 countries never seen in training and their cities, phrased outside the
training templates, with contexts built like the app's. Both models run as GGUF in llama.cpp with greedy decoding and
the same prompt; the base model is [{{base_gguf}}](https://huggingface.co/{{base_gguf}}) UD-Q4_K_XL, {{base_role}}. A refusal is any "the context does not say" answer, counted with the
same rule for both models.

{{results_table}}

{{results_note}}

## Training

- LoRA (16-bit, Unsloth; rank 16, alpha 32, learning rate 2e-4, effective batch 16, sequences up to 1024 tokens),
  1 epoch, loss on answer tokens only and weighted by question category, merged into the base weights.
- {{dataset_rows}} synthetic question/answer pairs, about {{dataset_refusals}}% of them refusals, with answers extracted from the
<!-- it -->
  Wikimedia dumps of Oct 1, 2026: Italian Wikivoyage country and city pages (cleaned with the app's code),
  Italian Wikipedia (cuisine, culture, telecommunications and media of countries; history and climate of cities),
  English Wikivoyage sections machine-translated where the Italian page has none (MarianMT
  `Helsinki-NLP/opus-mt-tc-big-en-it`), Wikidata quick facts (capital, currency, calling code, driving side), emergency
<!-- /it -->
<!-- en -->
  Wikimedia dumps of Oct 1, 2026: English Wikivoyage country and city pages (cleaned with the app's code),
  English Wikipedia (cuisine, culture, telecommunications and media of countries; history and climate of cities),
  Italian Wikivoyage sections machine-translated where the English page has none (MarianMT
  `Helsinki-NLP/opus-mt-tc-big-it-en`), the travel advice of the Government of Canada (travel.gc.ca) and of the UK Foreign, Commonwealth
  & Development Office (gov.uk) as published in the app's English guides, Wikidata quick facts (capital, currency, calling code, driving side), emergency
<!-- /en -->
  numbers and vaccination summaries.
- Contexts built like the app's: 1 to 3 guide sections with distractor sections, up to 2000 characters, plus the
  blocks the app adds (nearby points of interest, next departures, distances between cities, emergency numbers,
  personal notes).
<!-- it -->
- Questions only, never their answers: travel questions written by a model imitating the user, from the UltraChat part of [soniawmeyer/travel-conversations-finetuning](https://huggingface.co/datasets/soniawmeyer/travel-conversations-finetuning),
  machine-translated into Italian;
  off-topic questions from [truthful_qa_italian](https://huggingface.co/datasets/sapienzanlp/truthful_qa_italian), [alpaca-cleaned-italian](https://huggingface.co/datasets/DanielSc4/alpaca-cleaned-italian) and
<!-- /it -->
<!-- en -->
- Questions only, never their answers: travel questions written by a model imitating the user, from the UltraChat part of [soniawmeyer/travel-conversations-finetuning](https://huggingface.co/datasets/soniawmeyer/travel-conversations-finetuning);
  off-topic questions from [truthful_qa](https://huggingface.co/datasets/truthfulqa/truthful_qa), [alpaca-cleaned](https://huggingface.co/datasets/yahma/alpaca-cleaned) and
<!-- /en -->
  [MKQA](https://github.com/apple-aiml-research/ml-mkqa); questions from the FAQs of hotel and tourist service sites
  in [WebFAQ](https://huggingface.co/datasets/PaDaS-Lab/webfaq), on matters the guides do not cover (check-in, pets,
  breakfast, cancellations). Off-topic and FAQ questions always get a refusal as answer.

## Limitations

- Answers are only as good and as current as the guide text in the context: check prices, opening hours, entry
  requirements and safety information with official sources before traveling.
- It answers in {{language_name}}, whatever the language of the question.
- It may refuse "what is near me" questions that name the place with a synonym of the label in the
  context (see the table).
- It may reproduce sentences of the source texts verbatim (see the license).

## License and sources

- Weights: **[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)**, because the model can reproduce the [Wikivoyage](https://www.wikivoyage.org/)
  and [Wikipedia](https://www.wikipedia.org/) text it was trained on (CC BY-SA 4.0). Every source page (title, URL, license) and the revision of every dataset are in `ATTRIBUTION.tsv`.
- Base model: [{{base_model}}](https://huggingface.co/{{base_model}}), [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0), license text in `LICENSE-base-model`.
<!-- it -->
- Machine translation: MarianMT [`Helsinki-NLP/opus-mt-tc-big-en-it`](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-it),
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); translated sections are marked in `ATTRIBUTION.tsv`.
<!-- /it -->
<!-- en -->
- Machine translation: MarianMT [`Helsinki-NLP/opus-mt-tc-big-it-en`](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-it-en),
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); translated sections are marked in `ATTRIBUTION.tsv`.
- Travel advice: [travel.gc.ca](https://travel.gc.ca/), "contains information licensed under the
  [Open Government Licence - Canada](https://open.canada.ca/en/open-government-licence-canada)"; FCDO on [gov.uk](https://www.gov.uk/foreign-travel-advice),
  "contains public sector information licensed under the
  [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/)".
<!-- /en -->
- Vaccination summaries: [Travel.gc.ca](https://travel.gc.ca/)
  ([OGL-Canada 2.0](https://open.canada.ca/en/open-government-licence-canada), "contains information licensed under the Open Government
  Licence - Canada") and [TravelHealthPro](https://travelhealthpro.org.uk/) by UKHSA / NaTHNaC
  ([OGL v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/), "contains public sector information licensed
  under the Open Government Licence v3.0"). Emergency numbers: Travel.gc.ca and [gov.uk](https://www.gov.uk/foreign-travel-advice) (same licenses),
  Wikipedia, Wikivoyage and Wikidata.
- [Wikidata](https://www.wikidata.org/): [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
- Questions: [soniawmeyer/travel-conversations-finetuning](https://huggingface.co/datasets/soniawmeyer/travel-conversations-finetuning)
  ([MIT](https://opensource.org/license/mit)),
<!-- it -->
  [truthful_qa_italian](https://huggingface.co/datasets/sapienzanlp/truthful_qa_italian) ([Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)),
  [alpaca-cleaned-italian](https://huggingface.co/datasets/DanielSc4/alpaca-cleaned-italian)
<!-- /it -->
<!-- en -->
  [truthful_qa](https://huggingface.co/datasets/truthfulqa/truthful_qa) ([Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)),
  [alpaca-cleaned](https://huggingface.co/datasets/yahma/alpaca-cleaned)
<!-- /en -->
  ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) as declared by the copy; the original [Stanford Alpaca](https://github.com/tatsu-lab/stanford_alpaca) data is
  [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)), [WebFAQ](https://huggingface.co/datasets/PaDaS-Lab/webfaq) by PaDaS-Lab
  ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)), [MKQA](https://github.com/apple-aiml-research/ml-mkqa) by Apple
  ([CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)).
- No content from [Viaggiare Sicuri](https://www.viaggiaresicuri.it/) (Farnesina) is included.
