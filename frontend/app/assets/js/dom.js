/*
 * Building page content without innerHTML.
 *
 * addons.mozilla.org flags every innerHTML assignment whose value it cannot
 * prove constant, and several of ours carried text scraped from Ekşi Sözlük --
 * nicks, titles, error messages -- which as markup is an injection waiting for
 * the first nick with a tag in it. So text goes in as text, and markup is built
 * as nodes.
 */

/**
 * An element with properties and children: h("a", {className: "x", href}, "label").
 *
 * className and style objects are set as properties, on* functions become
 * listeners, everything else is an attribute. Children may be nodes, strings
 * (inserted as text, never parsed), arrays of either, or null/false to skip.
 */
export function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === "className") node.className = value;
    else if (key === "style" && typeof value === "object") Object.assign(node.style, value);
    else if (key.startsWith("on") && typeof value === "function") node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  return appendChildren(node, children);
}

function appendChildren(node, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** Replaces an element's content with the given children, text never parsed as markup. */
export function setContent(node, ...children) {
  if (!node) return node;
  node.replaceChildren();
  return appendChildren(node, children);
}

/** The icon-and-text label every action button here carries. */
export function buttonLabel(icon, text) {
  return [h("span", { className: "btn-icon" }, icon), h("span", { className: "btn-text" }, text)];
}
