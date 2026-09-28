'use strict';
// The CSRF token lives in the XSRF-TOKEN cookie (SPA mode); a plain form post can't carry it, so the form posts via fetch.
const form = document.getElementById('login-form');
const error = document.getElementById('login-error');
if (new URLSearchParams(location.search).has('error')) error.hidden = false;

function csrf() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : '';
}

form.addEventListener('submit', async ev => {
  ev.preventDefault();
  error.hidden = true;
  if (!csrf()) await fetch('/login.html', { credentials: 'same-origin' });
  const res = await fetch('/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': csrf() },
    body: new URLSearchParams(new FormData(form)),
  });
  if (res.redirected && !res.url.includes('error')) location.href = '/';
  else error.hidden = false;
});
