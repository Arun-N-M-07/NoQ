/*
 * Staff order dashboard. Loads orders from the REST API (the login session cookie authorises the calls),
 * re-renders every 10 seconds, and sends staff actions as PATCH/POST requests.
 */
(() => {
  const $ = sel => document.querySelector(sel);
  const board = $('[data-board]');
  const COLUMNS = [['PLACED', 'New'], ['CONFIRMED', 'Confirmed'], ['PREPARING', 'Preparing'], ['READY', 'Ready for pickup']];
  const STATUS_FILTERS = ['PLACED', 'CONFIRMED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED'];
  let filter = 'ACTIVE';
  let lastSeen = new Map(); // ref -> status, to highlight orders that changed

  const esc = NoQ.escapeHtml;

  function minutesAgo(iso) {
    const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
    return mins < 1 ? 'just now' : mins < 60 ? `${mins} min ago` : new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function needsCounterPayment(o) {
    return o.paymentMethod === 'PAY_AT_COUNTER' && o.paymentStatus === 'PENDING' && o.status !== 'CANCELLED' && o.status !== 'COMPLETED';
  }

  function actions(o) {
    const ref = esc(o.orderReference);
    const btn = (label, to, cls = '') => `<button class="btn btn-sm ${cls}" type="button" data-action="status" data-ref="${ref}" data-to="${to}">${label}</button>`;
    const parts = [];
    if (needsCounterPayment(o)) parts.push(`<button class="btn btn-sm btn-ok" type="button" data-action="paid" data-ref="${ref}">Mark paid ₹${esc(o.totalAmount)}</button>`);
    const waitingForOnlinePayment = o.paymentMethod === 'ONLINE' && o.paymentStatus !== 'PAID';
    if (o.status === 'PLACED' && waitingForOnlinePayment) parts.push('<span class="pill pill-warn">Waiting for online payment</span>');
    else if (o.status === 'PLACED') parts.push(btn('Confirm', 'CONFIRMED', 'btn-primary'));
    if (o.status === 'CONFIRMED') parts.push(btn('Start preparing', 'PREPARING', 'btn-primary'));
    if (o.status === 'PREPARING') parts.push(btn('Mark ready', 'READY', 'btn-primary'));
    if (o.status === 'PLACED' || o.status === 'CONFIRMED') parts.push(btn('Cancel', 'CANCELLED', 'btn-danger'));
    let html = `<div class="actions">${parts.join('')}</div>`;
    if (o.status === 'READY') {
      html += `<form class="otp-row" data-verify="${ref}">
        <input class="input" name="otp" inputmode="numeric" maxlength="6" pattern="\\d{6}" placeholder="OTP" required aria-label="Pickup OTP for ${ref}">
        <button class="btn btn-sm btn-ok" type="submit">Verify &amp; hand over</button></form>`;
    }
    return html;
  }

  function card(o) {
    const payKind = o.paymentStatus === 'PAID' ? 'pill-ok' : o.paymentStatus === 'PENDING' ? 'pill-warn' : 'pill-bad';
    const changed = lastSeen.has(o.orderReference) && lastSeen.get(o.orderReference) !== o.status;
    const isNew = lastSeen.size > 0 && !lastSeen.has(o.orderReference);
    return `<article class="card order-card ${changed || isNew ? 'flash' : ''}">
      <div class="top"><span class="ref">${esc(o.orderReference)}</span><span class="muted small">${minutesAgo(o.createdAt)}</span></div>
      <ul>${o.items.map(i => `<li><b>${i.quantity}×</b> ${esc(i.name)}</li>`).join('')}</ul>
      <div class="meta">
        <span class="pill">${o.paymentMethod === 'ONLINE' ? 'Online (demo)' : 'Counter'}</span>
        <span class="pill ${payKind}">${esc(o.paymentStatus)}</span>
        <span class="pill">₹${esc(o.totalAmount)}</span>
        ${filter !== 'ACTIVE' ? `<span class="pill pill-accent">${esc(o.status)}</span>` : ''}
      </div>
      ${actions(o)}
    </article>`;
  }

  function render(orders) {
    if (filter === 'ACTIVE') {
      board.className = 'board';
      board.innerHTML = COLUMNS.map(([status, title]) => {
        const list = orders.filter(o => o.status === status).reverse(); // oldest first = first to cook
        return `<section class="column"><h2>${title}<span>${list.length}</span></h2>
          <div class="cards">${list.map(card).join('') || '<p class="muted small" style="padding:0 6px">Nothing here</p>'}</div></section>`;
      }).join('');
    } else {
      const list = filter === 'AWAITING_PAYMENT' ? orders.filter(needsCounterPayment) : orders;
      board.className = 'board single';
      board.innerHTML = list.map(card).join('') || '<p class="empty">No orders.</p>';
    }
    lastSeen = new Map(orders.map(o => [o.orderReference, o.status]));
    $('[data-updated]').textContent = `Updated ${new Date().toLocaleTimeString()} · auto-refresh every 10 s`;
  }

  async function load() {
    // Don't wipe an OTP the staff member is typing
    if (board.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return;
    try {
      // A single status uses the HQL query "orders by status, newest first" on the server
      const query = STATUS_FILTERS.includes(filter) ? `?status=${filter}` : '';
      render(await NoQ.api('/api/orders' + query));
    } catch (err) {
      if (err.status === 401) location.href = '/staff/login';
      else $('[data-updated]').textContent = 'Could not refresh: ' + err.message;
    }
  }

  async function run(promise, okMessage) {
    try {
      await promise;
      NoQ.toast(okMessage);
    } catch (err) {
      NoQ.toast(err.message, true);
    }
    load();
  }

  board.addEventListener('click', e => {
    const b = e.target.closest('[data-action]');
    if (!b) return;
    const ref = b.dataset.ref;
    if (b.dataset.action === 'status') {
      if (b.dataset.to === 'CANCELLED' && !confirm(`Cancel ${ref}? Stock will be returned.`)) return;
      run(NoQ.api(`/api/orders/${encodeURIComponent(ref)}/status`, { method: 'PATCH', body: { status: b.dataset.to } }),
        `${ref} → ${b.dataset.to}`);
    } else if (b.dataset.action === 'paid') {
      run(NoQ.api(`/api/orders/${encodeURIComponent(ref)}/payments/counter-confirmation`, { method: 'POST' }),
        `${ref}: payment received`);
    }
  });

  function verify(ref, otp) {
    return run(NoQ.api(`/api/orders/${encodeURIComponent(ref)}/verify-pickup`, { method: 'POST', body: { otp } }),
      `${ref} handed over ✔`);
  }

  board.addEventListener('submit', e => {
    const form = e.target.closest('[data-verify]');
    if (!form) return;
    e.preventDefault();
    const otp = form.otp.value.trim();
    form.otp.blur();
    verify(form.dataset.verify, otp);
  });

  $('[data-pickup-form]').addEventListener('submit', e => {
    e.preventDefault();
    const f = e.target;
    verify(f.ref.value.trim().toUpperCase(), f.otp.value.trim()).then(() => f.reset());
  });

  document.querySelectorAll('[data-filter]').forEach(chip => chip.addEventListener('click', () => {
    filter = chip.dataset.filter;
    document.querySelectorAll('[data-filter]').forEach(c => c.setAttribute('aria-pressed', String(c === chip)));
    lastSeen = new Map();
    load();
  }));
  $('[data-refresh]').addEventListener('click', load);

  load();
  setInterval(load, 10000);
})();
