# Risposte registrate dei servizi online

Ogni file e' il corpo di una risposta; accanto, `<nome>.expected.txt` dice cosa deve produrre `OnlineLlmClient`
(`text:` il testo intero, `error:` il nome della classe dell'errore, `status:` il codice HTTP, 200 se manca).
Le righe `header-<nome>: <valore>` aggiungono un'intestazione alla risposta (i limiti di richieste, `*-ratelimit-*`).
`OnlineRecordedStreamsTest` li legge tutti: se un servizio cambia il formato dello streaming, il test lo mostra.
Nelle risposte con un codice HTTP diverso da 200 il test aggiunge `Retry-After: 60`, per non aspettare un nuovo tentativo.

Fonti, controllate il 2026-11-14:

- `chat-completions/openai-text.sse`: forma dei pezzi `chat.completion.chunk` dall'esempio dello streaming in
  https://raw.githubusercontent.com/openai/openai-openapi/manual_spec/openapi.yaml (il pezzo " world" e `[DONE]` sono
  aggiunti: l'esempio mostra solo l'inizio e la fine).
- `chat-completions/openai-insufficient-quota.json`: errore `insufficient_quota` nella forma `error` dello stesso schema;
  il messaggio e' quello della pagina https://platform.openai.com/docs/guides/error-codes, non raggiungibile da script
  il 2026-11-14 (messaggio non ricontrollato).
- `anthropic-messages/text.sse`, `error-after-text.sse`: esempi di https://docs.anthropic.com/en/api/messages-streaming
  (stream di base ed evento `error` con `overloaded_error`; il secondo e' accorciato).
- `anthropic-messages/rate-limit.json`: forma del corpo d'errore di https://docs.anthropic.com/en/api/errors
  (`rate_limit_error`, 429).

- `anthropic-messages/rate-limit-reset.json`, `chat-completions/openai-rate-limit-reset.json`: lo stesso errore 429 con le
  intestazioni dei limiti di richieste, scritte come dicono le pagine dei limiti dei due servizi (data RFC 3339 per
  Anthropic, durata "6m0s" per OpenAI); inventate, non registrate da una chiave.

Mistral e Gemini usano lo stesso formato Chat Completions: le loro particolarita' (`[DONE]` senza `finish_reason`,
quota giornaliera) restano nei test in linea di `OnlineStreamTest`, registrate dal progetto il 2026-10-31 e 2026-11-01.
