const form = document.getElementById("k6-form");
const messagesDiv = document.getElementById("messages");
const sendBtn = document.getElementById("send-btn");
const newSessionBtn = document.getElementById("new-session-btn");
const endpointList = document.getElementById("endpoint-list");
const generationMode = document.getElementById("generation-mode");

let conversationId = null;
let endpointCounter = 0;
const el = (id) => document.getElementById(id);

const currentEndpointPresets = [
    { name: "patient_menu_main", group: "patient", method: "GET", path: "/backend-api/patient/home/v2/menu", auth: "patient" },
    { name: "patient_payment_detail", group: "patient", method: "GET", path: "/backendapi-payment/payment/v3/detail?orderId={{order_id}}", auth: "patient" },
    { name: "food_search_v3", group: "patient", method: "POST", path: "/backendapi-nutritions/nutrition/v1/foods/search", auth: "patient" },
    { name: "detail_food_search_v3", group: "patient", method: "GET", path: "/backendapi-nutritions/nutrition/internal/v1/foods/detail", auth: "patient" },
    { name: "nutrient_food_search_v3", group: "patient", method: "POST", path: "/backendapi-nutritions/nutrition/v1/nutrient/food-search-v3", auth: "patient" },
    { name: "nutrient_detail_code_food_search_v3", group: "patient", method: "GET", path: "/backendapi-nutritions/nutrition/v1/nutrient/foods-details-by-code", auth: "patient" },
    { name: "payment_category_admin", group: "admin", method: "GET", path: "/backend-api/admin/payment/category", auth: "admin" },
    { name: "payment_method_admin", group: "admin", method: "GET", path: "/backend-api/admin/payment/options", auth: "admin" }
];

// Muat info workspace + folder untuk autocomplete.
(async function loadFolders() {
    try {
        const response = await fetch("/api/k6-agent/folders");
        if (!response.ok) throw new Error("Tidak dapat membaca workspace folder.");
        const data = await response.json();
        el("workspace-root").textContent = data.workspaceRoot || "(tidak diketahui)";
        const list = el("folder-list");
        list.innerHTML = "";
        [".", ...(data.folders || [])].forEach((folder) => {
            const option = document.createElement("option");
            option.value = folder;
            list.appendChild(option);
        });
    } catch (error) {
        el("workspace-root").textContent = "Tidak dapat dimuat; periksa K6_WORKSPACE_ROOT.";
    }
})();

function makeField(labelText, control, className = "") {
    const label = document.createElement("label");
    if (className) label.className = className;
    label.appendChild(document.createTextNode(labelText));
    label.appendChild(control);
    return label;
}

function makeInput(field, value, type = "text", placeholder = "") {
    const input = document.createElement("input");
    input.type = type;
    input.dataset.field = field;
    input.value = value ?? "";
    input.placeholder = placeholder;
    if (type === "number") input.min = "100";
    return input;
}

function makeSelect(field, options, value) {
    const select = document.createElement("select");
    select.dataset.field = field;
    options.forEach(([optionValue, label]) => {
        const option = document.createElement("option");
        option.value = optionValue;
        option.textContent = label;
        select.appendChild(option);
    });
    select.value = value;
    return select;
}

function makeTextarea(field, placeholder = "") {
    const textarea = document.createElement("textarea");
    textarea.dataset.field = field;
    textarea.rows = 3;
    textarea.placeholder = placeholder;
    return textarea;
}

function addEndpointRow(endpoint = {}) {
    endpointCounter += 1;
    const card = document.createElement("div");
    card.className = "endpoint-card";
    card.dataset.endpointId = String(endpointCounter);

    const head = document.createElement("div");
    head.className = "endpoint-card-head";
    const title = document.createElement("strong");
    title.textContent = `Endpoint ${endpointCounter}`;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "btn btn-danger";
    remove.textContent = "Hapus";
    remove.addEventListener("click", () => card.remove());
    head.append(title, remove);

    const grid = document.createElement("div");
    grid.className = "endpoint-grid";
    grid.append(
        makeField("Nama / tag endpoint", makeInput("name", endpoint.name || "", "text", "contoh: patient_order_detail"), "span-2"),
        makeField("Group / modul", makeSelect("group", [["patient", "Patient"], ["admin", "Admin"], ["custom", "Custom"]], endpoint.group || "patient")),
        makeField("HTTP method", makeSelect("method", [["GET", "GET"], ["POST", "POST"], ["PUT", "PUT"], ["PATCH", "PATCH"], ["DELETE", "DELETE"]], endpoint.method || "GET")),
        makeField("Path endpoint", makeInput("path", endpoint.path || "", "text", "/api/v1/resource"), "span-4"),
        makeField("Auth", makeSelect("auth", [["patient", "Patient token"], ["admin", "Admin token"], ["bearer", "Bearer token dari ENV"], ["none", "Tanpa auth"]], endpoint.auth || "patient")),
        makeField("Expected status", makeInput("expectedStatus", endpoint.expectedStatus || 200, "number")),
        makeField("Nama scenario (opsional)", makeInput("scenario", endpoint.scenario || "", "text", "patients_verification"), "span-2")
    );

    const jsonFields = document.createElement("div");
    jsonFields.className = "json-fields";
    const headers = makeTextarea("headers", '{\n  "Accept-Language": "id"\n}');
    headers.value = endpoint.headers ? JSON.stringify(endpoint.headers, null, 2) : "";
    const body = makeTextarea("body", '{\n  "key": "value"\n}');
    body.value = endpoint.body ? JSON.stringify(endpoint.body, null, 2) : "";
    jsonFields.append(
        makeField("Headers tambahan (JSON, opsional)", headers),
        makeField("Request body (JSON, opsional)", body)
    );

    card.append(head, grid, jsonFields);
    endpointList.appendChild(card);
}

el("add-endpoint-btn").addEventListener("click", () => addEndpointRow());
el("load-current-endpoints-btn").addEventListener("click", () => {
    endpointList.innerHTML = "";
    currentEndpointPresets.forEach((endpoint) => addEndpointRow(endpoint));
    addMessage("ai", "Template endpoint aktif versi 2.15.0 dimuat. Periksa kembali path, payload, dan status yang diharapkan sebelum menjalankan test.");
});

generationMode.addEventListener("change", () => {
    const extend = generationMode.value === "extend";
    if (extend) {
        el("target-folder").value = ".";
        el("reference-folder").value = ".";
        el("mode-help").textContent = "Mode update akan membaca project pada root (.) dan mengintegrasikan perubahan ke main.js/config.js/patientAPI.js/adminAPI.js bila diperlukan. Centang overwrite agar file existing dapat diperbarui; backup akan dibuat sebelum perubahan.";
        sendBtn.textContent = "Update Project k6";
    } else {
        if (el("target-folder").value === ".") el("target-folder").value = "generated";
        el("mode-help").textContent = "Mode file baru akan menyimpan hasil ke subfolder tujuan. Referensi project akan dibaca agar gaya script tetap konsisten.";
        sendBtn.textContent = "Generate File Baru";
    }
});

function collectEndpointSpecs() {
    const specs = [];
    const cards = [...endpointList.querySelectorAll(".endpoint-card")];
    for (const card of cards) {
        const get = (field) => card.querySelector(`[data-field="${field}"]`)?.value?.trim() ?? "";
        const name = get("name");
        const path = get("path");
        if (!name || !path) throw new Error("Setiap endpoint harus memiliki nama/tag dan path.");
        const status = Number(get("expectedStatus") || 200);
        if (!Number.isInteger(status) || status < 100 || status > 599) {
            throw new Error(`Expected status endpoint ${name} harus berupa HTTP status 100-599.`);
        }
        let headers = {};
        let body = null;
        try {
            const rawHeaders = get("headers");
            if (rawHeaders) headers = JSON.parse(rawHeaders);
            if (!headers || Array.isArray(headers) || typeof headers !== "object") throw new Error("Headers harus berupa object JSON.");
        } catch (error) {
            throw new Error(`Headers endpoint ${name} bukan JSON valid: ${error.message}`);
        }
        try {
            const rawBody = get("body");
            if (rawBody) body = JSON.parse(rawBody);
        } catch (error) {
            throw new Error(`Body endpoint ${name} bukan JSON valid: ${error.message}`);
        }
        specs.push({
            name,
            group: get("group"),
            method: get("method"),
            path,
            auth: get("auth"),
            expectedStatus: status,
            scenario: get("scenario") || null,
            headers,
            body
        });
    }
    return specs;
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();

    let endpointSpecs;
    try {
        endpointSpecs = collectEndpointSpecs();
    } catch (error) {
        addMessage("error", error.message);
        return;
    }

    const mode = generationMode.value;
    const overwrite = el("overwrite").checked;
    if (mode === "extend" && !overwrite) {
        addMessage("error", "Mode update project memerlukan checkbox 'Izinkan overwrite file existing'. File lama tetap akan dicadangkan sebelum ditimpa.");
        return;
    }

    const apiParts = [];
    const freeText = el("api-list").value.trim();
    if (freeText) apiParts.push(freeText);
    if (endpointSpecs.length) {
        apiParts.push("Spesifikasi endpoint terstruktur dari UI (gunakan detail ini tanpa mengarang field):\n" + JSON.stringify(endpointSpecs, null, 2));
    }

    const payload = {
        conversationId,
        targetFolder: el("target-folder").value.trim(),
        referenceFolder: el("reference-folder").value.trim(),
        baseUrl: el("base-url").value.trim(),
        testType: el("test-type").value,
        generationMode: mode,
        overwrite,
        backupBeforeOverwrite: el("backup-before-overwrite").checked,
        apiList: apiParts.join("\n\n"),
        message: el("message").value
    };

    addMessage("user", summarize(payload, endpointSpecs.length));
    sendBtn.disabled = true;
    const loadingId = addLoadingMessage();

    try {
        const response = await fetch("/api/k6-agent/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        const data = await response.json().catch(() => ({}));
        removeLoadingMessage(loadingId);
        if (response.ok) {
            conversationId = data.conversationId;
            addMessage("ai", data.answer || "(tanpa jawaban teks)", data.savedFiles, data.backupFiles);
            el("message").value = "";
        } else {
            addMessage("error", data.error || `Terjadi kesalahan (HTTP ${response.status})`);
        }
    } catch (error) {
        removeLoadingMessage(loadingId);
        addMessage("error", `Gagal terhubung ke server: ${error.message || "network error"}`);
    } finally {
        sendBtn.disabled = false;
    }
});

newSessionBtn.addEventListener("click", () => {
    conversationId = null;
    messagesDiv.innerHTML = "";
    addMessage("ai", "Sesi baru dimulai. Percakapan sebelumnya tidak diteruskan. Form endpoint tetap dipertahankan.");
});

function summarize(payload, endpointCount) {
    const lines = [
        `${payload.generationMode === "extend" ? "Update project" : "Generate file baru"} (${payload.testType}) → ${payload.targetFolder}`,
        `Endpoint terstruktur: ${endpointCount}`
    ];
    if (payload.referenceFolder) lines.push(`Referensi: ${payload.referenceFolder}`);
    if (payload.apiList.trim()) lines.push("API spec / curl: disertakan");
    if (payload.message.trim()) lines.push(`Instruksi: ${payload.message.trim()}`);
    return lines.join("\n");
}

function addMessage(type, text, savedFiles = [], backupFiles = []) {
    const messageDiv = document.createElement("div");
    messageDiv.className = `message ${type}`;
    const label = document.createElement("div");
    label.className = "message-label";
    label.textContent = type === "user" ? "You" : type === "error" ? "Error" : "K6 Agent";
    const bubble = document.createElement("div");
    bubble.className = "message-bubble";
    bubble.textContent = text;

    if (savedFiles?.length) {
        const box = document.createElement("div");
        box.className = "saved-files";
        const title = document.createElement("strong");
        title.textContent = "File tersimpan:";
        box.appendChild(title);
        savedFiles.forEach((file) => {
            const code = document.createElement("code");
            code.textContent = file;
            box.appendChild(code);
        });
        bubble.appendChild(box);
    }
    if (backupFiles?.length) {
        const box = document.createElement("div");
        box.className = "backup-files";
        const title = document.createElement("strong");
        title.textContent = "Backup file existing:";
        box.appendChild(title);
        backupFiles.forEach((file) => {
            const code = document.createElement("code");
            code.textContent = file;
            box.appendChild(code);
        });
        bubble.appendChild(box);
    }

    messageDiv.append(label, bubble);
    messagesDiv.appendChild(messageDiv);
    scrollToBottom();
}

function addLoadingMessage() {
    const messageDiv = document.createElement("div");
    messageDiv.className = "message ai";
    messageDiv.id = "loading-message";
    const bubble = document.createElement("div");
    bubble.className = "message-bubble loading";
    bubble.textContent = "Agent sedang membaca project, memeriksa endpoint, dan menyiapkan perubahan...";
    messageDiv.appendChild(bubble);
    messagesDiv.appendChild(messageDiv);
    scrollToBottom();
    return "loading-message";
}

function removeLoadingMessage(id) {
    document.getElementById(id)?.remove();
}

function scrollToBottom() {
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}
