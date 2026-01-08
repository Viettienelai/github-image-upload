const { Plugin, TFolder } = require('obsidian');

const DEFAULT_SETTINGS = {
    itemOrders: {} 
}

module.exports = class UltimateOrderPlugin extends Plugin {
    async onload() {
        console.log('>>> LOADING ULTIMATE ORDER PLUGIN (WITH TOP/BOTTOM) <<<');
        await this.loadSettings();
        
        // 1. Inject CSS
        this.addStyle();

        // 2. Đăng ký Menu
        this.registerEvent(
            this.app.workspace.on("file-menu", (menu, file) => {
                if (file && file.parent) {
                    this.addContextMenu(menu, file);
                }
            })
        );

        // 3. Logic chạy sau khi layout ready
        this.app.workspace.onLayoutReady(() => {
            // Dọn rác khi khởi động
            this.garbageCollect();
            
            // Apply order
            this.applyOrders();
            this.registerDomObserver();
            
            // Event Rename
            this.registerEvent(this.app.vault.on("rename", (file, oldPath) => {
                if (this.settings.itemOrders[oldPath] !== undefined) {
                    this.settings.itemOrders[file.path] = this.settings.itemOrders[oldPath];
                    delete this.settings.itemOrders[oldPath];
                    this.saveSettings();
                }
            }));

            // Event Delete
            this.registerEvent(this.app.vault.on("delete", async (file) => {
                if (this.settings.itemOrders[file.path] !== undefined) {
                    delete this.settings.itemOrders[file.path];
                    await this.saveSettings();
                }
                if (file.parent) {
                    setTimeout(() => this.revalidateFolder(file.parent), 200);
                }
            }));
        });
    }

    async loadSettings() {
        this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
    }

    async saveSettings() {
        await this.saveData(this.settings);
        this.applyOrders();
    }

    // --- GARBAGE COLLECTION ---
    async garbageCollect() {
        let dirty = false;
        for (const path in this.settings.itemOrders) {
            const file = this.app.vault.getAbstractFileByPath(path);
            if (!file) {
                // console.log(`[GC] Removing: ${path}`);
                delete this.settings.itemOrders[path];
                dirty = true;
            }
        }
        if (dirty) await this.saveSettings();
    }

    // --- CORE LOGIC ---

    getSortedSiblings(siblings) {
        return siblings.map(item => {
            let currentOrder = this.settings.itemOrders[item.path];
            if (currentOrder === undefined) {
                const typeScore = (item instanceof TFolder) ? 0 : 1; 
                currentOrder = 999999 + typeScore; 
            }
            return {
                item: item,
                path: item.path,
                savedOrder: this.settings.itemOrders[item.path],
                name: item.name,
                isFolder: item instanceof TFolder
            };
        }).sort((a, b) => {
            if (a.savedOrder !== undefined && b.savedOrder !== undefined) return a.savedOrder - b.savedOrder;
            if (a.savedOrder !== undefined) return -1;
            if (b.savedOrder !== undefined) return 1;
            if (a.isFolder !== b.isFolder) return a.isFolder ? -1 : 1;
            return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
        });
    }

    async reorderItem(targetItem, moveAmount) {
        const parent = targetItem.parent;
        const siblingList = this.getSortedSiblings(parent.children);

        const currentIndex = siblingList.findIndex(x => x.path === targetItem.path);
        if (currentIndex === -1) return;

        let newIndex = currentIndex + moveAmount;
        // Logic kẹp (Clamp) sẽ xử lý việc Move to Top/Bottom
        if (newIndex < 0) newIndex = 0;
        if (newIndex >= siblingList.length) newIndex = siblingList.length - 1;

        if (newIndex === currentIndex) return;

        const itemToMove = siblingList[currentIndex];
        siblingList.splice(currentIndex, 1);
        siblingList.splice(newIndex, 0, itemToMove);

        siblingList.forEach((entry, index) => {
            this.settings.itemOrders[entry.path] = index;
        });

        this.optimizeData(siblingList);
        await this.saveSettings();
    }

    async revalidateFolder(parentFolder) {
        const siblings = parentFolder.children;
        const siblingList = this.getSortedSiblings(siblings);
        if (this.optimizeData(siblingList)) {
            await this.saveSettings();
        }
    }

    optimizeData(currentSortedList) {
        let dataChanged = false;

        // Rule: Đứng 1 mình -> Xóa
        if (currentSortedList.length <= 1) {
            currentSortedList.forEach(entry => {
                if (this.settings.itemOrders[entry.path] !== undefined) {
                    delete this.settings.itemOrders[entry.path];
                    dataChanged = true;
                }
            });
            return dataChanged;
        }

        // Rule: Trùng Default -> Xóa
        const defaultSortedList = [...currentSortedList].sort((a, b) => {
            if (a.isFolder !== b.isFolder) return a.isFolder ? -1 : 1;
            return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
        });

        let isMatchDefault = true;
        for (let i = 0; i < currentSortedList.length; i++) {
            if (currentSortedList[i].path !== defaultSortedList[i].path) {
                isMatchDefault = false;
                break;
            }
        }

        if (isMatchDefault) {
            currentSortedList.forEach(entry => {
                if (this.settings.itemOrders[entry.path] !== undefined) {
                    delete this.settings.itemOrders[entry.path];
                    dataChanged = true;
                }
            });
        }

        return dataChanged;
    }

    // --- CONTEXT MENU (CẬP NHẬT MỚI) ---
    addContextMenu(menu, file) {
        menu.addItem((item) => {
            item.setTitle("Change Position")
                .setIcon("sort-asc")
                .setSection("action")
                .setSubmenu(); 

            if (item.setSubmenu) {
                const subMenu = item.setSubmenu();
                
                // --- NHÓM LÊN ---
                // Move to Top: Dùng số âm cực lớn
                subMenu.addItem((i) => i.setTitle("Move to Top (↑ Top)")
                    .setIcon("arrow-up-circle") // Icon hình tròn có mũi tên lên
                    .onClick(() => this.reorderItem(file, -999999)));

                subMenu.addItem((i) => i.setTitle("Move Up 1 (↑)")
                    .setIcon("arrow-up")
                    .onClick(() => this.reorderItem(file, -1)));

                subMenu.addItem((i) => i.setTitle("Move Up 2 (↑↑)")
                    .setIcon("chevrons-up")
                    .onClick(() => this.reorderItem(file, -2)));

                subMenu.addSeparator();

                // --- NHÓM XUỐNG ---
                subMenu.addItem((i) => i.setTitle("Move Down 1 (↓)")
                    .setIcon("arrow-down")
                    .onClick(() => this.reorderItem(file, 1)));

                subMenu.addItem((i) => i.setTitle("Move Down 2 (↓↓)")
                    .setIcon("chevrons-down")
                    .onClick(() => this.reorderItem(file, 2)));

                // Move to Bottom: Dùng số dương cực lớn
                subMenu.addItem((i) => i.setTitle("Move to Bottom (↓ Bottom)")
                    .setIcon("arrow-down-circle") // Icon hình tròn có mũi tên xuống
                    .onClick(() => this.reorderItem(file, 999999)));
            }
        });
    }

    // --- CSS & DOM ---
    addStyle() {
        const existingStyle = document.getElementById('ultimate-order-css');
        if (existingStyle) existingStyle.remove();
        const style = document.createElement('style');
        style.id = 'ultimate-order-css';
        style.innerText = `.nav-files-container, .tree-item-children { display: flex !important; flex-direction: column !important; } .tree-item { order: 0; transition: order 0.2s; }`;
        document.head.appendChild(style);
    }

    applyOrders() {
        const fileExplorer = this.app.workspace.getLeavesOfType('file-explorer')[0];
        if (!fileExplorer) return;
        const items = fileExplorer.view.containerEl.querySelectorAll('.tree-item');
        items.forEach(item => {
            let path = item.getAttribute('data-path');
            if (!path) { const s = item.querySelector('.tree-item-self'); if (s) path = s.getAttribute('data-path'); }
            
            if (path && this.settings.itemOrders.hasOwnProperty(path)) {
                item.style.setProperty('order', this.settings.itemOrders[path], 'important');
            } else {
                item.style.order = 999999; 
            }
        });
    }

    registerDomObserver() {
        const observer = new MutationObserver(() => this.applyOrders());
        const leaf = this.app.workspace.getLeavesOfType('file-explorer')[0];
        if (leaf) observer.observe(leaf.view.containerEl, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'data-path'] });
    }

    onunload() {
        const style = document.getElementById('ultimate-order-css');
        if (style) style.remove();
    }
}