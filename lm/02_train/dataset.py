"""
Dataset for keyboard LM training.

Each sample mirrors exactly what FUTO's runtime feeds the model at inference
(org_futo_inputmethod_latin_xlm_LanguageModel.cpp PredictNextWord /
PredictCorrection): the context is tokenized, BOS (token id 1) is prepended,
and every word carries a whitespace-suffix token. We therefore tokenize each
line as [BOS] + sp.EncodeAsIds(line + " ") and train the causal next-token
objective over it.

FMAboard change: the corpus is tokenized once (multi-threaded) into a flat
uint16 token file plus an offsets index, cached next to the text file and
memory-mapped. Holding ~500M token ids as Python lists needs tens of GB of
RAM, and Windows DataLoader workers would each copy it.
"""
import numpy as np
import sentencepiece as spm
import torch
from pathlib import Path
from torch.utils.data import Dataset, DataLoader
from tqdm import tqdm

BOS_ID = 1   # FUTO hardcodes BOS to token id 1
ENCODE_BATCH = 200_000


def _cache_paths(text_file: Path, sp_model_path: Path, max_seq_len: int):
    stem = f"{text_file.stem}.{sp_model_path.stem}.L{max_seq_len}"
    return (text_file.with_name(stem + ".tokens.bin"),
            text_file.with_name(stem + ".offsets.npy"))


def _build_cache(text_file: Path, sp_model_path: Path, max_seq_len: int,
                 tokens_path: Path, offsets_path: Path) -> None:
    sp = spm.SentencePieceProcessor()
    sp.Load(str(sp_model_path))
    assert sp.GetPieceSize() < 65536, "uint16 token cache needs vocab < 65536"

    offsets = [0]
    tmp_tokens = tokens_path.with_suffix(".part")
    with open(text_file, encoding="utf-8") as fin, open(tmp_tokens, "wb") as fout:
        batch: list[str] = []

        def flush() -> None:
            encoded = sp.encode([line + " " for line in batch], num_threads=0)
            for ids in encoded:
                ids = [BOS_ID] + ids
                for start in range(0, len(ids) - 1, max_seq_len - 1):
                    chunk = ids[start:start + max_seq_len]
                    if len(chunk) >= 3:
                        fout.write(np.asarray(chunk, dtype=np.uint16).tobytes())
                        offsets.append(offsets[-1] + len(chunk))
            batch.clear()

        for line in tqdm(fin, desc=f"tokenizing {text_file.name}", unit="line"):
            line = line.strip()
            if line:
                batch.append(line)
                if len(batch) >= ENCODE_BATCH:
                    flush()
        if batch:
            flush()

    np.save(offsets_path, np.asarray(offsets, dtype=np.int64))
    tmp_tokens.replace(tokens_path)


class KeyboardDataset(Dataset):
    def __init__(
        self,
        text_file: Path,
        sp_model_path: Path,
        max_seq_len: int = 256,
        max_samples: int | None = None,   # None = use the whole corpus
    ):
        text_file = Path(text_file)
        sp_model_path = Path(sp_model_path)
        tokens_path, offsets_path = _cache_paths(text_file, sp_model_path, max_seq_len)

        stale = (not tokens_path.exists() or not offsets_path.exists()
                 or tokens_path.stat().st_mtime < text_file.stat().st_mtime
                 or tokens_path.stat().st_mtime < sp_model_path.stat().st_mtime)
        if stale:
            _build_cache(text_file, sp_model_path, max_seq_len, tokens_path, offsets_path)

        self.tokens_path = tokens_path
        self.tokens = None  # opened lazily so each DataLoader worker maps its own
        self.offsets = np.load(offsets_path)
        n = len(self.offsets) - 1
        self.n = n if max_samples is None else min(n, max_samples)
        print(f"  loaded {self.n:,} samples ({int(self.offsets[self.n]):,} tokens) "
              f"from {text_file.name}")

    def __len__(self) -> int:
        return self.n

    def __getitem__(self, idx: int):
        if self.tokens is None:
            self.tokens = np.memmap(self.tokens_path, dtype=np.uint16, mode="r")
        ids = torch.from_numpy(
            self.tokens[self.offsets[idx]:self.offsets[idx + 1]].astype(np.int64))
        return ids[:-1], ids[1:]


class TokenBudgetBatchSampler:
    """Groups samples of similar length so batches carry little padding, and
    sizes each batch by tokens (padded length × lines) instead of line count.

    Indices are shuffled, cut into pools of many batches, sorted by length
    inside each pool, split into batches under the token budget, and the
    batches are shuffled again, so order stays random across lengths.
    """

    def __init__(self, lengths: np.ndarray, max_tokens: int, shuffle: bool = True,
                 pool_batches: int = 100, seed: int = 1337):
        self.lengths = lengths
        self.max_tokens = max_tokens
        self.shuffle = shuffle
        self.pool_size = pool_batches * max(1, max_tokens // max(1, int(lengths.mean())))
        self.seed = seed
        self.epoch = 0
        self._n_batches = len(self._make_batches(np.random.default_rng(seed)))

    def _make_batches(self, rng) -> list[np.ndarray]:
        idx = np.arange(len(self.lengths))
        if self.shuffle:
            rng.shuffle(idx)
        batches = []
        for start in range(0, len(idx), self.pool_size):
            pool = idx[start:start + self.pool_size]
            pool = pool[np.argsort(self.lengths[pool], kind="stable")]
            batch_start, longest = 0, 0
            for i, sample in enumerate(pool):
                longest = max(longest, int(self.lengths[sample]))
                if (i - batch_start + 1) * longest > self.max_tokens and i > batch_start:
                    batches.append(pool[batch_start:i])
                    batch_start, longest = i, int(self.lengths[sample])
            batches.append(pool[batch_start:])
        if self.shuffle:
            rng.shuffle(batches)
        return batches

    def __iter__(self):
        rng = np.random.default_rng(self.seed + self.epoch)
        self.epoch += 1
        for b in self._make_batches(rng):
            yield b.tolist()

    def __len__(self) -> int:
        return self._n_batches


def collate_fn(batch):
    xs, ys = zip(*batch)
    max_len = max(x.size(0) for x in xs)
    x_pad = torch.full((len(xs), max_len), 0, dtype=torch.long)  # pad id 0
    y_pad = torch.full((len(ys), max_len), -1, dtype=torch.long)  # ignored in loss
    for i, (x, y) in enumerate(zip(xs, ys)):
        x_pad[i, :x.size(0)] = x
        y_pad[i, :y.size(0)] = y
    return x_pad, y_pad


def make_loader(
    text_file: Path,
    sp_model_path: Path,
    max_tokens: int = 16384,
    shuffle: bool = True,
    num_workers: int = 4,
    **kwargs,
) -> DataLoader:
    ds = KeyboardDataset(text_file, sp_model_path, **kwargs)
    lengths = np.diff(ds.offsets[:ds.n + 1]) - 1   # input length = tokens - 1
    sampler = TokenBudgetBatchSampler(lengths, max_tokens, shuffle=shuffle)
    pin = torch.cuda.is_available()   # MPS doesn't support pin_memory
    # MPS: multiprocessing workers cause spawn overhead — run single-threaded
    workers = 0 if torch.backends.mps.is_available() else num_workers
    return DataLoader(ds, batch_sampler=sampler,
                      num_workers=workers, collate_fn=collate_fn, pin_memory=pin,
                      persistent_workers=workers > 0)
