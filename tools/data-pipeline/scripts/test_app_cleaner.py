#!/usr/bin/env python3
"""Test della chiusura della JVM di app_clean (generate_sft_dataset.py): a fine esecuzione stdin si chiude e il processo
si attende, con kill() se non esce in tempo. Gradle e la JVM sono sostituiti da mock: nessun processo.

Uso: python test_app_cleaner.py
"""
import subprocess
import unittest
from types import SimpleNamespace
from unittest import mock

import generate_sft_dataset as g


def fake_jvm(wait_side_effect=None):
    return mock.Mock(stdin=mock.Mock(), stdout=mock.Mock(readline=lambda: '{"text": "pulito"}\n'),
                     wait=mock.Mock(side_effect=wait_side_effect))


class AppCleanerTest(unittest.TestCase):
    def start(self, jvm):
        """Lancia app_clean con Gradle e Popen finti; restituisce le funzioni passate a atexit.register."""
        self.addCleanup(setattr, g, "_app_cleaner", g._app_cleaner)
        g._app_cleaner = None
        gradle = SimpleNamespace(stdout="java\nclasspath\n")
        with mock.patch("subprocess.run", return_value=gradle), mock.patch("subprocess.Popen", return_value=jvm), \
                mock.patch("atexit.register") as register:
            self.assertEqual("pulito", g.app_clean("raw"))
        return [c.args[0] for c in register.call_args_list]

    def test_a_fine_esecuzione_chiude_stdin_e_attende(self):
        jvm = fake_jvm()
        (close,) = self.start(jvm)
        close()
        jvm.stdin.close.assert_called_once()
        jvm.wait.assert_called_once()
        jvm.kill.assert_not_called()

    def test_se_non_esce_in_tempo_kill(self):
        jvm = fake_jvm([subprocess.TimeoutExpired("java", 10), None])
        (close,) = self.start(jvm)
        close()
        jvm.kill.assert_called_once()
        self.assertEqual(2, jvm.wait.call_count)

    def test_registra_la_chiusura_una_volta_sola(self):
        jvm = fake_jvm()
        self.assertEqual(1, len(self.start(jvm)))
        with mock.patch("atexit.register") as register:
            g.app_clean("altro")
        register.assert_not_called()


if __name__ == "__main__":
    unittest.main()
