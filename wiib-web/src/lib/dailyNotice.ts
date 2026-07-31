const HIDE_DATE_KEY = 'wiib-notice-hide-date';
const SEEN_SESSION_KEY = 'wiib-notice-seen-session';

function todayKey(): string {
  return new Date().toDateString();
}

/** 每日隐藏跨浏览器重启保留；“我知道了”只在当前标签页会话内生效。 */
export function shouldShowDailyNotice(): boolean {
  return sessionStorage.getItem(SEEN_SESSION_KEY) !== '1'
    && localStorage.getItem(HIDE_DATE_KEY) !== todayKey();
}

export function acknowledgeDailyNotice(): void {
  sessionStorage.setItem(SEEN_SESSION_KEY, '1');
}

export function hideDailyNoticeToday(): void {
  localStorage.setItem(HIDE_DATE_KEY, todayKey());
  acknowledgeDailyNotice();
}
