import sqlite3, sys

db = sqlite3.connect(sys.argv[1])
rows = db.execute("SELECT id, number, call_type, status, kind, reason FROM log ORDER BY id").fetchall()
print("\n----- 발송 이력 -----")
for r in rows:
    print(r)

expect = [
    ("01011112222", "부재중",   "SENT",    ""),
    ("01011112222", "부재중",   "SKIPPED", "이미 보냄"),
    ("0212345678",  "부재중",   "SKIPPED", "휴대폰 번호 아님"),
    ("01033334444", "받은 전화", "SENT",    ""),
    ("01055556666", "부재중",   "SKIPPED", "연락처"),
    ("01099990000", "부재중",   "SKIPPED", "시험 모드"),
    ("01077778888", "부재중",   "SENT",    ""),
]
names = [
    "부재중 → 문자 발송",
    "같은 번호 재통화 → 30일 제한으로 안 보냄",
    "일반전화 → 안 보냄",
    "받은 전화 → 문자 발송",
    "연락처 저장 번호 → 안 보냄",
    "시험 모드에서 고객 번호 → 안 보냄",
    "시험 모드에서 시험용 번호 → 문자 발송",
]

ok = True
print("\n----- 검사 결과 -----")
for i, (num, ctype, status, reason) in enumerate(expect):
    got = rows[i] if i < len(rows) else None
    good = (got is not None and got[1] == num and got[2] == ctype and got[3] == status and reason in got[5])
    ok &= good
    print(("통과 " if good else "실패 ") + f"{i+1}. {names[i]}" + ("" if good else f"   (실제: {got})"))
if len(rows) != len(expect):
    ok = False
    print(f"실패: 이력 개수 {len(rows)}개 (기대 {len(expect)}개)")
sys.exit(0 if ok else 1)
