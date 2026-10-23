/*
 * Kitchen page: two tabs. "Incoming Orders" (PREPARING orders + "Ready for Pickup") is rendered by staff.js.
 * "Menu Availability" lists every dish from GET /api/menu with one Available / Not available switch,
 * saved with PATCH /api/menu/{id}/availability; the list is then re-read from the server.
 */
(() => {
  const esc = NoQ.escapeHtml;
  const tabs = document.querySelectorAll('[data-tab]');
  tabs.forEach(tab => tab.addEventListener('click', () => {
    tabs.forEach(t => t.setAttribute('aria-selected', String(t === tab)));
    document.querySelectorAll('[data-panel]').forEach(p => p.classList.toggle('hidden', p.dataset.panel !== tab.dataset.tab));
  }));

  const list = document.querySelector('[data-availability]');

  function row(item) {
    const soldOut = item.availableQuantity === 0 ? ' · Sold out' : ''; // status only, never the stock count
    return `<div class="avail-row">
      <div><b>${esc(item.name)}</b><div class="muted small">₹${esc(item.price)}${soldOut}</div></div>
      <span class="pill ${item.available ? 'pill-ok' : 'pill-bad'}">${item.available ? 'Available' : 'Not available'}</span>
      <button class="btn btn-sm" type="button" data-id="${item.id}" data-available="${!item.available}">
        ${item.available ? 'Mark not available' : 'Mark available'}</button>
    </div>`;
  }

  async function load() {
    try {
      const items = await NoQ.api('/api/menu');
      list.innerHTML = items.map(row).join('') || '<p class="empty">No dishes on the menu.</p>';
    } catch (err) {
      list.innerHTML = `<p class="empty">Could not load the menu: ${esc(err.message)}</p>`;
    }
  }

  list.addEventListener('click', async e => {
    const button = e.target.closest('[data-id]');
    if (!button) return;
    button.disabled = true;
    try {
      const item = await NoQ.api(`/api/menu/${button.dataset.id}/availability`,
        { method: 'PATCH', body: { available: button.dataset.available === 'true' } });
      NoQ.toast(`${item.name}: ${item.available ? 'available' : 'not available'}`);
    } catch (err) {
      NoQ.toast(err.message, true);
    }
    load();
  });

  load();
})();
