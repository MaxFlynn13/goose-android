package io.github.gooseandroid

import android.content.Intent
import android.service.quicksettings.TileService

class GooseQuickSettingsTile : TileService() {
    
    override fun onClick() {
        super.onClick()
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "new_chat")
        }
        
        startActivityAndCollapse(intent)
    }
    
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = "Goose"
            tile.subtitle = "AI Assistant"
            tile.updateTile()
        }
    }
}
