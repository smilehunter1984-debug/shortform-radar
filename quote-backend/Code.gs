/**
 * 콤마인테리어필름 견적 요청 받기 (구글 Apps Script)
 *
 * 하는 일
 *  1) 고객 사진을 구글 드라이브 '콤마 견적요청' 폴더에 고객별로 저장
 *  2) 구글 시트 '콤마 견적요청 명단'에 한 줄씩 기록
 *  3) 사장님 Gmail로 알림 메일(사진 첨부) 보내기
 *
 * 설치 방법은 같은 폴더의 README.md 를 보세요.
 */

// 알림 받을 메일 주소. 비워 두면 이 스크립트를 만든 구글 계정으로 갑니다.
const OWNER_EMAIL = '';
const FOLDER_NAME = '콤마 견적요청';
const SHEET_TITLE = '콤마 견적요청 명단';
const MAX_PHOTOS = 10;

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.website) return json_({ ok: true }); // 스팸 봇
    if (!d.name || !d.phone || !d.area) return json_({ ok: false, error: '필수 항목 누락' });

    const now = new Date();
    const stamp = Utilities.formatDate(now, 'Asia/Seoul', 'yyyy-MM-dd HH:mm');

    // 1) 사진 저장
    const photos = (d.photos || []).slice(0, MAX_PHOTOS);
    let folderUrl = '';
    const blobs = [];
    if (photos.length) {
      const root = getOrCreateFolder_(FOLDER_NAME);
      const folder = root.createFolder(stamp + ' ' + d.name + ' ' + d.area);
      photos.forEach(function (b64, i) {
        const blob = Utilities.newBlob(Utilities.base64Decode(b64), 'image/jpeg', '사진' + (i + 1) + '.jpg');
        folder.createFile(blob);
        blobs.push(blob);
      });
      folderUrl = folder.getUrl();
    }

    // 2) 시트에 기록
    const sheet = getSheet_();
    sheet.appendRow([
      stamp, '신규', d.name, "'" + d.phone, d.area,
      (d.parts || []).join(', '), d.partsMore || '',
      d.houseType || '', d.size || '', d.living || '',
      d.wishDate || '', d.callTime || '', d.memo || '',
      photos.length, folderUrl,
    ]);

    // 3) 알림 메일
    const to = OWNER_EMAIL || Session.getEffectiveUser().getEmail();
    const rows = [
      ['성함', d.name], ['연락처', d.phone], ['장소', d.area],
      ['시공 부위', (d.parts || []).join(', ')], ['수량·치수', d.partsMore],
      ['주거 형태', d.houseType], ['평수', d.size], ['현재 상태', d.living],
      ['희망 시공일', d.wishDate], ['통화 편한 시간', d.callTime], ['요청사항', d.memo],
    ].filter(function (r) { return r[1]; });
    const tel = String(d.phone).replace(/\D/g, '');
    let html = '<h2 style="margin:0 0 8px">새 견적 요청</h2><p style="color:#666">' + stamp + '</p>' +
      '<table cellpadding="6" style="border-collapse:collapse">' +
      rows.map(function (r) {
        return '<tr><td style="color:#666;white-space:nowrap">' + esc_(r[0]) + '</td><td><b>' + esc_(r[1]) + '</b></td></tr>';
      }).join('') + '</table>' +
      '<p><a href="tel:' + tel + '">📞 전화하기</a> &nbsp; <a href="sms:' + tel + '">💬 문자하기</a></p>';
    if (folderUrl) html += '<p>사진 ' + photos.length + '장: <a href="' + folderUrl + '">드라이브 폴더 열기</a> (메일에도 첨부)</p>';
    html += '<p><a href="' + SpreadsheetApp.openById(sheetId_()).getUrl() + '">견적요청 명단(시트) 열기</a></p>';

    MailApp.sendEmail({
      to: to,
      subject: '[견적요청] ' + d.name + ' · ' + d.area + (photos.length ? ' · 사진 ' + photos.length + '장' : ''),
      htmlBody: html,
      attachments: blobs,
      name: '콤마 견적 알림',
    });

    return json_({ ok: true });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

// 주소를 브라우저로 열었을 때 작동 확인용
function doGet() {
  return json_({ ok: true, message: '콤마 견적 접수 서버가 작동 중입니다.' });
}

// 설치 직후 한 번 실행해서 권한을 허용하고, 시트·폴더를 미리 만든다
function setup() {
  getOrCreateFolder_(FOLDER_NAME);
  const url = SpreadsheetApp.openById(sheetId_()).getUrl();
  Logger.log('준비 완료. 명단 시트: ' + url);
}

function getSheet_() {
  return SpreadsheetApp.openById(sheetId_()).getSheets()[0];
}

function sheetId_() {
  const props = PropertiesService.getScriptProperties();
  let id = props.getProperty('SHEET_ID');
  if (id) {
    try { SpreadsheetApp.openById(id); return id; } catch (e) { /* 지워졌으면 새로 만듦 */ }
  }
  const ss = SpreadsheetApp.create(SHEET_TITLE);
  const sh = ss.getSheets()[0];
  sh.appendRow(['접수 시각', '상태', '성함', '연락처', '장소', '시공 부위', '수량·치수',
    '주거 형태', '평수', '현재 상태', '희망일', '통화 시간', '요청사항', '사진 수', '사진 폴더']);
  sh.setFrozenRows(1);
  sh.getRange(1, 1, 1, 15).setFontWeight('bold').setBackground('#E7F1EE');
  id = ss.getId();
  props.setProperty('SHEET_ID', id);
  return id;
}

function getOrCreateFolder_(name) {
  const it = DriveApp.getFoldersByName(name);
  return it.hasNext() ? it.next() : DriveApp.createFolder(name);
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function esc_(s) {
  return String(s).replace(/[&<>"]/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
  });
}
