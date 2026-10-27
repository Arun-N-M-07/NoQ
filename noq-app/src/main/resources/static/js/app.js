/*
 * Shared helpers for every customer page.
 * The cart lives in localStorage so it survives page reloads. It is only a convenience:
 * the server re-checks every item, price and availability when the order is placed.
 */
const NoQ = (() => {
  const CART_KEY = 'noq.cart';            // { "3": {id, name, price, qty, image, emoji, max} }
  const LAST_ORDER_KEY = 'noq.lastOrder'; // the confirmation (incl. pickup OTP) of the last order - only on THIS device

  function read(key, fallback) {
    try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
  }
  function write(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* private mode: cart just won't persist */ }
  }

  const cart = {
    all: () => read(CART_KEY, {}),
    save(items) { write(CART_KEY, items); updateCartBadges(); },
    qty(id) { return this.all()[id]?.qty ?? 0; },
    add(item) {
      const items = this.all();
      const line = items[item.id] ?? { ...item, qty: 0 };
      if (line.qty >= Math.min(item.max ?? 20, 20)) { toast(`Only ${Math.min(item.max ?? 20, 20)} × ${item.name} allowed`, true); return; }
      line.qty += 1;
      items[item.id] = { ...line, price: item.price };
      this.save(items);
    },
    setQty(id, qty) {
      const items = this.all();
      if (!items[id]) return;
      if (qty <= 0) delete items[id]; else items[id].qty = Math.min(qty, Math.min(items[id].max ?? 20, 20));
      this.save(items);
    },
    remove(id) { const items = this.all(); delete items[id]; this.save(items); },
    clear() { this.save({}); },
    count() { return Object.values(this.all()).reduce((n, l) => n + l.qty, 0); },
    total() { return Object.values(this.all()).reduce((sum, l) => sum + l.qty * Number(l.price), 0); }
  };

  // Kept so a page refresh does not lose the pickup code (the server stores only its hash and can't show it again).
  const lastOrder = {
    get: () => read(LAST_ORDER_KEY, null),
    save: order => write(LAST_ORDER_KEY, order)
  };

  function money(value) {
    return '₹' + Number(value).toFixed(2).replace(/\.00$/, '');
  }

  function toast(message, bad = false) {
    document.querySelector('.toast')?.remove();
    const el = document.createElement('div');
    el.className = 'toast' + (bad ? ' bad' : '');
    el.setAttribute('role', 'status');
    el.textContent = message;
    document.body.append(el);
    setTimeout(() => el.remove(), 3200);
  }

  /** fetch() wrapper: sends/receives JSON and turns API errors into Error(message). */
  async function api(url, { method = 'GET', body, headers = {} } = {}) {
    const res = await fetch(url, {
      method,
      headers: { 'Accept': 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
      body: body ? JSON.stringify(body) : undefined,
      credentials: 'same-origin'
    });
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) {
      const fields = data?.fieldErrors ? ' ' + Object.values(data.fieldErrors).join(', ') : '';
      const err = new Error((data?.message ?? `Request failed (${res.status})`) + fields);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  /** Random id that also works on plain-http LAN addresses (crypto.randomUUID needs https). */
  function randomKey() {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  }

  function escapeHtml(text) {
    return String(text ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  /** Picture with emoji fallback (used when an item has no image or the image fails to load). */
  function thumb(image, emoji, alt) {
    return image
      ? `<img src="${escapeHtml(image)}" alt="${escapeHtml(alt)}" loading="lazy" data-fallback="${escapeHtml(emoji)}">`
      : `<div class="img-fallback">${escapeHtml(emoji)}</div>`;
  }

  function updateCartBadges() {
    const count = cart.count();
    document.querySelectorAll('[data-cart-count]').forEach(el => { el.textContent = count; });
    const bar = document.querySelector('[data-cart-bar]');
    if (bar) {
      bar.classList.toggle('hidden', count === 0);
      bar.querySelector('[data-cart-bar-count]').textContent = `${count} item${count === 1 ? '' : 's'}`;
      bar.querySelector('[data-cart-bar-total]').textContent = money(cart.total());
    }
  }

  // If an external food photo fails to load, swap in a friendly emoji tile instead of a broken image.
  function useFallback(img) {
    if (img.tagName !== 'IMG' || !img.dataset.fallback) return;
    const div = document.createElement('div');
    div.className = 'img-fallback';
    div.textContent = img.dataset.fallback;
    img.replaceWith(div);
  }
  document.addEventListener('error', e => useFallback(e.target), true);
  // Images that already failed before this script ran never fire the listener above, so sweep once at load.
  window.addEventListener('load', () => {
    document.querySelectorAll('img[data-fallback]').forEach(img => { if (img.complete && img.naturalWidth === 0) useFallback(img); });
  });

  document.addEventListener('DOMContentLoaded', updateCartBadges);

  return { cart, lastOrder, money, toast, api, randomKey, escapeHtml, thumb };
})();
