/* Menu page: category filter, search, sort, and add-to-cart buttons. Everything runs in the browser. */
(() => {
  const grid = document.querySelector('[data-menu-grid]');
  const cards = [...grid.querySelectorAll('.menu-card')];
  const search = document.getElementById('search');
  const sort = document.getElementById('sort');
  const chips = [...document.querySelectorAll('.chip[data-category]')];
  let category = 'ALL';

  function cardData(card) {
    const d = card.dataset;
    return { id: d.id, name: d.name, price: Number(d.price), image: d.image || null, emoji: d.emoji, max: Number(d.stock) };
  }

  function apply() {
    const q = search.value.trim().toLowerCase();
    const sorters = {
      'default': (a, b) => a.dataset.order - b.dataset.order,
      'popular': (a, b) => b.dataset.popularity - a.dataset.popularity || a.dataset.order - b.dataset.order,
      'price-asc': (a, b) => a.dataset.price - b.dataset.price,
      'price-desc': (a, b) => b.dataset.price - a.dataset.price,
      'name': (a, b) => a.dataset.name.localeCompare(b.dataset.name)
    };
    let visible = 0;
    [...cards].sort(sorters[sort.value]).forEach(card => {
      const text = (card.dataset.name + ' ' + card.querySelector('.desc').textContent).toLowerCase();
      const show = (category === 'ALL' || card.dataset.category === category) && (!q || text.includes(q));
      card.classList.toggle('hidden', !show);
      if (show) visible++;
      grid.append(card); // re-append in sorted order
    });
    document.querySelector('[data-empty]').classList.toggle('hidden', visible > 0);
    document.querySelector('[data-result-count]').textContent = `${visible} dish${visible === 1 ? '' : 'es'}`;
  }

  /** Shows "Add" or a −/+ stepper on each orderable card, based on the cart. */
  function renderControls() {
    cards.filter(c => c.dataset.orderable === 'true').forEach(card => {
      const qty = NoQ.cart.qty(card.dataset.id);
      const box = card.querySelector('[data-cart-control]');
      box.innerHTML = qty === 0
        ? `<button class="btn btn-primary btn-sm" type="button" data-add="${card.dataset.id}" aria-label="Add ${NoQ.escapeHtml(card.dataset.name)} to cart">Add</button>`
        : `<div class="stepper"><button type="button" data-dec="${card.dataset.id}" aria-label="Remove one">−</button>
             <span aria-live="polite">${qty}</span>
             <button type="button" data-add="${card.dataset.id}" aria-label="Add one">+</button></div>`;
    });
  }

  document.addEventListener('click', e => {
    const add = e.target.closest('[data-add]');
    const dec = e.target.closest('[data-dec]');
    if (add) {
      const card = cards.find(c => c.dataset.id === add.dataset.add);
      if (!card || card.dataset.orderable !== 'true') return;
      NoQ.cart.add(cardData(card));
      renderControls();
    } else if (dec) {
      NoQ.cart.setQty(dec.dataset.dec, NoQ.cart.qty(dec.dataset.dec) - 1);
      renderControls();
    }
  });

  chips.forEach(chip => chip.addEventListener('click', () => {
    category = chip.dataset.category;
    chips.forEach(c => c.setAttribute('aria-pressed', String(c === chip)));
    apply();
  }));
  search.addEventListener('input', apply);
  sort.addEventListener('change', apply);

  renderControls();
  apply();
})();
