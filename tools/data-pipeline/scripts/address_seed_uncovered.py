#!/usr/bin/env python3
"""Righe del seme della griglia dei civici non ancora coperte dalle celle pubblicate.

Una riga "z/x/y" del seme e' coperta se e' una cella pubblicata, un'antenata di una cella pubblicata (la
cella e' stata divisa) o una discendente di una cella pubblicata (la cella la contiene gia'): in tutti e tre i
casi aggiungerla alla partizione la farebbe sovrapporre a una cella dell'indice.

Uso:
  address_seed_uncovered.py ids <seed.tsv> <published-ids.txt>
      scrive su stdout le righe non coperte, nell'ordine del seme
  address_seed_uncovered.py warn <seed.tsv> <published-ids.txt> <repo-seed.tsv> [--min-zoom 7]
      per ogni riga non coperta a zoom >= min-zoom scrive su stdout un "::warning::" con l'id della cella
      (prima quelle assenti dal seme del repository); non fallisce mai per il contenuto dei file

published-ids.txt: un id "z/x/y" per riga (le celle dell'indice pubblicato).
"""
import argparse
import sys


def read_ids(path):
    with open(path, encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def parse(cell_id):
    z, x, y = (int(part) for part in cell_id.split("/"))
    return z, x, y


def uncovered(seed_ids, published_ids):
    """Gli id del seme che non sono ne' pubblicati, ne' antenati, ne' discendenti di un id pubblicato."""
    published = set()
    ancestors = set()
    for cell_id in published_ids:
        z, x, y = parse(cell_id)
        published.add((z, x, y))
        while z > 0:
            z, x, y = z - 1, x // 2, y // 2
            ancestors.add((z, x, y))
    result = []
    for cell_id in seed_ids:
        z, x, y = parse(cell_id)
        if (z, x, y) in ancestors:
            continue
        covered = False
        while True:
            if (z, x, y) in published:
                covered = True
                break
            if z == 0:
                break
            z, x, y = z - 1, x // 2, y // 2
        if not covered:
            result.append(cell_id)
    return result


def warnings(seed_ids, published_ids, repo_seed_ids, min_zoom):
    """Righe "::warning::" per le celle del seme a zoom >= min_zoom ancora senza copertura; prima quelle
    che il seme del repository non ha (aree con civici nuovi o senza cella)."""
    in_repo = set(repo_seed_ids)
    cells = [cell_id for cell_id in uncovered(seed_ids, published_ids) if parse(cell_id)[0] >= min_zoom]
    cells.sort(key=lambda cell_id: cell_id in in_repo)
    return [
        f"::warning::cella {cell_id} del seme rigenerato senza cella pubblicata"
        + ("" if cell_id in in_repo else " (assente dal seme del repository)")
        for cell_id in cells
    ]


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    ids = sub.add_parser("ids")
    ids.add_argument("seed")
    ids.add_argument("published")
    warn = sub.add_parser("warn")
    warn.add_argument("seed")
    warn.add_argument("published")
    warn.add_argument("repo_seed")
    warn.add_argument("--min-zoom", type=int, default=7)
    args = parser.parse_args()

    if args.command == "ids":
        for cell_id in uncovered(read_ids(args.seed), read_ids(args.published)):
            print(cell_id)
        return
    try:
        lines = warnings(read_ids(args.seed), read_ids(args.published), read_ids(args.repo_seed), args.min_zoom)
    except (OSError, ValueError) as error:
        print(f"::warning::controllo del seme rigenerato non riuscito: {error}")
        return
    for line in lines:
        print(line)
    print(f"-- seme rigenerato: {len(lines)} celle senza cella pubblicata", file=sys.stderr)


if __name__ == "__main__":
    main()
