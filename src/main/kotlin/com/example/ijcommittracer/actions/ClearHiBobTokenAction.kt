package com.example.ijcommittracer.actions

import com.example.ijcommittracer.services.HiBobApiService
import com.example.ijcommittracer.services.NotificationService
import com.example.ijcommittracer.services.TokenStorageService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/**
 * Action to clear the HiBob API token and cached data.
 * Uses the TokenStorageService to securely clear credentials.
 */
class ClearHiBobTokenAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // Ask for confirmation
        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to clear the HiBob API token?",
            "Clear HiBob Token",
            "Yes, Clear Token",
            "Cancel",
            Messages.getQuestionIcon()
        )
        
        if (result != Messages.YES) {
            return
        }
        
        // Clear tokens in a background thread to avoid UI freezes
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Clear token from secure storage
            val tokenStorage = TokenStorageService.getInstance(project)
            tokenStorage.clearHiBobToken()
            
            // Also clear the cache
            val hibobService = HiBobApiService.getInstance(project)
            hibobService.clearCache()
            
            // Switch to UI thread to show notification
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                NotificationService.showInfo(
                    project,
                    "HiBob API token cleared and cache invalidated",
                    "Commit Tracer"
                )
            }
        }
    }
}