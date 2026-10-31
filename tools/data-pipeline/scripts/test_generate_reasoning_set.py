#!/usr/bin/env python3
"""Test di generate_reasoning_set.py: giorni di servizio, risposte calcolate dagli orari, lettura della risposta finale.

Uso: python test_generate_reasoning_set.py
"""
import datetime
import io
import re
import unittest
import zipfile

from generate_reasoning_set import (KINDS, Timetable, active_services, busiest_weekday, generate, parse_answer,
                                    read_csv, score_answer)

STOPS = [f"S{n}" for n in range(8)]


def feed():
    """Una rete finta: linea A su S0..S7 ogni 10 minuti dalle 7 alle 12, linea B da S5 ogni 15 minuti, una corsa a
    frequenza e una corsa di un servizio che il giorno scelto non c'e'."""
    files = {
        "stops.txt": "stop_id,stop_name\n" + "".join(f"{s},Fermata {s}\n" for s in STOPS),
        "routes.txt": "route_id,route_short_name,route_long_name\nA,1,\nB,2,\n",
        "calendar.txt": ("service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                         "FER,1,1,1,1,1,0,0,20261101,20261130\nFES,0,0,0,0,0,1,1,20261101,20261130\n"),
        "calendar_dates.txt": "service_id,date,exception_type\nFER,20261104,2\nFES,20261104,1\n",
        "frequencies.txt": "trip_id,start_time,end_time,headway_secs\nF1,07:00:00,09:00:00,600\n",
    }
    trips = ["route_id,service_id,trip_id,trip_headsign"]
    times = ["trip_id,arrival_time,departure_time,stop_id,stop_sequence"]
    for k, start in enumerate(range(7 * 60, 12 * 60, 10)):
        trips.append(f"A,FER,A{k},Capolinea")
        times += [f"A{k},{(start + 3 * i) // 60:02d}:{(start + 3 * i) % 60:02d}:00,"
                  f"{(start + 3 * i) // 60:02d}:{(start + 3 * i) % 60:02d}:00,{s},{i + 1}" for i, s in enumerate(STOPS)]
    for k, start in enumerate(range(7 * 60, 13 * 60, 15)):
        trips.append(f"B,FER,B{k},Monte")
        times += [f"B{k},{start // 60:02d}:{start % 60:02d}:00,{start // 60:02d}:{start % 60:02d}:00,S5,1",
                  f"B{k},{start // 60:02d}:{start % 60 + 5:02d}:00,{start // 60:02d}:{start % 60 + 5:02d}:00,S6,2"]
    trips += ["A,FER,F1,Frequenza", "A,FES,X1,Festivo"]
    times += ["F1,07:00:00,07:00:00,S0,1", "F1,07:05:00,07:05:00,S1,2", "X1,07:00:00,07:00:00,S0,1",
              "X1,07:05:00,07:05:00,S1,2"]
    files["trips.txt"] = "\n".join(trips) + "\n"
    files["stop_times.txt"] = "\n".join(times) + "\n"
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, text in files.items():
            zf.writestr(name, text)
    return zipfile.ZipFile(buf)


def minutes(hhmm):
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


class ServiceTest(unittest.TestCase):
    def test_eccezioni_del_giorno(self):
        zf = feed()
        cal, dates = read_csv(zf, "calendar.txt"), read_csv(zf, "calendar_dates.txt")
        self.assertEqual({"FER"}, active_services(cal, dates, datetime.date(2026, 11, 3)))
        self.assertEqual({"FES"}, active_services(cal, dates, datetime.date(2026, 11, 4)))
        self.assertEqual(datetime.date(2026, 11, 2), busiest_weekday(cal, dates))

    def test_fuori_le_corse_a_frequenza_e_dei_servizi_spenti(self):
        tt = Timetable(feed(), datetime.date(2026, 11, 3))
        self.assertNotIn("F1", tt.stops)
        self.assertNotIn("X1", tt.stops)
        self.assertEqual(30, len(tt.departures[("S0", "A")]))


class GenerateTest(unittest.TestCase):
    def setUp(self):
        self.rows = generate(Timetable(feed(), datetime.date(2026, 11, 3)), 20, seed=3)

    def test_tipi_e_lingue_in_parti_uguali_stesso_seme_stesso_set(self):
        self.assertEqual([k for _ in range(4) for k in KINDS], [r["kind"] for r in self.rows])
        self.assertEqual(10, sum(r["lang"] == "it" for r in self.rows))
        self.assertEqual(self.rows, generate(Timetable(feed(), datetime.date(2026, 11, 3)), 20, seed=3))

    def test_le_risposte_tornano_con_gli_orari(self):
        for r in self.rows:
            q, ctx = r["question"], r["context"]
            if r["kind"] == "next":
                # Linea A ogni 10 minuti: il passaggio del tabellone entro 10 minuti dopo l'ora della domanda.
                asked = minutes(re.search(r"(?:alle|at) (\d\d:\d\d)", q).group(1))
                self.assertIn(r["answer"], ctx)
                self.assertIn(minutes(r["answer"]) - asked, range(1, 11))
            elif r["kind"] == "duration":
                a, b = re.findall(r"Fermata (S\d)", q)
                self.assertEqual(3 * (int(b[1]) - int(a[1])), int(r["answer"]))
            elif r["kind"] == "transfer":
                self.assertIn("linea 2" if r["lang"] == "it" else "line 2", q)
                arrive = minutes(re.findall(r"Fermata S5 (\d\d:\d\d)", ctx)[0])
                self.assertEqual((arrive + 3 + 14) // 15 * 15, minutes(r["answer"]))
            elif r["kind"] == "count":
                t1, t2 = (minutes(t) for t in re.findall(r"\d\d:\d\d", q)[:2])
                shown = [minutes(t) for t in re.findall(r"\d\d:\d\d", ctx.split(":", 1)[1])]
                self.assertEqual(sum(t1 <= t <= t2 for t in shown), int(r["answer"]))
            self.assertTrue(score_answer(r, f"... Risposta: {r['answer']}"))


class ParseTest(unittest.TestCase):
    def test_ultima_risposta_dopo_il_thinking(self):
        self.assertEqual("08:05", parse_answer("<think>Risposta: 07:55</think>Prendo il bus. Risposta: 8.05", "time"))
        self.assertEqual("12", parse_answer("Answer: 12 minutes", "number"))
        self.assertEqual("yes", parse_answer("Risposta: Sì, arrivi alle 7:40", "yesno"))
        self.assertEqual("no", parse_answer("Answer: no.", "yesno"))
        self.assertIsNone(parse_answer("Il bus passa alle 08:05", "time"))

    def test_punteggio(self):
        row = {"answer_type": "yesno", "answer": "sì"}
        self.assertTrue(score_answer(row, "Answer: yes"))
        self.assertFalse(score_answer(row, "Risposta: no"))


if __name__ == "__main__":
    unittest.main()
