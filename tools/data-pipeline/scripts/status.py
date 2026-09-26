#!/usr/bin/env python3
"""Righe di stato leggibili per gli script della pipeline (training, eval, conversione, dataset).

Una riga per aggiornamento, pensata anche per i log su file (niente \\r come le barre tqdm):
  [training] 250/886 (28%) · 13,6 s/passo · mancano 2h24m · loss 0,394
Uso:
  from status import Progress, phase
  phase("caricamento modello")            # [fase] caricamento modello
  p = Progress("eval", total=361, unit="riga")
  p.update(done, "rifiuti 88%")           # stampa al massimo ogni `every` secondi (e sempre all'ultima)
"""
import sys
import time


def _num(x, decimals=1):
    return f"{x:.{decimals}f}".replace(".", ",")


def duration(seconds):
    """'2h24m', '5m30s', '42s'."""
    s = int(seconds)
    if s >= 3600:
        return f"{s // 3600}h{s % 3600 // 60:02d}m"
    if s >= 60:
        return f"{s // 60}m{s % 60:02d}s"
    return f"{s}s"


def phase(name, detail=""):
    print(f"[fase] {name}" + (f" · {detail}" if detail else ""), file=sys.stderr, flush=True)


class Progress:
    def __init__(self, name, total, unit="passo", every=15.0):
        self.name, self.total, self.unit, self.every = name, total, unit, every
        self.start = time.monotonic()
        self.last_print = 0.0
        self.first_done, self.first_time = 0, self.start

    def mark_start(self, done):
        """Riparte da qui per la velocita' (es. dopo il primo passo, che include compilazioni e caricamenti)."""
        self.first_done, self.first_time = done, time.monotonic()

    def update(self, done, extra="", force=False):
        now = time.monotonic()
        if not force and done < self.total and now - self.last_print < self.every:
            return
        self.last_print = now
        parts = [f"[{self.name}] {done}/{self.total} ({100 * done // max(self.total, 1)}%)"]
        rate_done, rate_time = done - self.first_done, now - self.first_time
        if rate_done > 0 and rate_time > 0:
            per = rate_time / rate_done
            parts.append(f"{_num(per)} s/{self.unit}" if per >= 1 else f"{_num(1 / per)} {self.unit}/s")
            if done < self.total:
                parts.append(f"mancano {duration(per * (self.total - done))}")
        parts.append(f"trascorsi {duration(now - self.start)}")
        if extra:
            parts.append(extra)
        print(" · ".join(parts), file=sys.stderr, flush=True)
