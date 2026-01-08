const { Plugin, Notice, setIcon } = require('obsidian');

const DEFAULT_SETTINGS = {
    hiddenPaths: [],
    isRevealMode: false
};

module.exports = class FileHiderPlugin extends Plugin {
    async onload() {
        await this.loadSettings();

        // 1. Tạo style
        this.styleEl = document.head.createEl('style');
        this.styleEl.id = 'obsidian-file-hider-css';
        this.updateHiddenStyles();

        // 2. Tạo Ribbon
        this.createRibbonIcon();

        // 3. EVENT QUAN TRỌNG: Xử lý menu chuột phải
        
        // Trường hợp 1: Click phải vào 1 file đơn lẻ (hoặc file không nằm trong vùng chọn)
        this.registerEvent(
            this.app.workspace.on("file-menu", (menu, file) => {
                this.addMenuOptions(menu, [file]);
            })
        );

        // Trường hợp 2: Click phải khi đang chọn nhiều file (Multi-select)
        this.registerEvent(
            this.app.workspace.on("files-menu", (menu, files) => {
                this.addMenuOptions(menu, files);
            })
        );

        // 4. Xử lý đổi tên/xóa file
        this.registerEvent(
            this.app.vault.on("rename", (file, oldPath) => {
                if (this.settings.hiddenPaths.includes(oldPath)) {
                    this.settings.hiddenPaths = this.settings.hiddenPaths.filter(p => p !== oldPath);
                    this.settings.hiddenPaths.push(file.path);
                    this.saveSettings();
                    this.updateHiddenStyles();
                }
            })
        );

        this.registerEvent(
            this.app.vault.on("delete", (file) => {
                if (this.settings.hiddenPaths.includes(file.path)) {
                    this.settings.hiddenPaths = this.settings.hiddenPaths.filter(p => p !== file.path);
                    this.saveSettings();
                    this.updateHiddenStyles();
                }
            })
        );
    }

    onunload() {
        if (this.styleEl) this.styleEl.remove();
        if (this.ribbonIconEl) this.ribbonIconEl.remove();
    }

    // --- LOGIC MENU CHUNG (Dùng cho cả file-menu và files-menu) ---
    
    addMenuOptions(menu, files) {
        // Lấy danh sách đường dẫn từ danh sách file đầu vào
        const targetPaths = files.map(f => f.path);

        // Kiểm tra xem toàn bộ đám file này đã bị ẩn chưa
        const allHidden = targetPaths.every(path => this.settings.hiddenPaths.includes(path));

        if (allHidden) {
            menu.addItem((item) => {
                item.setTitle(`Unhide ${files.length > 1 ? files.length + ' items' : 'item'}`)
                    .setIcon("eye")
                    .setSection("action") // Gom nhóm vào section action cho đẹp
                    .onClick(async () => {
                        this.settings.hiddenPaths = this.settings.hiddenPaths.filter(p => !targetPaths.includes(p));
                        await this.saveSettings();
                        this.updateHiddenStyles();
                        new Notice(`Đã hiện lại ${files.length} mục.`);
                    });
            });
        } else {
            menu.addItem((item) => {
                item.setTitle(`Hide ${files.length > 1 ? files.length + ' items' : 'item'}`)
                    .setIcon("eye-off")
                    .setSection("action")
                    .onClick(async () => {
                        targetPaths.forEach(path => {
                            if (!this.settings.hiddenPaths.includes(path)) {
                                this.settings.hiddenPaths.push(path);
                            }
                        });
                        await this.saveSettings();
                        this.updateHiddenStyles();
                        new Notice(`Đã ẩn ${files.length} mục.`);
                    });
            });
        }
    }

    // --- LOGIC RIBBON ---

    createRibbonIcon() {
        this.ribbonIconEl = this.addRibbonIcon('eye', 'File Hider', (evt) => {
            this.toggleRevealMode();
        });
        this.updateRibbonVisuals();
    }

    toggleRevealMode() {
        this.settings.isRevealMode = !this.settings.isRevealMode;
        this.saveSettings();
        this.updateRibbonVisuals();
        this.updateHiddenStyles();
        new Notice(this.settings.isRevealMode ? "Chế độ: Hiện file ẩn" : "Chế độ: Ẩn file");
    }

    updateRibbonVisuals() {
        if (!this.ribbonIconEl) return;
        const iconName = this.settings.isRevealMode ? "eye" : "eye-off";
        const tooltip = this.settings.isRevealMode ? "Đang hiện file ẩn (Click để ẩn lại)" : "Đang ẩn file (Click để xem)";
        setIcon(this.ribbonIconEl, iconName);
        this.ribbonIconEl.setAttribute("aria-label", tooltip);
    }

    // --- LOGIC CSS ---

    updateHiddenStyles() {
        if (!this.styleEl) return;

        if (this.settings.isRevealMode) {
            // Chế độ hiện: Giảm opacity để phân biệt
            const cssForOpacity = this.settings.hiddenPaths.map(path => {
                const safePath = CSS.escape(path);
                return `
                    .nav-file-title[data-path="${safePath}"],
                    .nav-folder-title[data-path="${safePath}"] {
                        opacity: 0.5;
                        filter: grayscale(100%);
                    }
                `;
            }).join(" ");
            this.styleEl.innerText = cssForOpacity;
            return;
        }

        // Chế độ ẩn: Display none
        const cssRules = this.settings.hiddenPaths.map(path => {
            const safePath = CSS.escape(path);
            return `
                .nav-file-title[data-path="${safePath}"],
                .nav-folder-title[data-path="${safePath}"] {
                    display: none !important;
                }
            `;
        }).join("\n");

        this.styleEl.innerText = cssRules;
    }

    async loadSettings() {
        this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
    }

    async saveSettings() {
        await this.saveData(this.settings);
    }
};