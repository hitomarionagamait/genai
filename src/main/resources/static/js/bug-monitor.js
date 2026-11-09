(function () {
    const groups = Array.from(document.querySelectorAll('.vgroup'));
    if (!groups.length) return;
    const $ = (id) => document.getElementById(id);
    const q = $('f-q'), fGroup = $('f-group'), fStatus = $('f-status'), fPrio = $('f-priority');
    const cCrit = $('f-critical'), cUn = $('f-unassigned'), cAging = $('f-aging'), info = $('f-info');

    const active = () => q.value.trim() || fGroup.value || fStatus.value || fPrio.value
        || cCrit.checked || cUn.checked || cAging.checked;

    const apply = () => {
        const term = q.value.trim().toLowerCase();
        let shownTotal = 0, all = 0;
        groups.forEach((g) => {
            const rows = Array.from(g.querySelectorAll('tbody tr[data-row]'));
            let shown = 0;
            const groupOk = !fGroup.value || g.dataset.group === fGroup.value;
            rows.forEach((r) => {
                const d = r.dataset;
                const ok = groupOk
                    && (!fStatus.value || (fStatus.value === 'open') === (d.open === 'true'))
                    && (!fPrio.value || d.priority === fPrio.value)
                    && (!cCrit.checked || d.critical === 'true')
                    && (!cUn.checked || (d.unassigned === 'true' && d.open === 'true'))
                    && (!cAging.checked || d.aging === 'true')
                    && (!term || r.textContent.toLowerCase().includes(term));
                r.style.display = ok ? '' : 'none';
                if (ok) shown++;
            });
            g.querySelector('.gshown').textContent = active() ? (shown + ' dari ' + rows.length + ' tampil') : '';
            g.style.display = shown === 0 && active() ? 'none' : '';
            if (active() && shown > 0 && (term || fGroup.value)) g.open = true;
            shownTotal += shown; all += rows.length;
        });
        info.textContent = active() ? (shownTotal + ' baris cocok') : '';
    };

    [q, fGroup, fStatus, fPrio, cCrit, cUn, cAging].forEach((el) => el.addEventListener(el.tagName === 'INPUT' && el.type === 'search' ? 'input' : 'change', apply));
    $('expand-all').addEventListener('click', () => groups.forEach((g) => { g.open = true; }));
    $('collapse-all').addEventListener('click', () => groups.forEach((g) => { g.open = false; }));
})();
