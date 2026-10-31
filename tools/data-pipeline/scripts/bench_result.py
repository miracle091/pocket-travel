"""Benchmark pp/tg in token/s di un GGUF con llama-bench, nello stesso schema JSON dell'app.

Lo schema (versione 1) e' documentato in testa a BenchmarkStats.kt (feature/ai): qui `source` e' "eval_gguf", `seed` e'
null (llama-bench non ha l'opzione: usa rand() senza seme, quindi sempre gli stessi token, ma non quelli dell'app),
`overheating` e' false e `load_time_ms` null. Stesso protocollo dell'app: un
riscaldamento (lo fa llama-bench), 5 ripetizioni; con CV oltre il 5% altre 5 unite alle prime; con CV oltre il 10% la misura e'
instabile. `comparison_key` e' "<sha256[:12]>/<llama_cpp>/<device>/t<thread>/pp<n>tg<n>", come nell'app; `device` e' la GPU
(`gpu_info` di llama-bench) se ci sono strati sulla GPU, altrimenti la CPU.
"""
import hashlib
import json
import statistics
import subprocess
import time
from pathlib import Path

from eval_common import find_llama_bin, llama_env

SCHEMA_VERSION = 1
N_PROMPT = 512
N_GEN = 128
RUNS = 5
CV_EXTRA_PERCENT = 5.0
CV_UNSTABLE_PERCENT = 10.0


def cv_percent(values):
    """Deviazione standard campionaria diviso la media, in percentuale (0 con meno di due valori)."""
    if len(values) < 2 or statistics.fmean(values) == 0:
        return 0.0
    return statistics.stdev(values) / statistics.fmean(values) * 100


def stable_measure(samples):
    cv = cv_percent(samples)
    return {"median_tps": statistics.median(samples), "cv_percent": cv, "samples_tps": list(samples),
            "unstable": cv > CV_UNSTABLE_PERCENT}


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def comparison_key(sha256, llama_cpp, device, threads, n_prompt, n_gen):
    return f"{sha256[:12]}/{llama_cpp}/{device}/t{threads}/pp{n_prompt}tg{n_gen}"


def build_record(model, sha256, rows, now_ms):
    """Il record dello schema comune dalle righe JSON di llama-bench (una per test, con samples_ts)."""
    pp = next(r for r in rows if r["n_prompt"] == N_PROMPT and r["n_gen"] == 0)
    tg = next(r for r in rows if r["n_gen"] == N_GEN and r["n_prompt"] == 0)
    llama_cpp = f"b{pp['build_number']}-{pp['build_commit']}"
    # Con strati sulla GPU la misura e' della GPU: senza, CPU e GPU avrebbero la stessa comparison_key.
    gpu = pp.get("n_gpu_layers", 0) > 0 and pp.get("gpu_info")
    device = str(gpu or pp.get("cpu_info") or "unknown").strip()
    pp_measure, tg_measure = stable_measure(pp["samples_ts"]), stable_measure(tg["samples_ts"])
    return {
        "schema": SCHEMA_VERSION, "source": "eval_gguf",
        "model": {"id": None, "file": Path(model).name, "sha256": sha256},
        "llama_cpp": llama_cpp, "sampler": "none", "seed": None, "device": device, "threads": pp["n_threads"],
        "n_prompt": N_PROMPT, "n_gen": N_GEN, "warmup": 1, "pp": pp_measure, "tg": tg_measure,
        "unstable": pp_measure["unstable"] or tg_measure["unstable"], "overheating": False, "load_time_ms": None,
        "comparison_key": comparison_key(sha256, llama_cpp, device, pp["n_threads"], N_PROMPT, N_GEN),
        "ran_at": now_ms,
    }


def run_llama_bench(model, llama_cpp_dir, threads=0, gpu_layers=0, device=None):
    exe = find_llama_bin("llama-bench", llama_cpp_dir)
    cmd = [exe, "-m", str(model), "-p", str(N_PROMPT), "-n", str(N_GEN), "-r", str(RUNS), "-ngl", str(gpu_layers),
           "-o", "json", *(["-t", str(threads)] if threads else []), *(["--device", device] if device else [])]
    done = subprocess.run(cmd, capture_output=True, env=llama_env(exe))
    if done.returncode != 0:
        raise SystemExit(f"llama-bench uscito con codice {done.returncode}: {done.stderr.decode('utf-8', 'replace')}")
    return json.loads(done.stdout.decode("utf-8"))


def bench(model, llama_cpp_dir, threads=0, gpu_layers=0, device=None):
    """Misura con il protocollo dell'app e restituisce il record JSON. Con CV oltre il 5% si rifanno altre 5 ripetizioni
    e si uniscono alle prime (10 campioni in tutto), come fa l'app."""
    rows = run_llama_bench(model, llama_cpp_dir, threads, gpu_layers, device)
    if any(cv_percent(r["samples_ts"]) > CV_EXTRA_PERCENT for r in rows):
        more = run_llama_bench(model, llama_cpp_dir, threads, gpu_layers, device)
        for row, extra in zip(rows, more):
            row["samples_ts"] = row["samples_ts"] + extra["samples_ts"]
    return build_record(model, file_sha256(model), rows, int(time.time() * 1000))
