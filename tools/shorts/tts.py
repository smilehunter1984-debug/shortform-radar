"""대본(script.json)의 각 줄을 AI 음성(Microsoft Edge TTS)으로 만든다.

사용법: python3 tools/shorts/tts.py <프로젝트폴더> [--force]
필요: pip install edge-tts, 네트워크 허용 speech.platform.bing.com
"""
import asyncio, json, os, subprocess, sys
import certifi

if os.path.exists("/root/.ccr/ca-bundle.crt"):  # 클라우드 프록시 인증서
    certifi.where = lambda: "/root/.ccr/ca-bundle.crt"
import edge_tts

proj = sys.argv[1]
cfg = json.load(open(f"{proj}/script.json"))
VOICE = cfg.get("voice", "ko-KR-HyunsuMultilingualNeural")
RATE, PITCH = cfg.get("rate", "+16%"), cfg.get("pitch", "-4Hz")
os.makedirs(f"{proj}/tts", exist_ok=True)


def ok(p):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p], capture_output=True, text=True)
    try:
        return float(r.stdout) > 0.5
    except ValueError:
        return False


async def main():
    total = 0.0
    for i, s in enumerate(cfg["scenes"]):
        p = f"{proj}/tts/{i:02d}.mp3"
        if ok(p) and "--force" not in sys.argv:
            continue
        for _ in range(6):
            try:
                await edge_tts.Communicate(s["say"], VOICE, rate=RATE, pitch=PITCH, proxy=os.environ.get("HTTPS_PROXY")).save(p)
                if ok(p):
                    break
            except Exception as e:
                print(i, "재시도", type(e).__name__)
            await asyncio.sleep(2)
        print(i, "OK" if ok(p) else "실패")


asyncio.run(main())
