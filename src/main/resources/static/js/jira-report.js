// ---- Tab navigasi (state di URL hash, mis. #devops) ----
(function () {
    const tabs = document.getElementById('tabs');
    if (!tabs) return;
    const buttons = tabs.querySelectorAll('button[data-tab]');
    const show = (name) => {
        let found = false;
        buttons.forEach((b) => {
            const on = b.dataset.tab === name;
            b.classList.toggle('active', on);
            if (on) found = true;
        });
        if (!found) return false;
        document.querySelectorAll('.panel').forEach((p) => p.classList.toggle('active', p.id === 'tab-' + name));
        return true;
    };
    buttons.forEach((b) => b.addEventListener('click', () => {
        show(b.dataset.tab);
        history.replaceState(null, '', '#' + b.dataset.tab);
    }));
    if (location.hash) show(location.hash.slice(1));
})();

// ---- Filter tabel All Ticket ----
(function () {
    const table = document.getElementById('all-table');
    if (!table) return;
    const q = document.getElementById('all-filter');
    const type = document.getElementById('all-type');
    const info = document.getElementById('all-visible');
    const rows = Array.from(table.tBodies[0].rows).filter((r) => r.cells.length > 2);
    const apply = () => {
        const term = q.value.trim().toLowerCase();
        const t = type.value;
        let n = 0;
        rows.forEach((r) => {
            const ok = (!t || r.dataset.type === t) && (!term || r.textContent.toLowerCase().includes(term));
            r.style.display = ok ? '' : 'none';
            if (ok) n++;
        });
        info.textContent = n + ' dari ' + rows.length + ' tiket';
    };
    q.addEventListener('input', apply);
    type.addEventListener('change', apply);
    apply();
})();

// ---- Agent ----
(function () {
    const send = document.getElementById('agent-send');
    if (!send) return;
    const input = document.getElementById('agent-q');
    const answer = document.getElementById('agent-answer');
    const version = document.getElementById('agent-version');
    let conversationId = null;

    const ask = async () => {
        const message = input.value.trim();
        if (!message) return;
        send.disabled = true;
        answer.className = 'answer loading';
        answer.textContent = 'Agent sedang membaca data dan menyusun jawaban…';
        try {
            const res = await fetch('/api/jira-report/agent', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ fixVersion: version.value, message, conversationId })
            });
            const data = await res.json();
            if (res.ok) {
                conversationId = data.conversationId;
                answer.className = 'answer';
                answer.textContent = data.answer || '(tanpa jawaban)';
            } else {
                answer.className = 'answer error';
                answer.textContent = data.error || 'Terjadi kesalahan';
            }
        } catch (e) {
            answer.className = 'answer error';
            answer.textContent = 'Gagal terhubung ke server';
        } finally {
            send.disabled = false;
        }
    };

    send.addEventListener('click', ask);
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) ask(); });
    document.querySelectorAll('.chip-btn').forEach((b) => b.addEventListener('click', () => { input.value = b.dataset.q; ask(); }));
    document.getElementById('agent-reset').addEventListener('click', () => {
        conversationId = null;
        answer.className = 'answer';
        answer.textContent = '';
        input.value = '';
    });
})();
