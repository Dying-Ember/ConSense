"""Complete causal assistant loss without retaining full-page vocabulary logits."""


def complete_assistant_loss(hidden_states, lm_head, labels):
    import torch
    from torch.nn import functional as F
    from torch.utils.checkpoint import checkpoint

    if hidden_states.ndim != 3 or labels.shape != hidden_states.shape[:2]:
        raise ValueError("Hidden states and labels must share batch/sequence dimensions")
    if any(parameter.requires_grad for parameter in lm_head.parameters()):
        raise ValueError("Chunked pilot loss requires a frozen language head")
    targets = labels[:, 1:]
    supervised = targets != -100
    targets = targets[supervised]
    if not targets.numel():
        raise ValueError("Complete assistant target including EOS is required")
    hidden = hidden_states[:, :-1][supervised]

    def chunk_sum(states, tokens):
        return F.cross_entropy(lm_head(states).float(), tokens, reduction="sum")

    # Checkpoint the projection and CE together so no chunk retains its logits.
    sums = [checkpoint(chunk_sum, hidden[start:start + 256], targets[start:start + 256],
                       use_reentrant=False)
            for start in range(0, targets.numel(), 256)]
    return torch.stack(sums).sum() / targets.numel()
