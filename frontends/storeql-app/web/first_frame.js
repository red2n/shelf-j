// Removes the loading placeholder once Flutter paints its first frame. A file rather than an inline
// script, so the Content-Security-Policy can refuse every inline script (12.11).
window.addEventListener('flutter-first-frame', () => {
  const el = document.getElementById('loading');
  if (el) el.remove();
});
