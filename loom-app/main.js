const { app, BrowserWindow } = require('electron')
const path = require('node:path')

let win;

function createWindow() {
    //Create the browser window
    win = new BrowserWindow({
        width: 600,
        height: 600,
        backgroundColor: '#ffffff',
        //icon: 'file:///' + __dirname + '/dist/assets/favicon.ico'
        webPreferences: {
            preload: path.join(__dirname, 'preload.js')
        }
    })

    win.loadURL('file:///' + __dirname + '/index.html')
    win.on('close', function () {
        win = null
    })
}

// Create window on electron intialization
//app.on('ready', createWindow)
app.whenReady().then(() => {
    createWindow()
    app.on('activate', () => {
        //macOS specific close process
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
