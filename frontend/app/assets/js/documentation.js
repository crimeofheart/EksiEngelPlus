// Applies the saved theme. A file of its own because extension pages refuse
// inline scripts, so the copy that sat in documentation.html never ran.
function applyTheme() {
  const savedTheme = localStorage.getItem('theme') || 'light';
  document.documentElement.setAttribute('data-theme', savedTheme);
}
applyTheme();
