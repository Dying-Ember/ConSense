"""CPU differential checks against the installed stock causal-LM loss."""
import copy
import unittest

import torch
from torch import nn
from torch.nn import functional as F
from transformers.loss.loss_utils import ForCausalLMLoss

try:
    from .assistant_loss import complete_assistant_loss
except ImportError:
    from assistant_loss import complete_assistant_loss


class TrackingHead(nn.Linear):
    def __init__(self, hidden, vocabulary, *, dtype=torch.float32):
        super().__init__(hidden, vocabulary, bias=False, dtype=dtype)
        self.requires_grad_(False)
        self.rows = []

    def forward(self, hidden):
        self.rows.append(hidden.numel() // hidden.shape[-1])
        return super().forward(hidden)


class CompleteAssistantLoss(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)

    def compare_stock(self, dtype):
        generator = torch.Generator(device='cpu').manual_seed(7021)
        batch, length, width, vocabulary, rank = 2, 280, 13, 37, 3
        labels = torch.randint(vocabulary, (batch, length), generator=generator)
        labels[0, :9] = -100
        labels[1, :17] = -100
        labels[0, 100:105] = -100
        labels[:, -1] = 2  # The complete assistant target ends with EOS.
        values = [torch.randn(batch, length, width, generator=generator, dtype=dtype),
                  torch.randn(rank, width, generator=generator, dtype=dtype) * .1,
                  torch.randn(width, rank, generator=generator, dtype=dtype) * .1]
        stock_values = [value.clone().requires_grad_() for value in values]
        chunk_values = [value.clone().requires_grad_() for value in values]

        def adapter_hidden(tensors):
            inputs, a, b = tensors
            hidden = inputs + .5 * F.linear(F.linear(inputs, a), b)
            hidden.retain_grad()
            return hidden

        stock_hidden, chunk_hidden = adapter_hidden(stock_values), adapter_hidden(chunk_values)
        chunk_head = TrackingHead(width, vocabulary, dtype=dtype)
        stock_head = nn.Linear(width, vocabulary, bias=False, dtype=dtype)
        stock_head.load_state_dict(copy.deepcopy(chunk_head.state_dict()))
        stock_head.requires_grad_(False)
        expected = ForCausalLMLoss(stock_head(stock_hidden), labels, vocabulary)
        actual = complete_assistant_loss(chunk_hidden, chunk_head, labels)
        self.assertEqual(actual.dtype, torch.float32)
        self.assertEqual(actual.ndim, 0)
        torch.testing.assert_close(actual, expected, atol=1e-6, rtol=1e-6)
        forward_calls = len(chunk_head.rows)
        self.assertGreater(forward_calls, 2)  # More than 512 valid tokens, with a nonempty tail.
        expected.backward()
        actual.backward()
        tolerance = 2e-3 if dtype == torch.bfloat16 else 2e-7
        relative = 2e-2 if dtype == torch.bfloat16 else 2e-5
        torch.testing.assert_close(chunk_hidden.grad, stock_hidden.grad, atol=tolerance, rtol=relative)
        for actual_value, stock_value in zip(chunk_values, stock_values):
            self.assertIsNotNone(actual_value.grad)
            torch.testing.assert_close(actual_value.grad, stock_value.grad, atol=tolerance, rtol=relative)
        self.assertTrue(all(0 < count <= 256 for count in chunk_head.rows), chunk_head.rows)
        self.assertGreater(len(chunk_head.rows), forward_calls, 'Backward must exercise checkpoint recomputation')
        self.assertTrue(any(count < 256 for count in chunk_head.rows), 'The final partial chunk was not observed')
        self.assertTrue(all(parameter.grad is None for parameter in chunk_head.parameters()))
        prediction_mask = F.pad(labels[:, 1:] != -100, (0, 1), value=False)
        self.assertEqual(torch.count_nonzero(chunk_hidden.grad[~prediction_mask]).item(), 0)
        self.assertGreater(chunk_hidden.grad[0, 8].abs().sum().item(), 0, 'First assistant prediction was dropped')
        self.assertGreater(chunk_hidden.grad[:, -2].abs().sum().item(), 0, 'EOS prediction was dropped')
        self.assertEqual(torch.count_nonzero(chunk_hidden.grad[:, -1]).item(), 0)

    def test_fp32_loss_hidden_and_adapter_gradients_match_stock_over_multiple_chunks(self):
        self.compare_stock(torch.float32)

    def test_bf16_head_keeps_fp32_loss_and_matches_stock_gradients(self):
        self.compare_stock(torch.bfloat16)

    def test_empty_shifted_target_is_rejected_before_head_work(self):
        for labels in [torch.full((1, 5), -100), torch.tensor([[2, -100, -100, -100, -100]])]:
            head = TrackingHead(3, 7)
            with self.subTest(labels=labels.tolist()), self.assertRaises(ValueError):
                complete_assistant_loss(torch.randn(1, 5, 3, requires_grad=True), head, labels)
            self.assertEqual(head.rows, [])

    def test_wrong_hidden_or_label_shapes_are_rejected(self):
        cases = [(torch.randn(5, 3), torch.ones(1, 5, dtype=torch.long)),
                 (torch.randn(1, 5, 3), torch.ones(5, dtype=torch.long)),
                 (torch.randn(2, 5, 3), torch.ones(1, 5, dtype=torch.long)),
                 (torch.randn(1, 5, 3), torch.ones(1, 4, dtype=torch.long))]
        for hidden, labels in cases:
            with self.subTest(hidden=hidden.shape, labels=labels.shape), self.assertRaises(ValueError):
                complete_assistant_loss(hidden, TrackingHead(3, 7), labels)

    def test_every_head_parameter_must_be_frozen(self):
        for trainable in ['weight', 'bias']:
            head = nn.Linear(3, 7, bias=True).requires_grad_(False)
            getattr(head, trainable).requires_grad_(True)
            with self.subTest(trainable=trainable), self.assertRaises(ValueError):
                complete_assistant_loss(torch.randn(1, 5, 3, requires_grad=True), head,
                                        torch.tensor([[-100, -100, 1, 3, 2]]))


if __name__ == '__main__':
    unittest.main()
