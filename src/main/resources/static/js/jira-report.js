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

// ---- Pagination independen untuk setiap tabel pada setiap tab ----
(function () {
    const tablePages = new Map();
    const dataRows = (table) => Array.from(table.tBodies[0]?.rows || [])
        .filter((row) => !row.classList.contains('none') && row.cells.length > 1);

    function installPagination(table) {
        const rows = dataRows(table);
        const scroll = table.closest('.scroll') || table;
        const controls = document.createElement('div');
        controls.className = 'pagination-controls';
        controls.innerHTML = `
            <label>Baris per halaman
                <select class="page-size" aria-label="Jumlah item per halaman">
                    <option value="10" selected>10</option>
                    <option value="25">25</option>
                    <option value="50">50</option>
                    <option value="100">100</option>
                    <option value="all">Semua</option>
                </select>
            </label>
            <span class="page-info"></span>
            <button type="button" class="page-prev" aria-label="Halaman sebelumnya">‹</button>
            <button type="button" class="page-next" aria-label="Halaman berikutnya">›</button>`;
        scroll.insertAdjacentElement('afterend', controls);

        const sizeSelect = controls.querySelector('.page-size');
        const info = controls.querySelector('.page-info');
        const prev = controls.querySelector('.page-prev');
        const next = controls.querySelector('.page-next');
        let page = 1;
        let pageSize = 10;
        let filtered = rows.slice();

        const render = () => {
            const pageCount = pageSize === Infinity ? 1 : Math.max(1, Math.ceil(filtered.length / pageSize));
            page = Math.min(Math.max(1, page), pageCount);
            const start = pageSize === Infinity ? 0 : (page - 1) * pageSize;
            const end = pageSize === Infinity ? filtered.length : Math.min(filtered.length, start + pageSize);
            const visible = new Set(filtered.slice(start, end));
            rows.forEach((row) => { row.style.display = visible.has(row) ? '' : 'none'; });
            info.textContent = filtered.length === 0 ? '0 item' : `${start + 1}–${end} dari ${filtered.length} item`;
            prev.disabled = page <= 1 || filtered.length === 0;
            next.disabled = page >= pageCount || filtered.length === 0;
            controls.hidden = rows.length === 0;
        };

        const api = {
            setFilter(predicate) {
                filtered = rows.filter(predicate);
                page = 1;
                render();
            },
            refresh() { render(); },
            rows
        };
        sizeSelect.addEventListener('change', () => {
            pageSize = sizeSelect.value === 'all' ? Infinity : Number(sizeSelect.value);
            page = 1;
            render();
        });
        prev.addEventListener('click', () => { page--; render(); });
        next.addEventListener('click', () => { page++; render(); });
        render();
        return api;
    }

    document.querySelectorAll('.panel table.tbl[data-paginate="true"]').forEach((table) => {
        tablePages.set(table.id || `table-${tablePages.size + 1}`, installPagination(table));
    });

    const allTable = document.getElementById('all-table');
    if (allTable) {
        const api = tablePages.get('all-table');
        const q = document.getElementById('all-filter');
        const type = document.getElementById('all-type');
        const info = document.getElementById('all-visible');
        const apply = () => {
            const term = q.value.trim().toLowerCase();
            const selectedType = type.value;
            api.setFilter((row) => (!selectedType || row.dataset.type === selectedType)
                && (!term || row.textContent.toLowerCase().includes(term)));
            info.textContent = `${api.rows.filter((row) => (!selectedType || row.dataset.type === selectedType)
                && (!term || row.textContent.toLowerCase().includes(term))).length} dari ${api.rows.length} tiket`;
        };
        q.addEventListener('input', apply);
        type.addEventListener('change', apply);
        apply();
    }

    const bugsTable = document.getElementById('bugs-development-uat-table');
    if (bugsTable) {
        const api = tablePages.get('bugs-development-uat-table');
        const q = document.getElementById('bugs-development-uat-filter');
        const info = document.getElementById('bugs-development-uat-visible');
        const apply = () => {
            const term = q.value.trim().toLowerCase();
            api.setFilter((row) => !term || row.textContent.toLowerCase().includes(term));
            const count = api.rows.filter((row) => !term || row.textContent.toLowerCase().includes(term)).length;
            info.textContent = `${count} dari ${api.rows.length} tiket`;
        };
        q.addEventListener('input', apply);
        apply();
    }
})();

// ---- Loading overlay ketika form Tampilkan / Ambil ulang dikirim ----
(function () {
    const form = document.getElementById('report-form');
    const overlay = document.getElementById('report-loading');
    if (!form || !overlay) return;
    form.addEventListener('submit', (event) => {
        // Submit buttons are disabled below for UX. Keep the intended refresh mode in
        // an enabled hidden input so disabling the clicked button cannot drop refresh=true.
        const refreshFlag = document.getElementById('report-refresh-flag');
        if (refreshFlag) {
            refreshFlag.value = event.submitter && event.submitter.dataset.refresh === 'true' ? 'true' : 'false';
        }
        overlay.hidden = false;
        form.querySelectorAll('button[type="submit"]').forEach((button) => {
            button.disabled = true;
            button.setAttribute('aria-disabled', 'true');
        });
    });
    // Some browsers restore the page from back/forward cache with its old DOM state.
    // Remove the overlay when that happens so the page does not remain visually blocked.
    window.addEventListener('pageshow', () => {
        overlay.hidden = true;
        form.querySelectorAll('button[type="submit"]').forEach((button) => {
            button.disabled = false;
            button.removeAttribute('aria-disabled');
        });
    });
})();


// ---- Progress ketika membuat dan mengunduh template Excel ----
(function () {
    const link = document.querySelector('a[data-excel-download]');
    const overlay = document.getElementById('excel-download-loading');
    if (!link || !overlay || typeof window.fetch !== 'function') return;

    const title = document.getElementById('excel-download-title');
    const message = document.getElementById('excel-download-message');
    const track = document.getElementById('excel-download-track');
    const bar = document.getElementById('excel-download-progress');
    const percent = document.getElementById('excel-download-percent');
    let busy = false;

    const show = () => {
        overlay.hidden = false;
        title.textContent = 'Menyiapkan file Excel…';
        message.textContent = 'Mengumpulkan data Jira dan Bitbucket serta menyusun workbook.';
        percent.textContent = 'Sedang memproses…';
        track.classList.add('indeterminate');
        bar.style.width = '35%';
    };
    const hide = () => { overlay.hidden = true; };

    link.addEventListener('click', async (event) => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        if (busy) return;
        busy = true;
        link.setAttribute('aria-disabled', 'true');
        link.classList.add('disabled');
        show();

        try {
            const response = await fetch(link.href, { credentials: 'same-origin', headers: { 'Accept': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' } });
            if (!response.ok) {
                const detail = await response.text();
                throw new Error(detail || `Download gagal (HTTP ${response.status})`);
            }
            const contentType = (response.headers.get('Content-Type') || '').toLowerCase();
            if (contentType && !contentType.includes('spreadsheetml')) {
                const detail = await response.text();
                throw new Error(detail || 'Server tidak mengembalikan file Excel yang valid.');
            }

            title.textContent = 'Mengunduh file Excel…';
            message.textContent = 'Template selesai disusun. Sedang menerima file dari server.';
            const totalHeader = Number(response.headers.get('Content-Length'));
            const total = Number.isFinite(totalHeader) && totalHeader > 0 ? totalHeader : 0;
            const chunks = [];
            let loaded = 0;

            if (response.body && response.body.getReader) {
                const reader = response.body.getReader();
                while (true) {
                    const result = await reader.read();
                    if (result.done) break;
                    chunks.push(result.value);
                    loaded += result.value.byteLength;
                    if (total > 0) {
                        const value = Math.min(99, Math.round((loaded / total) * 100));
                        track.classList.remove('indeterminate');
                        bar.style.width = `${value}%`;
                        percent.textContent = `${value}% · ${(loaded / 1024).toFixed(0)} KB dari ${(total / 1024).toFixed(0)} KB`;
                    } else {
                        percent.textContent = `${(loaded / 1024).toFixed(0)} KB diterima`;
                    }
                }
            } else {
                const data = await response.blob();
                chunks.push(data);
                loaded = data.size;
            }

            const blob = new Blob(chunks, { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
            const disposition = response.headers.get('Content-Disposition') || '';
            const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i);
            const plain = disposition.match(/filename="?([^";]+)"?/i);
            const filename = encoded ? decodeURIComponent(encoded[1]) : (plain ? plain[1] : 'Pre_Deployment_Checklist.xlsx');
            const objectUrl = URL.createObjectURL(blob);
            const download = document.createElement('a');
            download.href = objectUrl;
            download.download = filename;
            document.body.appendChild(download);
            download.click();
            download.remove();
            window.setTimeout(() => URL.revokeObjectURL(objectUrl), 30000);

            track.classList.remove('indeterminate');
            bar.style.width = '100%';
            percent.textContent = total > 0 ? '100% selesai' : `${(loaded / 1024).toFixed(0)} KB selesai`;
            title.textContent = 'Download Excel siap';
            message.textContent = 'File berhasil disiapkan dan proses download telah dimulai.';
            window.setTimeout(hide, 900);
        } catch (error) {
            hide();
            window.alert(error && error.message ? error.message : 'Gagal mengunduh file Excel.');
        } finally {
            busy = false;
            link.removeAttribute('aria-disabled');
            link.classList.remove('disabled');
        }
    });
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
