/*
 * Order tracking with simple HTTP polling (no WebSockets):
 * every 5 seconds we GET /api/orders/{ref}/status, a small public JSON object.
 * Full details (items) need the pickup OTP, sent in the X-Pickup-Otp header.
 */
(() => {
  const STEPS = ['PLACED', 'CONFIRMED', 'PREPARING', 'READY', 'COMPLETED'];
  const $ = sel => document.querySelector(sel);
  const params = new URLSearchParams(location.search);
  let ref = (params.get('ref') || '').trim().toUpperCase();
  let otp = ref ? NoQ.orders.otpFor(ref) : null;
  let lastStatus = null;
  let timer = null;

  const form = $('[data-lookup-form]');
  if (ref) form.ref.value = ref;

  function pill(text, kind) { return `<span class="pill ${kind}">${NoQ.escapeHtml(text)}</span>`; }

  function renderStatus(s) {
    $('[data-ref]').textContent = s.orderReference;
    const payKind = s.paymentStatus === 'PAID' ? 'pill-ok' : s.paymentStatus === 'FAILED' ? 'pill-bad' : 'pill-warn';
    const method = s.paymentMethod === 'ONLINE' ? 'Online (demo)' : 'Pay at counter';
    $('[data-pills]').innerHTML = pill(s.status, s.status === 'CANCELLED' ? 'pill-bad' : 'pill-accent')
      + pill(`${method} · ${s.paymentStatus}`, payKind);

    const index = STEPS.indexOf(s.status);
    document.querySelectorAll('[data-step]').forEach((li, i) => {
      li.classList.toggle('done', index >= 0 && i < index);
      li.classList.toggle('current', i === index);
    });
    $('[data-progress]').classList.toggle('hidden', s.status === 'CANCELLED');
    $('[data-cancelled]').classList.toggle('hidden', s.status !== 'CANCELLED');
    if (s.status !== 'PLACED') $('[data-new-order]').classList.add('hidden');
    $('[data-ready]').classList.toggle('hidden', s.status !== 'READY');
    $('[data-ready-pay]').textContent = s.paymentStatus !== 'PAID' ? `Please pay ${NoQ.money(s.totalAmount)} at the counter.` : '';
    const needsOnlinePay = s.paymentMethod === 'ONLINE' && s.paymentStatus !== 'PAID' && s.status === 'PLACED';
    $('[data-pay-needed]').classList.toggle('hidden', !needsOnlinePay);
    $('[data-pay-link]').href = `/pay/${encodeURIComponent(s.orderReference)}`;
    $('[data-cancel]').classList.toggle('hidden', !otp || s.status !== 'PLACED');
    if (s.status === 'COMPLETED' || s.status === 'CANCELLED') {
      clearInterval(timer);
      $('[data-poll-note]').innerHTML = s.status === 'COMPLETED' ? 'Collected. Enjoy your meal! 😋' : 'Order closed.';
    }
  }

  async function loadDetails() {
    if (!otp) return;
    try {
      const order = await NoQ.api(`/api/orders/${encodeURIComponent(ref)}`, { headers: { 'X-Pickup-Otp': otp } });
      $('[data-items]').innerHTML = order.items.map(i =>
        `<li><span>${i.quantity} × ${NoQ.escapeHtml(i.name)}</span><span>${NoQ.money(i.lineTotal)}</span></li>`).join('');
      $('[data-total]').textContent = NoQ.money(order.totalAmount);
      $('[data-details]').classList.remove('hidden');
      $('[data-otp]').textContent = otp.replace(/(\d{3})(\d{3})/, '$1 $2');
      $('[data-otp-card]').classList.toggle('hidden', order.status === 'COMPLETED' || order.status === 'CANCELLED');
    } catch (err) {
      NoQ.toast(err.message, true);
    }
  }

  async function poll() {
    try {
      const s = await NoQ.api(`/api/orders/${encodeURIComponent(ref)}/status`);
      if (s.status !== lastStatus || !lastStatus) {
        if (lastStatus && s.status === 'READY') NoQ.toast('Your order is ready! 🎉');
        lastStatus = s.status;
        renderStatus(s);
        loadDetails();
      } else {
        renderStatus(s);
      }
    } catch (err) {
      if (err.status === 404) { clearInterval(timer); showLookup('We could not find that order reference.'); }
    }
  }

  function start() {
    form.classList.add('hidden');
    $('[data-order-view]').classList.remove('hidden');
    $('[data-new-order]').classList.toggle('hidden', params.get('new') !== '1');
    poll();
    timer = setInterval(poll, 5000);
  }

  function showLookup(message) {
    $('[data-order-view]').classList.add('hidden');
    form.classList.remove('hidden');
    if (message) { $('[data-lookup-error]').textContent = message; $('[data-lookup-error]').classList.remove('hidden'); }
  }

  form.addEventListener('submit', async e => {
    e.preventDefault();
    const tryRef = form.ref.value.trim().toUpperCase();
    const tryOtp = form.otp.value.trim();
    try {
      await NoQ.api(`/api/orders/${encodeURIComponent(tryRef)}`, { headers: { 'X-Pickup-Otp': tryOtp } });
      NoQ.orders.remember(tryRef, tryOtp);
      history.replaceState(null, '', `/track?ref=${encodeURIComponent(tryRef)}`);
      ref = tryRef; otp = tryOtp; lastStatus = null;
      clearInterval(timer);
      start();
    } catch (err) {
      showLookup(err.message);
    }
  });

  $('[data-cancel]').addEventListener('click', async () => {
    if (!confirm('Cancel this order?')) return;
    try {
      await NoQ.api(`/api/orders/${encodeURIComponent(ref)}/cancel`, { method: 'POST', headers: { 'X-Pickup-Otp': otp } });
      NoQ.toast('Order cancelled');
      poll();
    } catch (err) { NoQ.toast(err.message, true); }
  });

  if (ref && otp) start();
  else if (ref) { start(); }   // status is public; details appear after entering the code below
  else showLookup();
  if (ref && !otp) {
    form.classList.remove('hidden');
    $('[data-lookup-hint]').textContent = 'Enter your pickup code to see your items and code.';
  }
})();
