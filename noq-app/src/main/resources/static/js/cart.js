/* Cart + checkout. Shows the cart, refreshes prices/stock from the server, POSTs the order, then shows the confirmation. */
(() => {
  const linesEl = document.querySelector('[data-cart-lines]');
  const errorEl = document.querySelector('[data-error]');
  const placeBtn = document.querySelector('[data-place-order]');
  let live = {}; // latest menu data from /api/menu, by id

  function render() {
    const lines = Object.values(NoQ.cart.all());
    document.querySelector('[data-cart-empty]').classList.toggle('hidden', lines.length > 0);
    document.querySelector('[data-checkout]').classList.toggle('hidden', lines.length === 0);
    let problems = 0;

    linesEl.innerHTML = lines.map(line => {
      const item = live[line.id];
      let problem = '';
      if (Object.keys(live).length && (!item || !item.orderable)) problem = 'No longer available - please remove';
      else if (item && line.qty > item.availableQuantity) problem = `Only ${item.availableQuantity} left`;
      if (problem) problems++;
      return `<div class="cart-line ${problem ? 'problem' : ''}">
          <div class="thumb">${NoQ.thumb(line.image, line.emoji, line.name)}</div>
          <div><h3>${NoQ.escapeHtml(line.name)}</h3>
            <span class="muted small">${NoQ.money(line.price)} each</span>
            ${problem ? `<div class="field-error">${problem}</div>` : ''}</div>
          <div class="controls">
            <div class="stepper">
              <button type="button" data-qty="${line.id}" data-delta="-1" aria-label="Remove one ${NoQ.escapeHtml(line.name)}">−</button>
              <span>${line.qty}</span>
              <button type="button" data-qty="${line.id}" data-delta="1" aria-label="Add one ${NoQ.escapeHtml(line.name)}" ${problem ? 'disabled' : ''}>+</button>
            </div>
            <span class="line-total">${NoQ.money(line.qty * line.price)}</span>
            <button class="btn btn-sm btn-danger" type="button" data-remove="${line.id}">Remove</button>
          </div></div>`;
    }).join('');

    document.querySelector('[data-total]').textContent = NoQ.money(NoQ.cart.total());
    placeBtn.disabled = lines.length === 0 || problems > 0;
  }

  /** Update the stored cart with the CURRENT price and stock from the server. */
  async function refreshFromServer() {
    try {
      const menu = await NoQ.api('/api/menu');
      live = Object.fromEntries(menu.map(i => [String(i.id), i]));
      const items = NoQ.cart.all();
      Object.values(items).forEach(line => {
        const item = live[line.id];
        if (item) Object.assign(line, { name: item.name, price: item.price, max: item.availableQuantity, image: item.imageUrl, emoji: item.categoryEmoji });
      });
      NoQ.cart.save(items);
    } catch { /* offline: show cart as stored; the server will validate anyway */ }
    render();
  }

  document.addEventListener('click', e => {
    const qtyBtn = e.target.closest('[data-qty]');
    const removeBtn = e.target.closest('[data-remove]');
    if (qtyBtn) {
      const id = qtyBtn.dataset.qty;
      NoQ.cart.setQty(id, NoQ.cart.qty(id) + Number(qtyBtn.dataset.delta));
      render();
    } else if (removeBtn) {
      NoQ.cart.remove(removeBtn.dataset.remove);
      render();
    }
  });

  placeBtn.addEventListener('click', async () => {
    errorEl.classList.add('hidden');
    placeBtn.disabled = true;
    placeBtn.textContent = 'Placing order…';
    // Same key for retries of THIS checkout, so a double click or network retry can't create two orders.
    let key = sessionStorage.getItem('noq.checkoutKey');
    if (!key) { key = NoQ.randomKey(); sessionStorage.setItem('noq.checkoutKey', key); }
    try {
      const order = await NoQ.api('/api/orders', {
        method: 'POST',
        body: {
          items: Object.values(NoQ.cart.all()).map(l => ({ menuItemId: Number(l.id), quantity: l.qty })),
          idempotencyKey: key
        }
      });
      NoQ.lastOrder.save(order);
      NoQ.cart.clear();
      sessionStorage.removeItem('noq.checkoutKey');
      showConfirmation(order, 'Order confirmed');
      watch(order);
    } catch (err) {
      if (err.status !== 409 || !err.message.includes('already submitted')) sessionStorage.removeItem('noq.checkoutKey');
      errorEl.textContent = err.message;
      errorEl.classList.remove('hidden');
      placeBtn.textContent = 'Place order';
      refreshFromServer(); // show which line caused the problem
    }
  });

  // ---------- Order confirmation (everything shown comes from the server) ----------
  const confirmation = document.querySelector('[data-confirmation]');
  const PILL = { PREPARING: 'pill-warn', READY_FOR_PICKUP: 'pill-ok', COLLECTED: 'pill-ok', CANCELLED: 'pill-bad' };
  const FINAL = ['COLLECTED', 'CANCELLED'];

  function message(order) {
    if (order.status === 'COLLECTED') return 'Collected — enjoy your meal!';
    if (order.status === 'CANCELLED') return 'This order was cancelled. Any reserved items were released.';
    if (order.status === 'READY_FOR_PICKUP') return 'Your order is ready! Show your pickup code at the counter.';
    return order.message;
  }

  function showConfirmation(order, title) {
    const esc = NoQ.escapeHtml;
    if (title) document.querySelector('[data-page-title]').textContent = title;
    document.querySelector('[data-cart-empty]').classList.add('hidden');
    document.querySelector('[data-checkout]').classList.add('hidden');
    confirmation.classList.remove('hidden');
    const alert = confirmation.querySelector('[data-confirm-message]');
    alert.textContent = message(order);
    alert.className = 'alert ' + (order.status === 'CANCELLED' ? 'alert-bad' : 'alert-ok');
    confirmation.querySelector('[data-otp]').textContent = order.pickupOtp;
    confirmation.querySelector('[data-ref]').textContent = order.orderReference;
    const statusPill = confirmation.querySelector('[data-order-status]');
    statusPill.textContent = `Status: ${order.status.replaceAll('_', ' ')}`; // PREPARING, READY FOR PICKUP, COLLECTED
    statusPill.className = 'pill ' + PILL[order.status];
    const pill = confirmation.querySelector('[data-pay-status]');
    pill.textContent = `Payment: ${order.paymentStatus}`;
    pill.className = 'pill ' + (order.paymentStatus === 'PAID' ? 'pill-ok' : 'pill-warn');
    confirmation.querySelector('[data-items]').innerHTML = order.items.map(i =>
      `<li><span>${i.quantity} × ${esc(i.name)}</span><span>${NoQ.money(i.lineTotal)}</span></li>`).join('');
    confirmation.querySelector('[data-order-total]').textContent = NoQ.money(order.totalAmount);
  }

  /** While the confirmation is open, re-read the order's saved status every 5 s (the OTP header proves it is ours). */
  let poll;
  function watch(order) {
    if (!FINAL.includes(order.status)) poll = setInterval(refreshStatus, 5000);
  }

  async function refreshStatus() {
    const order = NoQ.lastOrder.get();
    try {
      const latest = await NoQ.api(`/api/orders/${encodeURIComponent(order.orderReference)}`,
        { headers: { 'X-Pickup-Otp': order.pickupOtp } });
      Object.assign(order, { status: latest.status, paymentStatus: latest.paymentStatus });
      NoQ.lastOrder.save(order);
      showConfirmation(order);
      if (FINAL.includes(order.status)) clearInterval(poll);
    } catch (err) {
      if (err.status) clearInterval(poll); // the server said no (e.g. order unknown): stop; a network blip just retries
    }
  }

  // An empty cart shows the last order's confirmation (so a refresh never loses the pickup code).
  const last = NoQ.lastOrder.get();
  if (last && NoQ.cart.count() === 0) {
    showConfirmation(last, 'Your last order');
    watch(last);
    refreshStatus(); // show the current status straight away, not the one saved at checkout
  } else {
    render();
    refreshFromServer();
  }
})();
