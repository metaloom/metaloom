const { app, BrowserWindow, protocol, net } = require('electron')
const path = require('node:path')
const { pathToFileURL } = require('node:url')

const UI_DIR = path.join(__dirname, 'ui')

// Register the custom protocol scheme before app is ready
protocol.registerSchemesAsPrivileged([
    {
        scheme: 'app',
        privileges: {
            standard: true,
            secure: true,
            supportFetchAPI: true,
            corsEnabled: true,
        },
    },
])

let win;

function createWindow() {
    // Create the browser window
    win = new BrowserWindow({
        width: 1280,
        height: 900,
        backgroundColor: '#0d0e11',
        icon: path.join(UI_DIR, 'img', 'logo_picto.png'),
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
        }
    })

    // Load the loom-ui via custom protocol
    win.loadURL('app://loom/index.html')

    win.on('close', function () {
        win = null
    })
}

// Create window on electron initialization
app.whenReady().then(() => {
    // Handle the custom 'app://' protocol – serve files from ui/ directory
    // with SPA fallback to index.html for unknown paths
    protocol.handle('app', (request) => {
        const url = new URL(request.url)
        let filePath = path.join(UI_DIR, decodeURIComponent(url.pathname))

        // Serve index.html for SPA routes (paths without file extension)
        if (!path.extname(filePath)) {
            filePath = path.join(UI_DIR, 'index.html')
        }

        return net.fetch(pathToFileURL(filePath).href)
    })

    createWindow()
    app.on('activate', () => {
        // macOS specific close process
        if (BrowserWindow.getAllWindows().length === 0) {
            createWindow()
        }
    })
})

//Quit when all windows are closed
app.on('window-all-closed', function () {

    // On macOS specific close process
    if (process.platform !== 'darwin') {
        app.quit()
    }
})
