# GitHub Image Upload (Obsidian Plugin)

**GitHub Image Upload** is a high-performance Obsidian plugin designed to streamline your image workflow. It automatically uploads images to GitHub and serves them via the lightning-fast **jsDelivr CDN**, keeping your local vault lightweight and your images globally accessible.

## ✨ Key Features

* **🚀 Rocket CDN Hosting:** Automatically generates [jsDelivr](https://www.jsdelivr.net/) links for every upload, ensuring your images load instantly.
* **📅 Smart Organization:** Categorizes images into hierarchical folders by **Year/Month** (e.g., `assets/2025/12/20251224_153000.png`).
* **📱 Mobile & Desktop Ready:** 
	* **Desktop:** Seamlessly handles Clipboard Paste and Drag & Drop.
    * **Mobile:** Provides a dedicated Ribbon icon and Mobile Toolbar command to upload directly from your gallery.
* **🧹 Garbage Collection:** A powerful "Cleanup" command scans your entire vault to identify and delete "orphan" images on GitHub that are no longer linked in any notes.
* **🗑️ Instant Delete:** Right-click any GitHub image link in your editor to permanently remove it from both the cloud and your note.
* **📊 Storage Insights:** Quickly check your total GitHub image storage usage (count and size) directly from the Command Palette.

## 🛠️ Installation

### Manual Installation
1. Download `main.js` and `manifest.json`.
2. Place it into your vault's `.obsidian/plugins/github-image-upload` directory.
3. Open **Obsidian Settings** > **Community Plugins** > **Reload**.
4. Enable **GitHub Image Master**.

## ⚙️ Configuration
Go to the plugin settings and provide:
1. **GitHub Token:** A [Personal Access Token](https://github.com/settings/tokens) with `repo` scope.
2. **Repository:** Your `username/repo-name` (e.g., `TechUser/MyPhotos`).
3. **Branch:** Usually `main`.
4. **Root Folder:** The base directory for images (e.g., `assets`).

## 📖 How to Use

### Uploading
* **Desktop:** Simply **Paste** (`Ctrl + V`) or **Drag & Drop** an image into the editor.
* **Mobile:** Tap the **Image Plus** icon in the ribbon or use the `GitHub: Select image from device and upload` command.

### Management (Command Palette `Ctrl + P`)
* **Delete:** Right-click an image link -> `🗑️ Delete this image from GitHub`.
* **Cleanup:** Run `GitHub: Clean up unused images (Full scan)`.
* **Check Size:** Run `GitHub: Check image storage usage`.
