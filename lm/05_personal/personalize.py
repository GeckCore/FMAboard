"""
FMAboard: fine-tune the Spanish keyboard model on your own typing.

    python 05_personal/personalize.py            # pull from phone, build, train, export
    python 05_personal/personalize.py --source D:/backup/FMAboard   # use a copied folder
    python 05_personal/personalize.py --build-only                  # just build the dataset

Steps:
  1. Collect *.messages.jsonl (schema in keyboard/.../fmaboard/SCHEMA.md) either
     from the phone's Documents/FMAboard folder over adb, or from --source.
  2. Build training text:
       - every sentence you wrote (repeated, it is the signal we care about)
       - your real autocorrections as FUTO correction samples
         ("…context <XBU><CHAR_Q><CHAR_E><CHAR_U><XBC>que <XEC>")
       - simulated typos over your own sentences, so autocorrect learns your words
       - a sample of the general corpus ("replay") so the model keeps normal Spanish
  3. Fine-tune from the base checkpoint, export GGUF, quantize to Q6_K, validate.

Everything stays on this PC under lm/personal/ (git-ignored).
"""
import argparse
import json
import random
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "01_data"))
from make_correction_data import (  # noqa: E402
    format_word_correction, misspell_word, randomly_misspell_words)

PERSONAL = ROOT / "personal"
RAW = PERSONAL / "raw"
DATA = PERSONAL / "data"
CKPT_DIR = ROOT / "02_train" / "personal"
BASE_CKPT = ROOT / "02_train" / "checkpoints" / "best.pt"
BASE_CORPUS = ROOT / "01_data" / "processed" / "correction_train.txt"
ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
PHONE_DIR = "/sdcard/Documents/FMAboard"

PERSONAL_REPEAT = 4        # how many times each personal sentence is seen
REPLAY_RATIO = 3           # general corpus lines per personal line
MIN_SENTENCES = 200        # below this, fine-tuning mostly overfits noise


def pull_from_phone() -> Path:
    RAW.mkdir(parents=True, exist_ok=True)
    print(f"Pulling {PHONE_DIR} from phone …")
    subprocess.run([str(ADB), "pull", PHONE_DIR, str(RAW)], check=True)
    return RAW / "FMAboard"


def load_messages(folder: Path) -> list[dict]:
    messages, seen = [], set()
    for f in sorted(folder.rglob("*.messages*.jsonl")):
        for line in f.read_text(encoding="utf-8").splitlines():
            try:
                m = json.loads(line)
            except json.JSONDecodeError:
                continue
            if m.get("id") in seen:
                continue
            seen.add(m.get("id"))
            messages.append(m)
    return messages


_SENT_SPLIT = re.compile(r"(?<=[.!?\n])\s+")
_SKIP_FIELDS = {"email", "uri", "search", "person_name", "postal_address"}


def own_text(m: dict) -> str:
    """What you actually wrote: final text minus what was already there."""
    text, pre = m.get("text") or "", m.get("prefilled") or ""
    return text[len(pre):] if pre and text.startswith(pre) else text


def sentences_of(m: dict) -> list[str]:
    if m.get("field") in _SKIP_FIELDS:
        return []
    out = []
    for s in _SENT_SPLIT.split(own_text(m)):
        s = " ".join(s.split())
        if len(s) >= 2 and any(c.isalpha() for c in s):
            out.append(s)
    return out


def correction_lines(m: dict) -> list[str]:
    """Real autocorrections, with the words before them as context."""
    text = own_text(m)
    lines = []
    for c in m.get("corrections") or []:
        typed, final = c.get("typed", ""), c.get("final", "")
        idx = text.find(final)
        if not typed or not final or idx < 0:
            continue
        context = " ".join(text[:idx].split()[-12:])
        sample = format_word_correction(typed.lower(), final)
        if sample:
            lines.append(f"{context} {sample}".strip())
    return lines


def build_dataset(messages: list[dict]) -> tuple[Path, Path, int]:
    DATA.mkdir(parents=True, exist_ok=True)
    random.seed(1337)

    sentences = [s for m in messages for s in sentences_of(m)]
    corrections = [l for m in messages for l in correction_lines(m)]
    print(f"{len(messages):,} messages → {len(sentences):,} sentences, "
          f"{len(corrections):,} real corrections")

    random.shuffle(sentences)
    n_valid = max(1, len(sentences) // 20)
    valid, train_sents = sentences[:n_valid], sentences[n_valid:]

    personal = []
    for s in train_sents:
        for _ in range(PERSONAL_REPEAT):
            personal.append(s)
            personal.append(randomly_misspell_words(s))
        # word-level typo practice on your own vocabulary
        words = s.split()
        if words:
            i = random.randrange(len(words))
            w = words[i]
            if w.isalpha():
                sample = format_word_correction(misspell_word(w), w)
                if sample:
                    personal.append(" ".join(words[:i] + [sample]))
    personal += corrections * (PERSONAL_REPEAT * 2)

    replay = []
    if BASE_CORPUS.exists():
        want = len(personal) * REPLAY_RATIO
        with open(BASE_CORPUS, encoding="utf-8") as f:
            for i, line in enumerate(f):
                if len(replay) < want:
                    replay.append(line.rstrip("\n"))
                else:  # reservoir sampling over the whole corpus
                    j = random.randrange(i + 1)
                    if j < want:
                        replay[j] = line.rstrip("\n")

    lines = personal + replay
    random.shuffle(lines)

    train_path, valid_path = DATA / "personal_train.txt", DATA / "personal_valid.txt"
    train_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    valid_path.write_text("\n".join(valid) + "\n", encoding="utf-8")
    print(f"train: {len(lines):,} lines ({len(personal):,} personal + {len(replay):,} replay)")
    print(f"valid: {len(valid):,} of your sentences (held out)")
    return train_path, valid_path, len(train_sents)


def run(cmd: list[str], env: dict | None = None) -> None:
    import os
    print("$", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd=ROOT, env={**os.environ, **(env or {})})


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--source", help="folder with *.messages.jsonl (default: pull from phone)")
    p.add_argument("--build-only", action="store_true")
    p.add_argument("--epochs", type=int, default=3)
    p.add_argument("--lr", type=float, default=1e-4)
    p.add_argument("--force", action="store_true", help="train even with little data")
    args = p.parse_args()

    folder = Path(args.source) if args.source else pull_from_phone()
    messages = load_messages(folder)
    train_path, valid_path, n_sent = build_dataset(messages)
    if args.build_only:
        return
    if n_sent < MIN_SENTENCES and not args.force:
        print(f"Only {n_sent} sentences of your own so far; wait until ~{MIN_SENTENCES} "
              f"(or pass --force).")
        return
    if not BASE_CKPT.exists():
        sys.exit(f"Base model {BASE_CKPT} not found: train the base model first.")

    py = sys.executable
    run([py, "02_train/train.py", "--init", str(BASE_CKPT),
         "--data", str(train_path), "--valid", str(valid_path),
         "--epochs", str(args.epochs), "--lr", str(args.lr), "--warmup", "100",
         "--out", str(CKPT_DIR)])

    f32 = PERSONAL / "es_personal_f32.gguf"
    q6k = PERSONAL / f"es_personal_{date.today():%Y%m%d}.gguf"
    run([py, "03_convert/export_to_gguf.py"], env={
        "KEYBOARD_CKPT": str(CKPT_DIR / "best.pt"), "KEYBOARD_OUT": str(f32),
        "KEYBOARD_NAME": f"FMAboard personal {date.today()}",
        "KEYBOARD_HISTORY": f"{date.today()}: fine-tuned on {n_sent} of your sentences",
    })
    run(["bash", "03_convert/quantize.sh"], env={
        "F32_GGUF_OVERRIDE": str(f32), "Q6K_GGUF_OVERRIDE": str(q6k)})
    run([py, "04_inject/inject_metadata.py"], env={"KEYBOARD_MODEL": str(q6k)})
    f32.unlink(missing_ok=True)
    print(f"\nPersonal model ready: {q6k}")


if __name__ == "__main__":
    main()
