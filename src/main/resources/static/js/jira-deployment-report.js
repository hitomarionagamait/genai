(() => {
    const buttons = document.querySelectorAll('.tab-button');
    const panels = document.querySelectorAll('.tab-panel');

    buttons.forEach((button) => {
        button.addEventListener('click', () => {
            const target = button.dataset.tab;
            buttons.forEach((item) => {
                const active = item === button;
                item.classList.toggle('active', active);
                item.setAttribute('aria-selected', String(active));
            });
            panels.forEach((panel) => {
                const active = panel.id === `tab-${target}`;
                panel.classList.toggle('active', active);
                panel.hidden = !active;
            });
        });
    });
})();
