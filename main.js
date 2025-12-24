const { Plugin, PluginSettingTab, Setting, Notice, TFile } = require('obsidian');

const DEFAULT_SETTINGS = {
    token: '',
    repo: '',
    branch: 'main',
    rootFolder: 'assets'
}

module.exports = class GitHubImageUpload extends Plugin {
    async onload() {
        await this.loadSettings();

        // 1. Add settings tab
        this.addSettingTab(new GitHubImageSettingTab(this.app, this));

        // Initialize base URL for CDN
        this.updateCdnBase();

        // 2. Desktop Events: Paste & Drop
        this.registerEvent(this.app.workspace.on('editor-paste', (evt, editor) => this.handleUpload(evt.clipboardData.files, evt, editor)));
        this.registerEvent(this.app.workspace.on('editor-drop', (evt, editor) => this.handleUpload(evt.dataTransfer.files, evt, editor)));

        // 3. Mobile Events: Ribbon Button (Mobile only)
        if (this.app.isMobile) {
            this.addRibbonIcon('image-plus', 'GitHub: Upload image', () => this.triggerMobileUpload());
        }

        // 4. Command Palette Commands (Ctrl + P)
        this.addCommand({
            id: 'upload-image-github',
            name: 'GitHub: Select image from device and upload',
            editorCallback: (editor) => this.triggerMobileUpload(editor)
        });

        this.addCommand({
            id: 'check-storage-github',
            name: 'GitHub: Check image storage usage',
            callback: () => this.checkGithubStorage()
        });

        this.addCommand({
            id: 'cleanup-images-github',
            name: 'GitHub: Clean up unused images (Full scan)',
            callback: () => this.cleanupUnusedImages()
        });

        // 5. Context Menu: Delete image from GitHub
        this.registerEvent(this.app.workspace.on("editor-menu", (menu, editor) => {
            const lineText = editor.getLine(editor.getCursor().line);
            // Identify GitHub or CDN links in the current line
            if ((lineText.includes("raw.githubusercontent.com") || lineText.includes("cdn.jsdelivr.net")) && lineText.includes("![")) {
                menu.addItem((item) => {
                    item.setTitle("🗑️ Delete this image from GitHub")
                        .setIcon("trash")
                        .onClick(() => this.deleteImageFromGithub(lineText, editor));
                });
            }
        }));
    }

    // --- SETTINGS MANAGEMENT ---
    async loadSettings() {
        this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
    }

    async saveSettings() {
        await this.saveData(this.settings);
        this.updateCdnBase();
    }

    updateCdnBase() {
        this.CDN_BASE = `https://cdn.jsdelivr.net/gh/${this.settings.repo}@${this.settings.branch}/`;
    }

    // --- FILE PROCESSING LOGIC ---
    getFolderPathStructure() {
        const now = new Date();
        return `${now.getFullYear()}/${(now.getMonth() + 1).toString().padStart(2, '0')}`;
    }

    getTimestampName() {
        const now = new Date();
        const pad = (n) => n.toString().padStart(2, '0');
        return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    }

    async handleUpload(files, evt, editor) {
        if (!files || files.length === 0) return;
        for (let file of files) {
            if (file.type.startsWith('image')) {
                evt.preventDefault();
                await this.uploadToGithub(file, editor);
            }
        }
    }

    triggerMobileUpload(editor) {
        const input = document.createElement('input');
        input.type = 'file'; input.accept = 'image/*'; input.multiple = true;
        input.onchange = async () => {
            const files = input.files;
            const activeEditor = editor || this.app.workspace.activeLeaf.view.editor;
            for (let i = 0; i < files.length; i++) await this.uploadToGithub(files[i], activeEditor);
        };
        input.click();
    }

    async uploadToGithub(file, editor) {
        if (!this.settings.token || !this.settings.repo) {
            new Notice("⚠️ Please configure Token and Repo in settings!");
            return;
        }

        const reader = new FileReader();
        reader.onloadend = async () => {
            const base64 = reader.result.split(',')[1];
            const extension = file.name.split('.').pop() || 'png';
            const fileName = `${this.getTimestampName()}.${extension}`;
            const path = `${this.settings.rootFolder}/${this.getFolderPathStructure()}/${fileName}`.replace(/\/+/g, '/');

            new Notice(`🚀 Uploading: ${fileName}`);

            try {
                const res = await fetch(`https://api.github.com/repos/${this.settings.repo}/contents/${path}`, {
                    method: 'PUT',
                    headers: { 'Authorization': `token ${this.settings.token}` },
                    body: JSON.stringify({ message: `Upload ${fileName}`, content: base64, branch: this.settings.branch })
                });

                if (res.ok) {
                    editor.replaceSelection(`![${fileName}](${this.CDN_BASE}${path})\n`);
                    new Notice(`✅ Success!`);
                }
            } catch (e) { new Notice(`❌ API connection error.`); }
        };
        reader.readAsDataURL(file);
    }

    // --- DELETE AND CLEANUP FEATURES ---
    async deleteImageFromGithub(lineText, editor) {
        const branchPattern = `${this.settings.branch}/`;
        const startIdx = lineText.indexOf(branchPattern);
        if (startIdx === -1) return;
        
        const pathPart = lineText.substring(startIdx + branchPattern.length).split(')')[0];
        const path = decodeURIComponent(pathPart);

        new Notice("🗑️ Deleting image from GitHub...");

        try {
            const getRes = await fetch(`https://api.github.com/repos/${this.settings.repo}/contents/${path}`, {
                headers: { 'Authorization': `token ${this.settings.token}` }
            });
            if (getRes.ok) {
                const data = await getRes.json();
                const delRes = await fetch(`https://api.github.com/repos/${this.settings.repo}/contents/${path}`, {
                    method: 'DELETE',
                    headers: { 'Authorization': `token ${this.settings.token}` },
                    body: JSON.stringify({ message: `Delete image`, sha: data.sha, branch: this.settings.branch })
                });
                if (delRes.ok) {
                    editor.setLine(editor.getCursor().line, ""); 
                    new Notice('✅ Permanently deleted!');
                }
            }
        } catch (e) { new Notice("❌ Error deleting image."); }
    }

    async cleanupUnusedImages() {
        new Notice("🔍 Scanning for unused images...");
        try {
            const allMdFiles = this.app.vault.getMarkdownFiles();
            const usedPaths = new Set();
            const regex = /https:\/\/(?:raw\.githubusercontent\.com|cdn\.jsdelivr\.net)\/.*?\/(.*?)\.(png|jpg|jpeg|gif|webp|svg)/gi;

            for (const file of allMdFiles) {
                const content = await this.app.vault.read(file);
                let m; while ((m = regex.exec(content)) !== null) {
                    const path = decodeURIComponent(m[0]).split(`${this.settings.branch}/`)[1];
                    if (path) usedPaths.add(path);
                }
            }

            const treeRes = await fetch(`https://api.github.com/repos/${this.settings.repo}/git/trees/${this.settings.branch}?recursive=1`, {
                headers: { 'Authorization': `token ${this.settings.token}` }
            });
            const treeData = await treeRes.json();
            const unused = treeData.tree.filter(item => 
                item.type === 'blob' && item.path.startsWith(this.settings.rootFolder) && 
                /\.(png|jpg|jpeg|gif|webp|svg)$/i.test(item.path) && !usedPaths.has(item.path)
            );

            if (unused.length === 0) return new Notice("✨ GitHub is clean!");
            if (!confirm(`Delete ${unused.length} unused images from GitHub?`)) return;

            for (const file of unused) {
                await fetch(`https://api.github.com/repos/${this.settings.repo}/contents/${file.path}`, {
                    method: 'DELETE',
                    headers: { 'Authorization': `token ${this.settings.token}` },
                    body: JSON.stringify({ message: `Cleanup`, sha: file.sha, branch: this.settings.branch })
                });
            }
            new Notice(`✅ Cleaned up ${unused.length} unused images.`);
        } catch (e) { new Notice("❌ Cleanup error."); }
    }

    async checkGithubStorage() {
        new Notice("📊 Calculating...");
        try {
            const res = await fetch(`https://api.github.com/repos/${this.settings.repo}/git/trees/${this.settings.branch}?recursive=1`, {
                headers: { 'Authorization': `token ${this.settings.token}` }
            });
            const data = await res.json();
            const images = data.tree.filter(i => i.path.startsWith(this.settings.rootFolder) && /\.(png|jpg|jpeg|gif|webp|svg)$/i.test(i.path));
            let bytes = 0; images.forEach(f => bytes += (f.size || 0));
            new Notice(`📂 ${images.length} images\n💾 ${(bytes / (1024 * 1024)).toFixed(2)} MB`, 6000);
        } catch (e) { new Notice("❌ Check error."); }
    }
}

// --- SETTINGS UI ---
class GitHubImageSettingTab extends PluginSettingTab {
    constructor(app, plugin) { super(app, plugin); this.plugin = plugin; }

    display() {
        const { containerEl } = this;
        containerEl.empty();
        containerEl.createEl('h2', { text: 'GitHub Image Upload Settings' });

        new Setting(containerEl).setName('GitHub Token').setDesc('Personal Access Token (should have repo scope).')
            .addText(text => text.setPlaceholder('ghp_xxx').setValue(this.plugin.settings.token)
            .onChange(async (v) => { this.plugin.settings.token = v; await this.plugin.saveSettings(); })
            .inputEl.type = 'password');

        new Setting(containerEl).setName('Repository').setDesc('Username/RepoName')
            .addText(text => text.setPlaceholder('Username/RepoName').setValue(this.plugin.settings.repo)
            .onChange(async (v) => { this.plugin.settings.repo = v; await this.plugin.saveSettings(); }));

        new Setting(containerEl).setName('Branch').setDesc('Main branch (usually "main")')
            .addText(text => text.setValue(this.plugin.settings.branch)
            .onChange(async (v) => { this.plugin.settings.branch = v; await this.plugin.saveSettings(); }));

        new Setting(containerEl).setName('Root Folder').setDesc('Root folder for storing images on GitHub')
            .addText(text => text.setValue(this.plugin.settings.rootFolder)
            .onChange(async (v) => { this.plugin.settings.rootFolder = v; await this.plugin.saveSettings(); }));
    }
}