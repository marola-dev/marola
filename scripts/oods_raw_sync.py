#!/usr/bin/env python3
"""oods_raw_sync — the OODS raw layer lives in a Hugging Face dataset, not in git (MIP-0056 §4.4).

    scripts/oods_raw_sync.py pull                    # dataset raw/** -> data/oods/raw/
    scripts/oods_raw_sync.py push --mode backfill    # data/oods/raw/ -> dataset, one commit
    scripts/oods_raw_sync.py pull --dry-run          # list what would change, transfer nothing
    scripts/oods_raw_sync.py --self-test             # just quality-other

Identity comes from `data/oods/sources.json`'s top-level `raw_store` block, so the repo names the
dataset once. The manifest is unchanged by the offload: its keys are still `raw/...`, and
`public_url` turns one into the file's public URL.

`push` needs `HF_TOKEN` (write); `pull` of a public dataset needs no token at all.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path

DATA_DIR = Path("data/oods")
CARD = DATA_DIR / "DATASET-CARD.md"


@dataclass(frozen=True)
class RawStore:
    kind: str
    repo: str
    prefix: str


def raw_store(registry: dict) -> RawStore:
    block = registry.get("raw_store")
    if not block:
        raise SystemExit("sources.json has no top-level raw_store block")
    if block.get("kind") != "hf-dataset":
        raise SystemExit(f"raw_store kind {block.get('kind')!r} is not supported")
    return RawStore(block["kind"], block["repo"], block["prefix"])


def public_url(store: RawStore, key: str) -> str:
    """A manifest key (`raw/ima-sc/csv/<city>/<beach>/<year>.csv`) as a URL anyone can fetch."""
    return f"https://huggingface.co/datasets/{store.repo}/resolve/main/{key}"


def allow_patterns(store: RawStore) -> list[str]:
    return [f"{store.prefix.rstrip('/')}/**"]


def commit_message(source: str, mode: str, date: str) -> str:
    return f"oods raw: {source} {mode} {date}"


def git_blob_sha1(data: bytes) -> str:
    # git's own blob hash, so a plain (non-LFS) file on the Hub compares without downloading it.
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def local_index(root: Path, store: RawStore) -> dict[str, tuple[str, str]]:
    """Every raw file by its repo path, hashed both ways — see `same` for why both."""
    prefix = store.prefix.rstrip("/")
    out = {}
    for file in sorted(p for p in root.rglob("*") if p.is_file()):
        data = file.read_bytes()
        out[f"{prefix}/{file.relative_to(root).as_posix()}"] = (
            git_blob_sha1(data),
            hashlib.sha256(data).hexdigest(),
        )
    return out


def same(local: tuple[str, str], remote: tuple[str, str | None]) -> bool:
    """A dataset repo's default `.gitattributes` puts `*.csv` in LFS, so both shapes really occur:
    an LFS entry exposes the content's sha256, a plain blob only git's own sha1."""
    git_sha1, sha256 = local
    blob_id, lfs_sha256 = remote
    return sha256 == lfs_sha256 if lfs_sha256 else git_sha1 == blob_id


def delta(
    local: dict[str, tuple[str, str]], remote: dict[str, tuple[str, str | None]]
) -> dict[str, list[str]]:
    return {
        "added": sorted(p for p in local if p not in remote),
        "changed": sorted(p for p in local if p in remote and not same(local[p], remote[p])),
        "removed": sorted(p for p in remote if p not in local),
    }


def report(what: str, changes: dict[str, list[str]]) -> None:
    for kind, paths in changes.items():
        for path in paths[:10]:
            print(f"  {kind}: {path}")
        if len(paths) > 10:
            print(f"  {kind}: … and {len(paths) - 10} more")
    total = sum(len(p) for p in changes.values())
    print(f"{what} (dry run): {total} files would change")


def _api(token: str | None):
    from huggingface_hub import HfApi

    return HfApi(token=token)


def _hub_errors() -> tuple[type[BaseException], ...]:
    """Lazy: `--self-test` must pass on a checkout without huggingface_hub installed."""
    try:
        from huggingface_hub.errors import HfHubHTTPError, RepositoryNotFoundError
    except ImportError:
        return ()
    return (RepositoryNotFoundError, HfHubHTTPError)


def remote_index(api, store: RawStore) -> dict[str, tuple[str, str | None]]:
    """`{}` when the dataset does not exist yet — the bootstrap run, not an error."""
    try:
        tree = api.list_repo_tree(
            store.repo, repo_type="dataset", recursive=True, path_in_repo=store.prefix.rstrip("/")
        )
        return {
            e.path: (e.blob_id, getattr(e.lfs, "sha256", None) if e.lfs else None)
            for e in tree
            if hasattr(e, "blob_id")
        }
    except _hub_errors() as err:
        print(
            f"the dataset {store.repo} is not readable yet ({type(err).__name__}) — treating "
            "it as empty"
        )
        return {}


def push(store: RawStore, root: Path, source: str, mode: str, dry_run: bool, api=None) -> int:
    token = os.environ.get("HF_TOKEN")
    if not dry_run and not token:
        print(
            "error: HF_TOKEN is not set — push needs write access to the dataset", file=sys.stderr
        )
        return 1
    if not root.is_dir():
        print(f"error: {root} does not exist — nothing to push", file=sys.stderr)
        return 1
    api = api or _api(token)
    if dry_run:
        report(f"push to {store.repo}", delta(local_index(root, store), remote_index(api, store)))
        return 0
    api.create_repo(store.repo, repo_type="dataset", exist_ok=True)
    if CARD.is_file():
        api.upload_file(
            path_or_fileobj=CARD.read_bytes(),
            path_in_repo="README.md",
            repo_id=store.repo,
            repo_type="dataset",
            commit_message="oods raw: dataset card",
        )
    # upload_folder hashes before it sends, so a re-push of unchanged files uploads nothing and
    # makes no commit.
    api.upload_folder(
        folder_path=str(root),
        path_in_repo=store.prefix.rstrip("/"),
        repo_id=store.repo,
        repo_type="dataset",
        commit_message=commit_message(source, mode, dt.datetime.now(dt.UTC).date().isoformat()),
    )
    print(f"pushed {root} to https://huggingface.co/datasets/{store.repo}")
    return 0


def pull(store: RawStore, root: Path, dry_run: bool, api=None, download=None) -> int:
    api = api or _api(os.environ.get("HF_TOKEN"))
    remote = remote_index(api, store)
    if not remote:
        print(f"nothing to pull — {store.repo} has no {store.prefix} yet")
        return 0
    if dry_run:
        local = local_index(root, store) if root.is_dir() else {}
        report(f"pull from {store.repo}", delta(remote, local))
        return 0
    if download is None:
        from huggingface_hub import snapshot_download

        download = snapshot_download
    # local_dir is the store root, not `raw/`: repo paths already start with the prefix. The
    # download is cached, so a daily run re-transfers only what moved.
    download(
        store.repo,
        repo_type="dataset",
        allow_patterns=allow_patterns(store),
        local_dir=str(root.parent),
        token=os.environ.get("HF_TOKEN"),
    )
    print(f"pulled {len(remote)} files into {root}")
    return 0


class Recorder:
    """The self-test's HfApi: records calls, answers an empty dataset."""

    def __init__(self):
        self.calls = []

    def __getattr__(self, name):
        def record(*args, **kwargs):
            self.calls.append((name, args, kwargs))
            return []

        return record


def self_test() -> int:
    fails = 0

    def ok(got, want, label):
        nonlocal fails
        if got == want:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label} — got {got!r}, want {want!r}")

    store = raw_store({"raw_store": {"kind": "hf-dataset", "repo": "o/r", "prefix": "raw/"}})
    ok(store.repo, "o/r", "the dataset id comes from sources.json")
    ok(
        public_url(store, "raw/ima-sc/csv/florianopolis/campeche/2003.csv"),
        "https://huggingface.co/datasets/o/r/resolve/main/raw/ima-sc/csv/florianopolis/campeche/2003.csv",
        "a manifest key reconstructs to a public URL",
    )
    ok(allow_patterns(store), ["raw/**"], "the pull allow-pattern is the prefix, not the world")
    ok(
        commit_message("ima-sc", "backfill", "2026-09-14"),
        "oods raw: ima-sc backfill 2026-09-14",
        "the commit message names source, mode and date",
    )

    local = {"raw/a.csv": ("s1", "h1"), "raw/b.csv": ("s2", "h2")}
    remote = {"raw/a.csv": ("s1", None), "raw/b.csv": ("x", "other"), "raw/gone.csv": ("s3", None)}
    ok(
        delta(local, remote),
        {"added": [], "changed": ["raw/b.csv"], "removed": ["raw/gone.csv"]},
        "the delta compares on sha256 when the remote is LFS, git sha1 otherwise",
    )
    ok(
        same(("s", "h"), ("different", "h")),
        True,
        "an LFS entry whose sha256 matches is unchanged whatever its blob id says",
    )
    ok(
        git_blob_sha1(b"hi\n"),
        "45b983be36b73c0788dc9cbcb76cbb80fc7bb057",
        "git_blob_sha1 is git's own blob hash",
    )

    registry = json.loads((DATA_DIR / "sources.json").read_text(encoding="utf-8"))
    on_disk = raw_store(registry)
    ok(on_disk.prefix, "raw/", "the committed sources.json carries a usable raw_store block")
    ok(
        public_url(on_disk, "raw/ima-sc/points.json").startswith(
            "https://huggingface.co/datasets/"
        ),
        True,
        "the committed dataset id makes a huggingface.co URL",
    )
    ok(CARD.is_file(), True, f"{CARD} exists — push uploads it as the dataset README")

    api = Recorder()
    os.environ["HF_TOKEN"] = "self-test"
    ok(
        push(on_disk, Path("scripts"), "ima-sc", "incremental", dry_run=False, api=api),
        0,
        "push returns 0",
    )
    names = [c[0] for c in api.calls]
    ok(names[0], "create_repo", "push creates the dataset repo first")
    ok(api.calls[0][2]["repo_type"], "dataset", "…as a dataset, not a model")
    ok(api.calls[0][2]["exist_ok"], True, "…and tolerates one that already exists")
    ok("upload_folder" in names, True, "push uploads the folder in one call")
    folder = next(c for c in api.calls if c[0] == "upload_folder")[2]
    ok(folder["path_in_repo"], "raw", "the folder lands under the prefix")
    ok(
        folder["commit_message"].startswith("oods raw: ima-sc incremental "),
        True,
        "one named commit",
    )
    card = next(c for c in api.calls if c[0] == "upload_file")[2]
    ok(card["path_in_repo"], "README.md", "the dataset card is uploaded as README.md")

    del os.environ["HF_TOKEN"]

    api = Recorder()
    ok(
        pull(on_disk, DATA_DIR / "raw", dry_run=False, api=api),
        0,
        "a pull from an empty dataset is not an error",
    )
    ok([c[0] for c in api.calls], ["list_repo_tree"], "…and downloads nothing")

    print("oods_raw_sync self-test: " + ("FAILED" if fails else "ok"))
    return 1 if fails else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("command", nargs="?", choices=["push", "pull"])
    ap.add_argument("--data-dir", type=Path, default=DATA_DIR)
    ap.add_argument("--source", default="ima-sc")
    ap.add_argument("--mode", default="incremental")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.command:
        ap.error("a command is required: push or pull")
    registry = json.loads((args.data_dir / "sources.json").read_text(encoding="utf-8"))
    store = raw_store(registry)
    root = args.data_dir / store.prefix.rstrip("/")
    if args.command == "push":
        return push(store, root, args.source, args.mode, args.dry_run)
    return pull(store, root, args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
