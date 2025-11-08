async function fetchJSON(url, opts) {
  const r = await fetch(url, opts);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}
function badge(state) {
  return `<span class="badge ${state}">${state}</span>`;
}
function time(ts) {
  if (!ts) return "";
  return new Date(ts * 1000).toLocaleString();
}
function qs(sel, root = document) {
  return root.querySelector(sel);
}
function qsa(sel, root = document) {
  return Array.from(root.querySelectorAll(sel));
}
