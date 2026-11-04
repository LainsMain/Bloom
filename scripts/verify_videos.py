#!/usr/bin/env python3
"""Checks candidate YouTube IDs via the public oEmbed endpoint (exists + embeddable)
and writes the first working one per exercise to app/src/main/assets/videos.json.

Usage: python3 -I scripts/verify_videos.py scripts/video_candidates.txt app/src/main/assets/videos.json
"""
import json, sys, time, urllib.error, urllib.parse, urllib.request

src, out = sys.argv[1], sys.argv[2]
result, failed = {}, []
for line in open(src, encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    ex, *ids = line.split()
    for vid in ids:
        url = "https://www.youtube.com/oembed?format=json&url=" + urllib.parse.quote(f"https://www.youtube.com/watch?v={vid}")
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=15) as r:
                data = json.load(r)
            result[ex] = {"id": vid, "title": data.get("title", ""), "channel": data.get("author_name", ""),
                          "vertical": data.get("height", 0) > data.get("width", 1)}
            print(f"OK   {ex:24} {vid}  {data.get('title','')[:60]} — {data.get('author_name','')}")
            break
        except urllib.error.HTTPError as e:
            print(f"skip {ex:24} {vid}  HTTP {e.code}")
        time.sleep(0.2)
    else:
        failed.append(ex)
json.dump(result, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print(f"\n{len(result)} verified, failed: {failed}")
