const { Plugin, Modal, Setting, MarkdownRenderer, Notice, TFile } = require('obsidian');

const ITERATIONS = 210000; 
const SALT_SIZE = 16;
const IV_SIZE = 12;
const PREFIX = "🔐β "; 
const SUFFIX = " 🔐";
const HINT_MARKER = "💡";

class CryptoEngine {
    async compress(text) {
        const encoder = new TextEncoder();
        const rawData = encoder.encode(text);
        const stream = new Blob([rawData]).stream().pipeThrough(new CompressionStream("deflate"));
        const response = new Response(stream);
        const compressedBuffer = await response.arrayBuffer();
        return new Uint8Array(compressedBuffer);
    }

    async decompress(data) {
        const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream("deflate"));
        const response = new Response(stream);
        const decompressedBuffer = await response.arrayBuffer();
        return new TextDecoder().decode(decompressedBuffer);
    }

    async deriveKey(password, salt) {
        const encoder = new TextEncoder();
        const baseKey = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveKey"]);
        return await crypto.subtle.deriveKey(
            { name: "PBKDF2", salt: salt, iterations: ITERATIONS, hash: "SHA-512" },
            baseKey, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]
        );
    }

    async encrypt(plainText, password) {
        const compressedData = await this.compress(plainText);
        const salt = crypto.getRandomValues(new Uint8Array(SALT_SIZE));
        const iv = crypto.getRandomValues(new Uint8Array(IV_SIZE));
        const key = await this.deriveKey(password, salt);
        const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv: iv }, key, compressedData);
        
        const combined = new Uint8Array(IV_SIZE + SALT_SIZE + encrypted.byteLength);
        combined.set(iv, 0);
        combined.set(salt, IV_SIZE);
        combined.set(new Uint8Array(encrypted), IV_SIZE + SALT_SIZE);
        return btoa(String.fromCharCode(...combined));
    }

    async decrypt(cipherBase64, password) {
        try {
            const combined = Uint8Array.from(atob(cipherBase64), c => c.charCodeAt(0));
            const iv = combined.slice(0, IV_SIZE);
            const salt = combined.slice(IV_SIZE, IV_SIZE + SALT_SIZE);
            const data = combined.slice(IV_SIZE + SALT_SIZE);
            const key = await this.deriveKey(password, salt);
            const decryptedContent = await crypto.subtle.decrypt({ name: "AES-GCM", iv: iv }, key, data);
            return await this.decompress(decryptedContent);
        } catch (e) { return null; }
    }
}

class PasswordModal extends Modal {
    constructor(app, isEncrypting, existingHint, callback) {
        super(app);
        this.password = ""; this.hint = existingHint || "";
        this.isEncrypting = isEncrypting; this.callback = callback;
    }
    onOpen() {
        const { contentEl } = this;
        this.titleEl.setText(this.isEncrypting ? "Khóa nội dung" : "Giải mã nội dung");
        const ps = new Setting(contentEl).setName("Mật khẩu").setDesc(!this.isEncrypting && this.hint ? `Gợi ý: ${this.hint}` : "");
        let pInput;
        ps.addText(t => { 
            pInput = t; 
            t.inputEl.type = "password"; 
            t.onChange(v => this.password = v); 
        });
        ps.addButton(b => b.setIcon("eye").setTooltip("Hiện/Ẩn mật khẩu").onClick(() => { 
            const isP = pInput.inputEl.type === "password";
            pInput.inputEl.type = isP ? "text" : "password"; 
            b.setIcon(isP ? "eye-off" : "eye");
        }));
        if (this.isEncrypting) {
            new Setting(contentEl).setName("Gợi ý").addText(t => t.setPlaceholder("Nhắc nhở mật mã...").onChange(v => this.hint = v));
        }
        pInput.inputEl.focus();
        pInput.inputEl.addEventListener("keypress", (e) => { if (e.key === "Enter") { this.callback(this.password, this.hint); this.close(); }});
        new Setting(contentEl).addButton(b => b.setButtonText("Xác nhận").setCta().onClick(() => { this.callback(this.password, this.hint); this.close(); }));
    }
}

class PreviewModal extends Modal {
    constructor(app, text, path, onRestore) { super(app); this.text = text; this.path = path; this.onRestore = onRestore; }
    async onOpen() {
        const { contentEl } = this;
        this.titleEl.setText("Nội dung đã giải mã");
        contentEl.classList.add("meld-no-scroll");
        const div = contentEl.createDiv({ cls: "meld-decrypt-preview-container markdown-rendered" });
        await MarkdownRenderer.renderMarkdown(this.text, div, this.path, this);
        const act = new Setting(contentEl);
        if (this.onRestore) {
            act.addButton(b => b.setButtonText("Khôi phục bản gốc").setWarning().onClick(() => { 
                this.onRestore(this.text); 
                this.close(); 
                new Notice("✅ Đã khôi phục văn bản gốc!");
            }));
        }
        act.addButton(b => b.setButtonText("Copy").onClick(() => { navigator.clipboard.writeText(this.text); new Notice("Đã sao chép!"); }));
    }
}

module.exports = class MeldEncryptLite extends Plugin {
    async onload() {
        this.crypto = new CryptoEngine();
        this.addCommand({ id: 'enc', name: 'Encrypt Selection', editorCallback: (ed) => this.processEncrypt(ed) });
        this.addCommand({ id: 'dec', name: 'Decrypt & Preview', editorCallback: (ed) => this.processDecrypt(ed) });
        
        this.registerMarkdownPostProcessor((el, ctx) => {
            el.querySelectorAll("p, li, span, div").forEach(m => {
                if (m.innerText.includes(PREFIX) && m.innerText.includes(SUFFIX)) {
                    const reg = /🔐β (?:💡(.*?)💡)?(.*?) 🔐/g;
                    // Lưu lại toàn bộ đoạn text gốc để replace sau này
                    m.innerHTML = m.innerHTML.replace(reg, (match, hint, cipher) => {
                        const rawMatch = match; 
                        return `<span class="meld-encrypt-inline-reading-marker" data-cipher="${cipher.trim()}" data-hint="${hint || ''}" data-raw="${encodeURIComponent(rawMatch)}">🔐 Click để xem nội dung bí mật</span>`;
                    });

                    m.querySelectorAll(".meld-encrypt-inline-reading-marker").forEach(span => {
                        span.addEventListener("click", () => {
                            new PasswordModal(this.app, false, span.dataset.hint, async (pw) => {
                                const dec = await this.crypto.decrypt(span.dataset.cipher, pw);
                                if (dec) {
                                    // Tạo callback khôi phục cho Reading Mode
                                    const onRestore = async (decryptedText) => {
                                        const file = this.app.vault.getAbstractFileByPath(ctx.sourcePath);
                                        if (file instanceof TFile) {
                                            const content = await this.app.vault.read(file);
                                            const rawToReplace = decodeURIComponent(span.dataset.raw);
                                            const updatedContent = content.replace(rawToReplace, decryptedText);
                                            await this.app.vault.modify(file, updatedContent);
                                        }
                                    };
                                    new PreviewModal(this.app, dec, ctx.sourcePath, onRestore).open();
                                } else {
                                    new Notice("❌ Mật khẩu không chính xác!");
                                }
                            }).open();
                        });
                    });
                }
            });
        });
    }

    async processEncrypt(editor) {
        const sel = editor.getSelection(); if (!sel) return new Notice("Vui lòng bôi đen văn bản!");
        new PasswordModal(this.app, true, "", async (pw, hint) => {
            if (!pw) return;
            const cipher = await this.crypto.encrypt(sel, pw);
            const hPart = hint ? `${HINT_MARKER}${hint}${HINT_MARKER}` : "";
            editor.replaceSelection(`${PREFIX}${hPart}${cipher}${SUFFIX}`);
        }).open();
    }

    async processDecrypt(editor) {
        let sel = editor.getSelection();
        let range = { from: editor.getCursor("from"), to: editor.getCursor("to") };
        if (!sel) {
            const cur = editor.getCursor(); const line = editor.getLine(cur.line);
            const s = line.lastIndexOf(PREFIX, cur.ch), e = line.indexOf(SUFFIX, cur.ch);
            if (s !== -1 && e !== -1) {
                range = { from: { line: cur.line, ch: s }, to: { line: cur.line, ch: e + SUFFIX.length } };
                sel = editor.getRange(range.from, range.to);
            }
        }
        if (!sel || !sel.includes(PREFIX)) return new Notice("Không tìm thấy đoạn mã hóa!");
        let hint = "", cipher = "";
        const raw = sel.replace(PREFIX, "").replace(SUFFIX, "").trim();
        if (raw.startsWith(HINT_MARKER)) {
            const p = raw.split(HINT_MARKER); hint = p[1]; cipher = p[2];
        } else cipher = raw;

        new PasswordModal(this.app, false, hint, async (pw) => {
            const dec = await this.crypto.decrypt(cipher, pw);
            if (dec) {
                // Callback thay thế trực tiếp trong Editor
                const onRestore = (t) => editor.replaceRange(t, range.from, range.to);
                new PreviewModal(this.app, dec, this.app.workspace.getActiveFile()?.path, onRestore).open();
            } else {
                new Notice("❌ Giải mã thất bại!");
            }
        }).open();
    }
};
