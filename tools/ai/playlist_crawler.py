#!/usr/bin/env python3
"""Simple playlist crawler: given seed URLs, discovers links to .m3u/.m3u8 and HTTP streams.

Usage:
    python tools/ai/playlist_crawler.py --seeds seeds.txt --out found_playlists.txt --max-depth 2

The script is deliberately conservative (rate-limit, same-host by default).
"""
from __future__ import annotations

import argparse
import re
import time
from collections import deque
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from tqdm import tqdm

LINK_RE = re.compile(r"(?i)https?://[^\s'\"]+\.(?:m3u8?|m3u)\b")


def find_playlist_links(text: str):
    links = set(LINK_RE.findall(text))
    return links


def crawl(seeds, max_depth=1, same_host=True, delay=0.5, timeout=8):
    seen = set()
    found = set()
    q = deque()
    for s in seeds:
        q.append((s, 0))
        seen.add(s)
    pbar = tqdm(total=0)
    while q:
        url, depth = q.popleft()
        try:
            time.sleep(delay)
            resp = requests.get(url, timeout=timeout, headers={"User-Agent": "myscanerIPTV-crawler/0.1"})
            text = resp.text
            # find direct playlist links in page
            for link in find_playlist_links(text):
                found.add(link)
            # also parse anchor hrefs
            if depth < max_depth:
                soup = BeautifulSoup(text, "lxml")
                anchors = [a.get("href") for a in soup.find_all("a") if a.get("href")]
                for href in anchors:
                    next_url = urljoin(url, href)
                    if next_url in seen:
                        continue
                    if same_host:
                        if urlparse(next_url).hostname != urlparse(url).hostname:
                            continue
                    seen.add(next_url)
                    q.append((next_url, depth + 1))
        except Exception:
            continue
    pbar.close()
    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", required=True, help="File with seed URLs (one per line)")
    parser.add_argument("--out", default="tools/ai/found_playlists.txt", help="Output file")
    parser.add_argument("--max-depth", type=int, default=1, help="Crawl depth")
    parser.add_argument("--delay", type=float, default=0.6, help="Delay between requests")
    parser.add_argument("--same-host", action="store_true", help="Only follow links on same host")
    args = parser.parse_args()

    seeds = [l.strip() for l in open(args.seeds, "r", encoding="utf-8") if l.strip()]
    found = crawl(seeds, max_depth=args.max_depth, same_host=args.same_host, delay=args.delay)
    if found:
        outp = open(args.out, "w", encoding="utf-8")
        for u in sorted(found):
            outp.write(u + "\n")
        outp.close()
    print(f"Found {len(found)} playlist links. Saved to {args.out}")


if __name__ == "__main__":
    main()
