#!/usr/bin/env python3
"""Test di text_only.py senza torch: nomi dei tensori della copia solo testo, riconoscimento dei parametri vision e
controllo del testo di training (CHATML reso con jinja2 come in transformers; saltato se jinja2 manca).

Uso: python test_text_only.py
"""
import importlib.util
import unittest

from text_only import CHATML, check_chat_format, text_names, vision_names

ROWS = [{"messages": [{"role": "user", "content": "Contesto: treni.\nDomanda: come arrivo?"},
                      {"role": "assistant", "content": "In treno. "}]}]


class JinjaTokenizer:
    """Applica il chat template come transformers (jinja2 con trim_blocks e lstrip_blocks)."""

    def __init__(self, chat_template):
        self.chat_template = chat_template
        self.ids = [7]  # token di <|im_end|>

    def __call__(self, text, add_special_tokens=False):
        return {"input_ids": self.ids}

    def apply_chat_template(self, messages, add_generation_prompt=False, tokenize=False, **kwargs):
        import jinja2
        env = jinja2.Environment(trim_blocks=True, lstrip_blocks=True)
        return env.from_string(self.chat_template).render(messages=messages, add_generation_prompt=add_generation_prompt, **kwargs)


class NamesTest(unittest.TestCase):
    def test_copia_senza_torre_visiva_ne_mtp(self):
        source = ["model.language_model.embed_tokens.weight", "model.language_model.layers.0.mlp.up_proj.weight",
                  "model.visual.blocks.0.attn.qkv.weight", "model.visual.merger.linear_fc1.weight", "mtp.fc.weight"]
        self.assertEqual(source[:2], text_names(source))

    def test_parametri_vision(self):
        names = ["base_model.model.model.layers.0.self_attn.q_proj.lora_A.default.weight",
                 "model.visual.patch_embed.proj.weight", "vision_tower.encoder.weight"]
        self.assertEqual(names[1:], vision_names(names))


@unittest.skipUnless(importlib.util.find_spec("jinja2"), "jinja2 non installato")
class ChatFormatTest(unittest.TestCase):
    def test_chatml_come_l_app(self):
        check_chat_format(JinjaTokenizer(CHATML), ROWS)  # nessun sys.exit

    def test_template_con_thinking_ferma_il_training(self):
        with self.assertRaises(SystemExit):
            check_chat_format(JinjaTokenizer(CHATML + "{% if enable_thinking %}<think>{% endif %}"), ROWS)

    def test_testo_diverso_ferma_il_training(self):
        with self.assertRaises(SystemExit):
            check_chat_format(JinjaTokenizer(CHATML.replace("<|im_end|>\n", "<|im_end|>")), ROWS)

    def test_im_end_non_token_unico_ferma_il_training(self):
        tok = JinjaTokenizer(CHATML)
        tok.ids = [7, 8]
        with self.assertRaises(SystemExit):
            check_chat_format(tok, ROWS)


if __name__ == "__main__":
    unittest.main()
