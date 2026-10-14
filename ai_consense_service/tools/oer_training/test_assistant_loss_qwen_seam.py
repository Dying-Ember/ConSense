"""Actual tiny Qwen2-VL/plain-LoRA CPU seam; no checkpoint weights are loaded."""
import sys
from pathlib import Path
import unittest

import torch
from transformers import Qwen2VLConfig, Qwen2VLForConditionalGeneration

try:
    from .assistant_loss import complete_assistant_loss
except ImportError:
    from assistant_loss import complete_assistant_loss


class QwenAssistantLossSeam(unittest.TestCase):
    def test_multimodal_base_path_preserves_stock_loss_lora_gradients_and_embedding_hook(self):
        overlay = Path(__file__).resolve().parents[3] / '.local/state/oer-training-quality-20261010/peft-overlay-01'
        if overlay.is_dir():
            sys.path.insert(0, str(overlay))
        from peft import LoraConfig, get_peft_model

        torch.set_num_threads(1)
        torch.manual_seed(15014)
        config = Qwen2VLConfig(
            text_config={'vocab_size': 41, 'hidden_size': 24, 'intermediate_size': 48,
                         'num_hidden_layers': 1, 'num_attention_heads': 3, 'num_key_value_heads': 1,
                         'max_position_embeddings': 512, 'tie_word_embeddings': True,
                         'rope_scaling': {'rope_type': 'default', 'mrope_section': [1, 1, 2]},
                         'attention_dropout': 0., 'pad_token_id': 0, 'eos_token_id': 2},
            vision_config={'depth': 1, 'embed_dim': 16, 'hidden_size': 24, 'num_heads': 2,
                           'mlp_ratio': 2, 'patch_size': 2, 'spatial_merge_size': 2, 'temporal_patch_size': 1},
            image_token_id=30, video_token_id=28, vision_start_token_id=29, vision_end_token_id=31,
            attn_implementation='sdpa')
        raw = Qwen2VLForConditionalGeneration(config).to('cpu').requires_grad_(False)
        suffixes = {'q_proj', 'k_proj', 'v_proj', 'o_proj', 'gate_proj', 'up_proj', 'down_proj'}
        targets = [name for name, _ in raw.named_modules()
                   if '.layers.' in name and name.rsplit('.', 1)[-1] in suffixes]
        self.assertEqual(len(targets), 7)
        model = get_peft_model(raw, LoraConfig(r=2, lora_alpha=4, lora_dropout=0,
                                             target_modules=targets, task_type='CAUSAL_LM', bias='none'))
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant': False})
        model.enable_input_require_grads()
        model.train()
        base = model.get_base_model()
        trainable = {name: parameter for name, parameter in model.named_parameters() if parameter.requires_grad}
        self.assertEqual(len(trainable), 14)
        self.assertTrue(all('lora_' in name and 'visual' not in name for name in trainable))
        with torch.no_grad():
            for name, parameter in trainable.items():
                if 'lora_B' in name:
                    parameter.normal_(0, .01)  # Exercise both A and B gradients, rather than zero-B initialization.
        embedding_grad_flags = []
        hook = base.get_input_embeddings().register_forward_hook(
            lambda module, arguments, output: embedding_grad_flags.append(output.requires_grad))
        ids = torch.tensor([[3, 29, 30, 30, 30, 30, 31, 7, 8, 9, 10, 11, 12, 13, 14, 2]])
        batch = {'input_ids': ids, 'attention_mask': torch.ones_like(ids),
                 'pixel_values': torch.randn(16, 12), 'image_grid_thw': torch.tensor([[1, 4, 4]])}
        labels = ids.clone()
        labels[:, :9] = -100
        try:
            stock = model(**batch, labels=labels, use_cache=False, return_dict=True)
            stock_rope = stock.rope_deltas.clone()
            stock.loss.backward()
            stock_grad = {name: parameter.grad.clone() for name, parameter in trainable.items()}
            model.zero_grad(set_to_none=True)
            hidden_result = base.model(**batch, use_cache=False, return_dict=True)
            loss = complete_assistant_loss(hidden_result.last_hidden_state, base.lm_head, labels)
            torch.testing.assert_close(loss, stock.loss.detach(), atol=1e-6, rtol=1e-6)
            torch.testing.assert_close(hidden_result.rope_deltas, stock_rope, atol=0, rtol=0)
            self.assertGreater(torch.count_nonzero(stock_rope).item(), 0, 'Image mRoPE was not exercised')
            loss.backward()
            for name, parameter in trainable.items():
                self.assertIsNotNone(parameter.grad, name)
                torch.testing.assert_close(parameter.grad, stock_grad[name], atol=2e-6, rtol=2e-4)
            self.assertGreater(sum(parameter.grad.abs().sum().item() for name, parameter in trainable.items()
                                   if 'lora_A' in name), 0)
            self.assertTrue(all(embedding_grad_flags) and len(embedding_grad_flags) >= 2)
            self.assertTrue(all(parameter.grad is None for parameter in base.lm_head.parameters()))
            self.assertTrue(all(parameter.grad is None for parameter in base.model.visual.parameters()))
            self.assertIsNone(base.get_input_embeddings().weight.grad)
            with torch.no_grad():
                changed = base.model(**{**batch, 'pixel_values': torch.zeros_like(batch['pixel_values'])},
                                     use_cache=False, return_dict=True).last_hidden_state
            self.assertGreater((changed - hidden_result.last_hidden_state.detach()).abs().max().item(), 1e-6,
                               'The direct base path did not consume the image')
        finally:
            hook.remove()


if __name__ == '__main__':
    unittest.main()
