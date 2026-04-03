package com.hunkwise.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class HunkwiseToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Review", false)
        toolWindow.contentManager.addContent(content)
    }
}
