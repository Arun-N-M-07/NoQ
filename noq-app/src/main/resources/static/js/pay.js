/* Demo payment page: tells the server to SIMULATE a successful or failed payment. */
(() => {
  const ref = document.querySelector('[data-ref]').dataset.ref;
  const actions = document.querySelector('[data-pay-actions]');
  if (!actions) return;
  const result = document.querySelector('[data-result]');
  const otpField = document.querySelector('[data-otp-field]');
  const storedOtp = NoQ.orders.otpFor(ref);
  if (!storedOtp) otpField.classList.remove('hidden'); // opened on another device: ask for the code

  actions.addEventListener('click', async e => {
    const btn = e.target.closest('[data-simulate]');
    if (!btn) return;
    const otp = storedOtp ?? document.getElementById('otp').value.trim();
    actions.querySelectorAll('button').forEach(b => { b.disabled = true; });
    try {
      const payment = await NoQ.api(`/api/orders/${encodeURIComponent(ref)}/payments/mock-online`, {
        method: 'POST', headers: { 'X-Pickup-Otp': otp }, body: { success: btn.dataset.simulate === 'true' }
      });
      const ok = payment.paymentStatus === 'PAID';
      document.querySelector('[data-pay-status]').textContent = `Payment: ${payment.paymentStatus}`;
      result.className = 'alert ' + (ok ? 'alert-ok' : 'alert-bad');
      result.textContent = ok
        ? `${payment.message}. Reference ${payment.transactionReference}. Taking you to your order…`
        : `${payment.message}. You can try again or cancel the order from the tracking page.`;
      if (ok) {
        if (!storedOtp) NoQ.orders.remember(ref, otp);
        setTimeout(() => { location.href = `/track?ref=${encodeURIComponent(ref)}&new=1`; }, 1500);
        return;
      }
    } catch (err) {
      result.className = 'alert alert-bad';
      result.textContent = err.message;
    }
    actions.querySelectorAll('button').forEach(b => { b.disabled = false; });
  });
})();
