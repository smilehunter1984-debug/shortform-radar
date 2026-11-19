"""세로 쇼츠 렌더러: 사진·영상 클립 + AI 내레이션 + 가운데 자막 + 출처 표기.

사용법
  미리보기(장면별 캡처 1장씩): python3 tools/shorts/render.py <프로젝트폴더> --preview
  본 렌더:                    python3 tools/shorts/render.py <프로젝트폴더>

입력: <프로젝트폴더>/script.json, media.json, media/*, tts/*.mp3
출력: <프로젝트폴더>/out/영상.mp4, 자막.srt, 유튜브_설명란.txt(출처 자동 채움), preview.jpg

script.json 장면(scene) 종류
  {"img": "키"}                                  사진 (천천히 확대/이동)
  {"vid": "키", "vid_start": 12.5, "crop_x": 0.5} 실제 영상 클립 (가로 영상은 세로로 잘라 꽉 채움,
                                                   crop_x 0~1 로 자를 위치 조정, "fit": "blur" 면 흐린 배경 + 원본 비율)
  {"card": ["큰 글자", "빨간 띠 글자"], "bg": "키"} 숫자·연도 강조 카드 (bg 사진을 흐리게 깔기)
공통: "say"(읽을 문장), "sub"(자막 1~2줄), "hl"(노란색 강조 단어)
"""
import json, os, subprocess, sys, wave
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

PROJ = sys.argv[1].rstrip("/")
PREVIEW = "--preview" in sys.argv
cfg = json.load(open(f"{PROJ}/script.json"))
media = json.load(open(f"{PROJ}/media.json"))
S = cfg["scenes"]
SPEED = float(cfg.get("speed", 1.1))
TITLE = cfg["title"]
os.makedirs(f"{PROJ}/out", exist_ok=True)

W, H, FPS, SR = 1080, 1920, 30, 44100
GAP, XF = 0.22, 6  # 문장 사이 쉼(초), 장면 전환 프레임 수
FD = "/usr/share/fonts/truetype/nanum/"
fnt = lambda n, s: ImageFont.truetype(FD + n, s)
YEL = (255, 214, 0)


def run(*a):
    return subprocess.run(a, check=True, capture_output=True, text=True)


# ---------------- 오디오 ----------------
clips = []
for i in range(len(S)):
    wav = f"{PROJ}/tts/{i:02d}.wav"
    run("ffmpeg", "-y", "-i", f"{PROJ}/tts/{i:02d}.mp3", "-af",
        "silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse",
        "-ar", str(SR), "-ac", "1", wav)
    with wave.open(wav) as w:
        clips.append(np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768)
starts, t = [], 0.35
for c in clips:
    starts.append(t)
    t += len(c) / SR + GAP
DUR = t + 1.4
SCENES = [(starts[i] if i else 0.0, starts[i + 1] if i + 1 < len(S) else DUR) for i in range(len(S))]
voice = np.zeros(int(DUR * SR) + 1)
for s, c in zip(starts, clips):
    voice[int(s * SR):int(s * SR) + len(c)] += c


def bgm(n):
    out = np.zeros(n)
    prog = [(57, 60, 64), (53, 57, 60), (48, 52, 55), (55, 59, 62)]
    beat, i, tt = 60 / 76, 0, 0.0
    while tt < n / SR:
        ch = prog[(i // 4) % 4]
        for m in [ch[i % 3]] + ([ch[0] - 12] if i % 4 == 0 else []):
            f, s0 = 440 * 2 ** ((m - 69) / 12), int(tt * SR)
            L = min(int(SR * 2.5), n - s0)
            if L > 0:
                x = np.arange(L) / SR
                out[s0:s0 + L] += (np.sin(2 * np.pi * f * x) + .25 * np.sin(4 * np.pi * f * x)) * np.exp(-x * 1.8) * (1 - np.exp(-x * 150))
        tt += beat
        i += 1
    return out / np.max(np.abs(out))


music = bgm(len(voice)) * 0.07
env = np.convolve((np.abs(voice) > 0.02).astype(float), np.ones(SR // 5) / (SR // 5), "same")
music *= 1 - 0.5 * np.clip(env * 3, 0, 1)
fo = int(SR * 1.2)
music[-fo:] *= np.linspace(1, 0, fo)
mix = voice * 0.95 + music
mix /= max(1.0, np.max(np.abs(mix)) / 0.95)
with wave.open(f"{PROJ}/out/mix.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((mix * 32767).astype(np.int16).tobytes())


# ---------------- 화면 ----------------
def cover(img, w, h, fx=0.5):
    s = max(w / img.width, h / img.height)
    img = img.resize((int(img.width * s + .5), int(img.height * s + .5)), Image.LANCZOS)
    x, y = int((img.width - w) * fx), (img.height - h) // 2
    return img.crop((x, y, x + w, y + h))


Z = 1.12
BW, BH = int(W * Z), int(H * Z)


def photo_base(key, fx=0.5):
    im = Image.open(f"{PROJ}/media/{media[key]['file']}").convert("RGB")
    if im.width / im.height <= 0.8:
        return cover(im, BW, BH, fx)
    bg = ImageEnhance.Brightness(cover(im, BW, BH).filter(ImageFilter.GaussianBlur(40))).enhance(0.45)
    fh = int(im.height * BW / im.width)
    if fh < BH * 0.42:
        ch = int(BH * 0.42); cw = int(ch * im.width / im.height)
        fit = im.resize((cw, ch), Image.LANCZOS).crop(((cw - BW) // 2, 0, (cw - BW) // 2 + BW, ch))
    else:
        fit = im.resize((BW, fh), Image.LANCZOS)
    bg.paste(fit, (0, (BH - fit.height) // 2 - int(BH * .03)))
    return bg


def card_base(s):
    key = s.get("bg") or next(k for k, v in media.items() if v["type"] == "image")
    bg = ImageEnhance.Brightness(photo_base(key).filter(ImageFilter.GaussianBlur(28))).enhance(0.32)
    big, small = s["card"]
    d = ImageDraw.Draw(bg)
    cx, cy, size = BW // 2, int(BH * 0.40), 200
    while d.textlength(big, font=fnt("NanumSquareB.ttf", size)) > W * 0.86:
        size -= 6
    d.text((cx, cy), big, font=fnt("NanumSquareB.ttf", size), fill=YEL, anchor="mm", stroke_width=4, stroke_fill=(0, 0, 0))
    fs = fnt("NanumSquareB.ttf", 58)
    while d.textlength(small, font=fs) > W * 0.84:
        fs = fnt("NanumSquareB.ttf", fs.size - 2)
    tw = d.textlength(small, font=fs)
    y0 = cy + size * .62
    d.rounded_rectangle((cx - tw / 2 - 34, y0, cx + tw / 2 + 34, y0 + 98), 18, fill=(200, 30, 30))
    d.text((cx, y0 + 49), small, font=fs, fill=(255, 255, 255), anchor="mm")
    return bg


class Clip:
    """영상 클립을 1080x1920 프레임으로 순서대로 읽는다."""
    def __init__(self, s, dur):
        m = media[s["vid"]]
        src = f"{PROJ}/media/{m['file']}"
        if s.get("fit") == "blur":
            vf = (f"split[a][b];[a]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},boxblur=30:2,eq=brightness=-0.25[bg];"
                  f"[b]scale={W}:-2[fg];[bg][fg]overlay=0:(H-h)/2-60")
        else:
            fx = s.get("crop_x", 0.5)
            vf = f"scale=-2:{H},crop={W}:{H}:(iw-{W})*{fx}:0"
        self.p = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-ss", str(s.get("vid_start", 0)), "-i", src, "-t", f"{dur + 1:.2f}",
                                   "-vf", vf + f",fps={FPS}", "-an", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        self.last = None

    def next(self):
        b = self.p.stdout.read(W * H * 3)
        if len(b) == W * H * 3:
            self.last = Image.frombytes("RGB", (W, H), b)
        return self.last or Image.new("RGB", (W, H))


bases = [None if "vid" in s else (card_base(s) if "card" in s else photo_base(s["img"], s.get("crop_x", 0.5))) for s in S]

g = np.zeros((H, W, 4), np.uint8)
g[:, :, 3] = np.concatenate([np.linspace(215, 0, 420), np.zeros(H - 420 - 760), np.linspace(0, 170, 760)])[:, None]
over = Image.fromarray(g, "RGBA")
d = ImageDraw.Draw(over)
ft = fnt("NanumSquareB.ttf", 72)
while max(d.textlength(l, font=ft) for l in TITLE) > W - 60:
    ft = fnt("NanumSquareB.ttf", ft.size - 2)
for k, ln in enumerate(TITLE):
    d.text((W // 2, 150 + k * 100), ln, font=ft, fill=(255, 255, 255) if k == 0 else YEL, anchor="mm", stroke_width=5, stroke_fill=(0, 0, 0))


def sub_layer(s):
    lay = Image.new("RGBA", (W, 330), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    size = 74
    while max(d.textlength(l, font=fnt("NanumSquareB.ttf", size)) for l in s["sub"]) > W - 90:
        size -= 2
    fs = fnt("NanumSquareB.ttf", size)
    for k, ln in enumerate(s["sub"]):
        segs = [(ln, (255, 255, 255))]
        if s.get("hl") and s["hl"] in ln:
            a, b = ln.split(s["hl"], 1)
            segs = [(a, (255, 255, 255)), (s["hl"], YEL), (b, (255, 255, 255))]
        x = (W - d.textlength(ln, font=fs)) / 2
        for txt, col in segs:
            d.text((x, 40 + k * (size + 30)), txt, font=fs, fill=col, stroke_width=9, stroke_fill=(0, 0, 0))
            x += d.textlength(txt, font=fs)
    return lay


subs = [sub_layer(s) for s in S]
fcred = fnt("NanumBarunGothic.ttf", 26)


def src_key(s):
    return s.get("img") or s.get("vid") or s.get("bg")


def credit(s):
    m = media.get(src_key(s)) or {}
    art = (m.get("artist") or "").split("\n")[0][:38]
    kind = "영상" if m.get("type") == "video" else "사진"
    return f"{kind}: {art + ' · ' if art else ''}{m.get('lic', '')} · Wikimedia Commons" if m else ""


creds = [credit(s) for s in S]
readers, last_frames = {}, {}


def scene_frame(i, t):
    st, en = SCENES[i]
    s = S[i]
    if "vid" in s:
        if i not in readers:
            readers[i] = Clip(s, en - st)
        if t >= en:  # 다음 장면으로 넘어가는 전환 중
            return last_frames.get(i) or readers[i].next()
        fr = readers[i].next()
        last_frames[i] = fr
        return fr
    p = min(1.0, max(0.0, (t - st) / max(en - st, .01)))
    z = (1.0 + 0.02 * p) if "card" in s else (1.0 + 0.10 * p if i % 2 == 0 else 1.10 - 0.10 * p)
    cw = BW / z
    ch = cw * H / W
    dx = (BW - cw) * (0.5 + (0.12 * (p - .5) if i % 3 == 1 else 0))
    return bases[i].resize((W, H), Image.BILINEAR, box=(dx, (BH - ch) * .5, dx + cw, (BH - ch) * .5 + ch))


def frame(fi):
    t = fi / FPS
    i = max(k for k, (st, _) in enumerate(SCENES) if st <= t)
    st = SCENES[i][0]
    fr = scene_frame(i, t)
    if i > 0 and t - st < XF / FPS:
        fr = Image.blend(scene_frame(i - 1, t), fr, (t - st) * FPS / XF)
    fr = fr.convert("RGBA")
    fr.alpha_composite(over)
    pop = min(1.0, (t - st) / 0.18)
    lay = subs[i]
    if pop < 1:
        sc = 0.9 + 0.1 * pop
        lay = lay.resize((int(W * sc), int(330 * sc)), Image.BILINEAR)
    fr.alpha_composite(lay, ((W - lay.width) // 2, 1180 + (330 - lay.height) // 2))
    ImageDraw.Draw(fr).text((W - 28, H - 70), creds[i], font=fcred, fill=(230, 230, 230), anchor="rs", stroke_width=2, stroke_fill=(0, 0, 0))
    return fr.convert("RGB")


if PREVIEW:
    ims = []
    for i, (st, en) in enumerate(SCENES):
        fi = int((st + (en - st) * 0.6) * FPS)
        if "vid" in S[i]:  # 미리보기에선 클립의 해당 지점만 바로 읽기
            readers[i] = Clip(dict(S[i], vid_start=S[i].get("vid_start", 0) + (en - st) * 0.6), 0.2)
        ims.append(frame(fi).resize((360, 640)))
    cols = 7
    sheet = Image.new("RGB", (360 * cols, 640 * ((len(ims) + cols - 1) // cols)))
    for k, im in enumerate(ims):
        sheet.paste(im, ((k % cols) * 360, (k // cols) * 640))
    sheet.save(f"{PROJ}/out/preview.jpg", quality=85)
    print("미리보기:", f"{PROJ}/out/preview.jpg", "| 예상 길이", round(DUR / SPEED, 1), "초 (", SPEED, "배속 )")
    sys.exit()

name = cfg.get("name", os.path.basename(PROJ))
mp4 = f"{PROJ}/out/{name}.mp4"
p = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                      "-i", f"{PROJ}/out/mix.wav",
                      "-filter_complex", f"[0:v]setpts=PTS/{SPEED}[v];[1:a]atempo={SPEED},loudnorm=I=-14:TP=-1.5:LRA=11[a]",
                      "-map", "[v]", "-map", "[a]", "-r", str(FPS), "-c:v", "libx264", "-preset", "medium", "-crf", "20",
                      "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-shortest", "-movflags", "+faststart", mp4],
                     stdin=subprocess.PIPE)
for fi in range(int(DUR * FPS)):
    p.stdin.write(frame(fi).tobytes())
p.stdin.close()
p.wait()


def ts(x):
    x /= SPEED
    return f"{int(x // 3600):02d}:{int(x % 3600 // 60):02d}:{int(x % 60):02d},{int(x % 1 * 1000):03d}"


with open(f"{PROJ}/out/{name}.srt", "w") as f:
    for i, s in enumerate(S):
        f.write(f"{i + 1}\n{ts(starts[i])} --> {ts(starts[i] + len(clips[i]) / SR)}\n{' '.join(s['sub'])}\n\n")

used = []
for s in S:
    k = src_key(s)
    if k and k not in used:
        used.append(k)
lines = [f"- {media[k]['title']} — {(media[k].get('artist') or '작자 미상').splitlines()[0]} / {media[k]['lic']}\n  {media[k]['page']}" for k in used]
tpl = f"{PROJ}/설명란_초안.txt"
if os.path.exists(tpl):
    open(f"{PROJ}/out/유튜브_설명란.txt", "w").write(open(tpl).read().replace("{CREDITS}", "\n".join(lines)))

# ---- 자동 검수 ----
dur = float(run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", mp4).stdout)
vol = subprocess.run(["ffmpeg", "-hide_banner", "-i", mp4, "-af", "volumedetect", "-vn", "-f", "null", "-"], capture_output=True, text=True).stderr
mean = [l.split(":")[1].strip() for l in vol.splitlines() if "mean_volume" in l]
bad = [k for k in used if "확인" in media[k]["lic"] or not media[k]["lic"]]
print(f"완료: {mp4}\n  길이 {dur:.1f}초 ({SPEED}배속) | 평균 음량 {mean[0] if mean else '?'} | 출처 {len(used)}건"
      + (f" | ⚠ 라이선스 확인 필요: {bad}" if bad else ""))
