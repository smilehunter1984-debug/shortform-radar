# 콤마 콜백문자 앱 (Android, Kotlin)

갤럭시폰 1대에서 사장님 혼자 쓰는 통화 후 자동 문자 앱. 플레이스토어에 올리지 않고 직접 설치(사이드로딩)한다.
사용자는 코딩을 모르므로 설명은 쉬운 한국어로 한다.

## 빌드
- PC: `cd callback-app && ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 폰 설치(USB): `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- GitHub: `callback-app/` 를 바꿔 push 하면 Actions(`.github/workflows/callback-app.yml`)가 단위 테스트·APK 빌드·에뮬레이터 시험(`ci/emulator-test.sh`)을 돌리고, APK를 아티팩트와 고정 주소(릴리스 태그 `callback-app-latest`의 `comma-callback.apk`)로 올린다. 고칠 때마다 `versionCode`를 올린다.
- 서명키 `keystore/debug.keystore` 를 고정으로 쓴다. 바꾸면 폰에서 앱을 지우고 다시 깔아야 하므로(설정·이력 삭제) 바꾸지 말 것.

## 구조 (app/src/main/java/com/comma/callback)
- `CallStateReceiver` 전화 상태 방송 → 끊김 시 `CallProcessor.onCallEnded`
- `CallProcessor` 통화기록 재조회로 번호·종류 확정 → 규칙 검사 → 발송/건너뜀 기록
- `SmsSender` 80바이트 이하 단문, 초과 시 `MmsPdu` 로 만든 장문(LMS, 제목 포함), 첨부 사진(`Attachments`)이 있으면 사진문자(MMS)
- `SentReceiver` 발송 결과 코드 기록, 장문 실패 시 나눠 보내기, 사진문자 실패 시 글자만 재발송
- `LogDb` SQLite 발송 이력 / `Settings` 설정값 / `RuntimeState` 통화 감지 상태
- `MonitorService` 상단 상주 알림(specialUse 타입) / `BootReceiver` 재부팅 시 재시작
- 화면: `MainActivity`(상태), `SetupActivity`(권한·배터리 안내), `SettingsActivity`(preferences.xml), `LogActivity`

## 규칙
- 밤 21시~08시에는 설정과 무관하게 발송하지 않는다(정보통신망법 야간 광고 금지). 이 검사를 지우지 말 것.
- 하루 발송 상한은 최대 500(통신사 하루 한도). 기본 100.
- 수정할 때마다 빌드가 통과하는지 확인한다.
- Google Play 정책은 신경 쓰지 않는다(개인용).
