/*
 * Order list used by two pages (one column, oldest first, refreshed every 10 s from the REST API):
 *   - Kitchen page (data-mode="kitchen"): orders still PREPARING, each with one "Ready for Pickup" button.
 *   - Staff page   (data-mode="staff"):   orders READY_FOR_PICKUP; verify the OTP (then the counter payment is
 *                                         recorded), then confirm the handover (COLLECTED).
 * The server checks every rule again - the buttons only offer the next step.
 */
(() => {
  const list = document.querySelector('[data-orders]');
  const kitchen = list.dataset.mode === 'kitchen';
  const verified = new Map(); // ref -> OTP that the server accepted (step 1), sent again on "Confirm handover"
  const esc = NoQ.escapeHtml;

  function timeOf(iso) {
    return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function action(o) {
    const ref = esc(o.orderReference);
    const btn = (label, attr, cls = 'btn-primary') =>
      `<button class="btn btn-sm ${cls}" type="button" ${attr} data-ref="${ref}">${label}</button>`;
    if (kitchen) return btn('Ready for Pickup', 'data-ready');
    if (verified.has(o.orderReference)) {
      return `<span class="pill pill-ok">OTP verified. Food can be delivered.</span>${btn('Confirm handover', 'data-collect', 'btn-ok')}`;
    }
    // The form carries its own order reference, so an OTP can only ever be checked against this order.
    return `<form class="otp-row" data-verify="${ref}">
        <label for="otp-${ref}">Enter pickup OTP</label>
        <input id="otp-${ref}" class="input" name="otp" inputmode="numeric" maxlength="6" pattern="\\d{6}" required autocomplete="off">
        <button class="btn btn-sm btn-ok" type="submit">Verify OTP</button></form>`;
  }

  function card(o) {
    return `<article class="card order-card">
      <div class="top"><span class="ref">${esc(o.orderReference)}</span><span class="muted small">${timeOf(o.createdAt)}</span></div>
      <ul>${o.items.map(i => `<li><b>${i.quantity}×</b> ${esc(i.name)}</li>`).join('')}</ul>
      <div class="meta">
        <span class="pill">₹${esc(o.totalAmount)}</span>
        <span class="pill ${o.paymentStatus === 'PAID' ? 'pill-ok' : 'pill-warn'}">Payment: ${esc(o.paymentStatus)}</span>
      </div>
      <div class="actions">${action(o)}</div>
    </article>`;
  }

  async function load() {
    // Don't wipe an OTP the staff member is typing
    if (list.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return;
    try {
      const orders = await NoQ.api(`/api/orders?status=${kitchen ? 'PREPARING' : 'READY_FOR_PICKUP'}`); // newest first
      list.innerHTML = orders.reverse().map(card).join('')
        || `<p class="empty card">${kitchen ? 'No orders to prepare.' : 'No orders ready for pickup.'}</p>`;
      document.querySelector('[data-updated]').textContent = `Updated ${new Date().toLocaleTimeString()} · refreshes every 10 s`;
    } catch (err) {
      if (err.status === 401) location.href = '/staff/login';
      else document.querySelector('[data-updated]').textContent = 'Could not refresh: ' + err.message;
    }
  }

  /** Runs one action (button disabled meanwhile, so a double click sends it once), then reloads the list. */
  async function run(button, url, body, okMessage) {
    button.disabled = true;
    try {
      await NoQ.api(url, { method: body.status ? 'PATCH' : 'POST', body });
      NoQ.toast(okMessage);
      return true;
    } catch (err) {
      NoQ.toast(err.message, true);
      return false;
    } finally {
      load();
    }
  }

  list.addEventListener('click', e => {
    const b = e.target.closest('[data-ready], [data-collect]');
    if (!b) return;
    const ref = b.dataset.ref;
    const url = `/api/orders/${encodeURIComponent(ref)}`;
    if (b.dataset.ready !== undefined) run(b, `${url}/status`, { status: 'READY_FOR_PICKUP' }, `${ref} is ready for pickup`);
    else run(b, `${url}/collect`, { otp: verified.get(ref) }, `${ref} collected ✔`).then(ok => { if (ok) verified.delete(ref); });
  });

  list.addEventListener('submit', async e => {
    const form = e.target.closest('[data-verify]');
    if (!form) return;
    e.preventDefault();
    const ref = form.dataset.verify;
    const url = `/api/orders/${encodeURIComponent(ref)}`;
    const otp = form.otp.value.trim();
    form.otp.blur();
    form.querySelector('button').disabled = true;
    // Step 1: the server checks the OTP (changes nothing); only a correct OTP goes on to record the counter payment.
    // Step 2 is "Confirm handover", which the server refuses unless the order is PAID.
    try {
      const order = await NoQ.api(`${url}/verify-pickup`, { method: 'POST', body: { otp } });
      if (order.paymentStatus !== 'PAID') await NoQ.api(`${url}/payments/counter-confirmation`, { method: 'POST', body: {} });
      verified.set(ref, otp);
      NoQ.toast('OTP verified. Food can be delivered.');
    } catch (err) {
      NoQ.toast(err.message, true);
    }
    load();
  });

  load();
  setInterval(load, 10000);
})();
