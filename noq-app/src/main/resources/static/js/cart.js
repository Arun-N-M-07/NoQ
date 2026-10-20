/* Cart + checkout. Shows the cart, refreshes prices/stock from the server, then POSTs the order. */
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
              <button type="button" data-qty="${line.id}" data-delta="1" aria-label="Add one ${NoQ.escapeHtml(line.name)}">+</button>
            </div>
            <span class="line-total">${NoQ.money(line.qty * line.price)}</span>
            <button class="btn btn-sm btn-danger" type="button" data-remove="${line.id}">Remove</button>
          </div></div>`;
    }).join('');

    const total = NoQ.cart.total();
    document.querySelector('[data-subtotal]').textContent = NoQ.money(total);
    document.querySelector('[data-total]').textContent = NoQ.money(total);
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
    const paymentMethod = document.querySelector('input[name=payment]:checked').value;
    try {
      const order = await NoQ.api('/api/orders', {
        method: 'POST',
        body: {
          items: Object.values(NoQ.cart.all()).map(l => ({ menuItemId: Number(l.id), quantity: l.qty })),
          paymentMethod,
          idempotencyKey: key
        }
      });
      NoQ.orders.remember(order.orderReference, order.pickupOtp);
      NoQ.cart.clear();
      sessionStorage.removeItem('noq.checkoutKey');
      const ref = encodeURIComponent(order.orderReference);
      location.href = paymentMethod === 'ONLINE' ? `/pay/${ref}` : `/track?ref=${ref}&new=1`;
    } catch (err) {
      if (err.status !== 409 || !err.message.includes('already submitted')) sessionStorage.removeItem('noq.checkoutKey');
      errorEl.textContent = err.message;
      errorEl.classList.remove('hidden');
      placeBtn.textContent = 'Place order';
      refreshFromServer(); // show which line caused the problem
    }
  });

  render();
  refreshFromServer();
})();
