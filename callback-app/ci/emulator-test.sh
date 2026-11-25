#!/usr/bin/env bash
# 가상 안드로이드폰에서 앱을 설치하고 가짜 전화를 걸어 문자 발송 규칙을 자동으로 검사한다.
set -u
PKG=com.comma.callback
OUT=emulator-results
mkdir -p "$OUT"
log() { echo "=== $*"; }

wait_s() { sleep "$1"; }

log "설치"
adb install -r app/build/outputs/apk/debug/app-debug.apk

log "루트 + 한국 시간 낮 12시로 맞추기(야간 발송 금지 때문에)"
adb root; sleep 3; adb wait-for-device
adb shell settings put global auto_time 0
adb shell setprop persist.sys.timezone Asia/Seoul
adb shell "date $(TZ=Asia/Seoul date +%m%d)1200$(date +%Y).00" || true
adb shell date

log "권한 허용"
for p in READ_PHONE_STATE READ_CALL_LOG SEND_SMS READ_CONTACTS POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p || true
done
adb shell dumpsys deviceidle whitelist +$PKG || true

write_prefs() {
  cat > /tmp/prefs.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="enabled" value="true" />
    <boolean name="test_mode" value="$1" />
    <string name="test_numbers">$2</string>
    <string name="start_hour">8</string>
    <string name="end_hour">21</string>
</map>
XML
  adb shell am force-stop $PKG
  adb push /tmp/prefs.xml /data/local/tmp/prefs.xml >/dev/null
  adb shell chmod 644 /data/local/tmp/prefs.xml
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/prefs.xml shared_prefs/${PKG}_preferences.xml'"
  # 앱을 한 번 열어야 통화 방송을 받는다(설치 직후/강제종료 후 '중지' 상태 해제)
  adb shell am start -n $PKG/.MainActivity >/dev/null
  wait_s 4
  adb shell input keyevent KEYCODE_HOME
  wait_s 1
}

missed() {   # 벨만 울리고 끊김
  log "부재중: $1"
  adb emu gsm call "$1"; wait_s 3
  adb emu gsm cancel "$1"; wait_s 14
}
answered() { # 받고 통화 후 끊김
  log "받은 전화: $1"
  adb emu gsm call "$1"; wait_s 2
  adb emu gsm accept "$1"; wait_s 4
  adb emu gsm cancel "$1"; wait_s 14
}

log "연락처 1개 저장(01055556666)"
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_name:n: --bind account_type:n:
RID=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | sort -n | tail -1)
adb shell content insert --uri content://com.android.contacts/data \
  --bind raw_contact_id:i:$RID --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:01055556666 --bind data2:i:2
adb shell content insert --uri content://com.android.contacts/data \
  --bind raw_contact_id:i:$RID --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:가족

log "1부: 실제 운영 모드"
write_prefs false ""
missed   01011112222     # 1. 부재중 → 발송
missed   01011112222     # 2. 같은 번호 다시 → 30일 안이라 안 보냄
missed   0212345678      # 3. 일반전화 → 안 보냄
answered 01033334444     # 4. 받은 전화 → 발송
missed   01055556666     # 5. 연락처 번호 → 안 보냄

log "2부: 시험 모드(시험용 번호 01077778888)"
write_prefs true "01077778888"
missed   01099990000     # 6. 고객 번호 → 시험 모드라 안 보냄
missed   01077778888     # 7. 시험용 번호 → 발송

wait_s 10
log "화면 캡처"
adb shell am start -n $PKG/.MainActivity >/dev/null; wait_s 3
adb exec-out screencap -p > "$OUT/1-main.png"
adb shell am start -n $PKG/.LogActivity >/dev/null; wait_s 3
adb exec-out screencap -p > "$OUT/2-log.png"

log "이력 DB 가져오기"
for f in callback.db callback.db-wal callback.db-shm; do
  adb exec-out run-as $PKG cat databases/$f > "$OUT/$f" 2>/dev/null || rm -f "$OUT/$f"
done
adb shell content query --uri content://call_log/calls --projection number:type:duration > "$OUT/calllog.txt" || true
adb logcat -d > "$OUT/logcat.txt" || true

python3 ci/check_results.py "$OUT/callback.db"
