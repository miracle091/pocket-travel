#!/usr/bin/env python3
"""Stampa le celle figlie, allo zoom minimo della griglia dei civici, che contengono almeno una tile z14
del PMTiles di una cella: una riga "z/x/y" per figlia, in ordine. build-address-cell.sh le usa per
dividere una cella sotto ADDRESS_CELL_MIN_ZOOM senza costruire le figlie vuote (una cella z2 ha 1024
discendenti z7, quasi tutti senza civici).

Uso: address_cell_children.py <addresses.pmtiles locale> <z/x/y della cella> <zoom minimo>

Le tile fuori dalla cella si ignorano. Richiede solo la libreria standard.
"""
import importlib.util
import sys
from pathlib import Path

# seed-address-grid.py legge gia' la directory di un PMTiles; il nome con i trattini non si importa
# con "import", quindi si carica dal path.
_spec = importlib.util.spec_from_file_location("seed_address_grid", Path(__file__).parent / "seed-address-grid.py")
_seed = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_seed)


def children_at_min_zoom(tile_ids, z, x, y, min_zoom):
    """Celle (min_zoom, x, y) dentro z/x/y che contengono una delle tile z14 di tile_ids, in ordine."""
    if not z < min_zoom <= 14:
        raise ValueError(f"zoom minimo {min_zoom} non valido per una cella z{z}")
    children = set()
    for tile_id in tile_ids:
        tz, tx, ty = _seed.tile_id_to_zxy(tile_id)
        if tz != 14 or (tx >> (14 - z), ty >> (14 - z)) != (x, y):
            continue
        children.add((min_zoom, tx >> (14 - min_zoom), ty >> (14 - min_zoom)))
    return sorted(children)


def read_file_range(path):
    def read(start, length):
        with open(path, "rb") as f:
            f.seek(start)
            return f.read(length)
    return read


def main(argv):
    if len(argv) != 4:
        print("Uso: address_cell_children.py <addresses.pmtiles> <z/x/y> <zoom minimo>", file=sys.stderr)
        return 1
    z, x, y = (int(v) for v in argv[2].split("/"))
    lengths = _seed.pmtiles_z14_lengths(argv[1], read_file_range(argv[1]))
    for cz, cx, cy in children_at_min_zoom(lengths, z, x, y, int(argv[3])):
        print(f"{cz}/{cx}/{cy}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
