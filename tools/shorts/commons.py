"""Wikimedia Commons에서 재사용 허가된 사진·영상을 찾고 내려받는 도구.

사용법
  검색:  python3 tools/shorts/commons.py search "Jensen Huang" "Nvidia"          # 사진
         python3 tools/shorts/commons.py search --video "Jensen Huang"           # 영상
  받기:  python3 tools/shorts/commons.py get <프로젝트폴더> <키> "<파일 제목>" [폭]
         (예: get videos/2026-11-19_젠슨황 speak "Jensen huang stanford 2026-04-09 033.jpg")

메모
- API(commons.wikimedia.org)는 자주 429(요청 과다)를 돌려준다. 파일 자체는
  upload.wikimedia.org 경로를 직접 계산해 받으므로 API가 막혀도 다운로드는 된다.
- 썸네일 폭은 표준값(330/500/960/1280/1920/3840)만 허용된다.
- 영상은 transcoded 1080p → 720p 순으로 시도한다.
- 라이선스·작성자는 프로젝트폴더/media.json 에 저장되고 렌더러가 출처 표기에 쓴다.
"""
import hashlib, json, os, re, subprocess, sys, time, urllib.parse

UA = "shortform-video-research/1.0 (educational)"
OK_LICENSES = ("public domain", "cc0", "cc by", "cc-by", "pdm", "공공누리", "kogl", "godl")


def curl(url, out=None, timeout=300):
    args = ["curl", "-sS", "-L", "-A", UA, "--max-time", str(timeout), "-w", "\n%{http_code}"]
    if out:
        args += ["-o", out]
    r = subprocess.run(args + [url], capture_output=True, text=not out)
    tail = r.stdout if isinstance(r.stdout, str) else r.stdout.decode(errors="ignore")
    code = tail.strip().rsplit("\n", 1)[-1]
    return code, (tail.rsplit("\n", 1)[0] if not out else None)


def api(params, tries=5):
    params = dict(params, format="json")
    for a in range(tries):
        code, body = curl("https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params), timeout=60)
        if code == "200":
            return json.loads(body)
        time.sleep(10 * (a + 1))
    raise RuntimeError(f"Commons API 실패({code}) — 잠시 후 다시 시도")


def clean(s):
    return re.sub(r"\s+", " ", re.sub("<[^>]+>", "", s or "")).strip()


def info(title):
    r = api(dict(action="query", titles="File:" + title, prop="imageinfo", iiprop="url|extmetadata|size"))
    p = list(r["query"]["pages"].values())[0]
    ii = p["imageinfo"][0]
    m = ii.get("extmetadata", {})
    g = lambda k: clean(m.get(k, {}).get("value"))
    return dict(title=title, lic=g("LicenseShortName"), artist=g("Artist")[:80], date=g("DateTimeOriginal")[:20],
                desc=g("ImageDescription")[:200], page=ii["descriptionurl"], w=ii.get("width"), h=ii.get("height"))


def search(queries, video=False, n=30):
    out, seen = [], set()
    for q in queries:
        r = api(dict(action="query", generator="search", gsrsearch=q + (" filetype:video" if video else " filetype:bitmap"),
                     gsrnamespace=6, gsrlimit=n, prop="imageinfo", iiprop="url|extmetadata|size"))
        for p in (r.get("query", {}).get("pages", {}) or {}).values():
            t = p["title"][5:]
            if t in seen:
                continue
            seen.add(t)
            ii = p["imageinfo"][0]
            m = ii.get("extmetadata", {})
            g = lambda k: clean(m.get(k, {}).get("value"))
            out.append(dict(title=t, lic=g("LicenseShortName"), artist=g("Artist")[:60], date=g("DateTimeOriginal")[:20],
                            w=ii.get("width"), h=ii.get("height"), dur=round(ii.get("duration") or 0), desc=g("ImageDescription")[:120]))
        time.sleep(2)
    return out


def paths(title):
    n = title.replace(" ", "_")
    h = hashlib.md5(n.encode()).hexdigest()
    q = urllib.parse.quote(n)
    base = f"https://upload.wikimedia.org/wikipedia/commons"
    return n, h, q, base


def download(proj, key, title, width=1920):
    os.makedirs(f"{proj}/media", exist_ok=True)
    n, h, q, base = paths(title)
    is_video = title.lower().endswith((".webm", ".ogv", ".mp4"))
    ext = "webm" if is_video else "jpg"
    out = f"{proj}/media/{key}.{ext}"
    if is_video:
        urls = [f"{base}/transcoded/{h[0]}/{h[:2]}/{q}/{q}.{r}.webm" for r in ("1080p.vp9", "720p.vp9")]
    else:
        urls = [f"{base}/thumb/{h[0]}/{h[:2]}/{q}/{width}px-{q}", f"{base}/{h[0]}/{h[:2]}/{q}"]
        if title.lower().endswith(".png"):
            urls[0] += ".png"
    for u in urls:
        for a in range(4):
            code, _ = curl(u, out)
            if code == "200":
                break
            if code == "404":
                break
            time.sleep(15 * (a + 1))
        if code == "200":
            break
    if code != "200":
        raise RuntimeError(f"{key}: 다운로드 실패 {code}")
    mf = f"{proj}/media.json"
    meta = json.load(open(mf)) if os.path.exists(mf) else {}
    try:
        meta[key] = dict(info(title), file=os.path.basename(out), type="video" if is_video else "image")
    except RuntimeError:
        meta[key] = dict(title=title, lic="(확인 필요)", artist="", page="https://commons.wikimedia.org/wiki/File:" + q,
                         file=os.path.basename(out), type="video" if is_video else "image")
    lic = meta[key]["lic"].lower()
    if not any(x in lic for x in OK_LICENSES) or "nc" in lic.split() or "-nc" in lic or "nd" in lic.split():
        print(f"  ⚠ 라이선스 확인 필요: {meta[key]['lic']}")
    json.dump(meta, open(mf, "w"), ensure_ascii=False, indent=1)
    print(key, "OK", meta[key]["lic"], "|", meta[key]["artist"][:40])


if __name__ == "__main__":
    cmd, rest = sys.argv[1], sys.argv[2:]
    if cmd == "search":
        video = "--video" in rest
        res = search([x for x in rest if x != "--video"], video=video)
        for i, o in enumerate(res):
            print(i, o["lic"], "|", o["title"][:90], "|", o["w"], "x", o["h"], f"| {o['dur']}s" if video else "", "|", o["date"], "|", o["desc"][:70])
    elif cmd == "get":
        download(rest[0], rest[1], rest[2], int(rest[3]) if len(rest) > 3 else 1920)
