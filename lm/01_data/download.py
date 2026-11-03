"""
Download Spanish corpus slices for keyboard LM training (FMAboard, adapted from
the pt-BR pipeline).

Uses streaming so only the rows we keep are downloaded. Weighted towards
everyday mobile writing: tweets and subtitle dialogue first, then Wikipedia
and clean web text for vocabulary and spelling.
"""
import re
import sys
from pathlib import Path
from datasets import load_dataset
from tqdm import tqdm

OUT_DIR = Path(__file__).parent / "raw"
OUT_DIR.mkdir(exist_ok=True)

_URL = re.compile(r"https?://\S+|www\.\S+")
_MENTION = re.compile(r"(^|\s)@\w+")
_HASHTAG = re.compile(r"#(\w+)")
_SPACES = re.compile(r"\s+")


def clean_tweet(doc: dict) -> str | None:
    text = doc.get("text") or ""
    if text.startswith("RT "):
        return None
    text = _URL.sub(" ", text)
    text = _MENTION.sub(" ", text)
    text = _HASHTAG.sub(r"\1", text)
    text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    text = _SPACES.sub(" ", text).strip()
    # Short tweets are fine: they look like chat messages
    if len(text) < 12 or len(text.split()) < 3:
        return None
    return text


def opus_spanish(doc: dict) -> str | None:
    return (doc.get("translation") or {}).get("es")


# Target: a few hundred million tokens is enough for a small keyboard LM.
SOURCES = [
    {
        # Informal social-media Spanish: closest to how people type on phones
        "id": "pysentimiento/spanish-tweets",
        "config": None,
        "split": "train",
        "extract": clean_tweet,
        "max_docs": 3_000_000,
        "out": "tweets_es.txt",
    },
    {
        # Subtitle dialogue (OPUS-100, Spanish side of en-es)
        "id": "Helsinki-NLP/opus-100",
        "config": "en-es",
        "split": "train",
        "extract": opus_spanish,
        "max_docs": 1_000_000,
        "out": "opus_es.txt",
    },
    {
        # Wikipedia es: clean grammar and spelling, broad vocabulary
        "id": "wikimedia/wikipedia",
        "config": "20231101.es",
        "split": "train",
        "extract": lambda doc: doc.get("text"),
        "max_docs": 150_000,
        "out": "wikipedia_es.txt",
    },
    {
        # FineWeb-2 Spanish: filtered, deduplicated web text
        "id": "HuggingFaceFW/fineweb-2",
        "config": "spa_Latn",
        "split": "train",
        "extract": lambda doc: doc.get("text"),
        "max_docs": 200_000,
        "out": "fineweb2_es.txt",
    },
]


def clean(text: str | None) -> str | None:
    if not text:
        return None
    text = text.strip()
    if len(text) < 12 or len(text) > 4000:
        return None
    if text.count("http") > 2 or text.count("<") > 4:
        return None
    return text


def download_source(src: dict) -> None:
    out_path = OUT_DIR / src["out"]
    if out_path.exists():
        print(f"[skip] {src['out']} already exists")
        return

    print(f"[fetch] {src['id']} …")
    kwargs = {"split": src["split"], "streaming": True}
    if src["config"]:
        kwargs["name"] = src["config"]

    dataset = load_dataset(src["id"], **kwargs)

    tmp_path = out_path.with_suffix(".part")
    written = 0
    with open(tmp_path, "w", encoding="utf-8") as fout:
        for doc in tqdm(dataset, total=src["max_docs"], unit="doc"):
            if written >= src["max_docs"]:
                break
            cleaned = clean(src["extract"](doc))
            if cleaned:
                # one document per line: newlines inside become spaces
                fout.write(cleaned.replace("\n", " ") + "\n")
                written += 1
    tmp_path.replace(out_path)

    print(f"[done] {written:,} docs → {out_path}")


if __name__ == "__main__":
    only = set(sys.argv[1:])
    for src in SOURCES:
        if only and src["out"] not in only:
            continue
        try:
            download_source(src)
        except Exception as e:
            print(f"[error] {src['id']}: {e}", file=sys.stderr)
