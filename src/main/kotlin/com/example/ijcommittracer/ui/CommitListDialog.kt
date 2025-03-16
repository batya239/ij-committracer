package com.example.ijcommittracer.ui

import com.example.ijcommittracer.CommitTracerBundle
import com.example.ijcommittracer.actions.ListCommitsAction.AuthorStats
import com.example.ijcommittracer.actions.ListCommitsAction.CommitInfo
import com.example.ijcommittracer.services.NotificationService
import com.example.ijcommittracer.ui.components.AuthorsPanel
import com.example.ijcommittracer.ui.components.CommitsPanel
import com.example.ijcommittracer.ui.components.DateFilterPanel
import com.example.ijcommittracer.ui.models.AuthorTableModel
import com.example.ijcommittracer.ui.models.CommitTableModel
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for displaying a list of Git commits with date filtering and author aggregation.
 */
class CommitListDialog(
    private val project: Project,
    private val commits: List<CommitInfo>,
    private val authorStats: Map<String, AuthorStats>,
    private var fromDate: Date,
    private var toDate: Date,
    private val repository: GitRepository
) : DialogWrapper(project) {

    private lateinit var commitsPanel: CommitsPanel
    private lateinit var authorsPanel: AuthorsPanel
    private lateinit var dateFilterPanel: DateFilterPanel
    private var filteredCommits: List<CommitInfo> = commits
    
    // Use a fixed format with Locale.US for Git command date parameters
    private val gitDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    
    // Other date formatters with consistent Locale.US
    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateTimeFormat = SimpleDateFormat("dd/MM/yy, HH:mm", Locale.US)
    
    // Pattern for YouTrack ticket references
    // Matches project code in capital letters, followed by a hyphen, followed by numbers (e.g. IDEA-12345)
    private val youtrackTicketPattern = Pattern.compile("([A-Z]+-\\d+)")

    init {
        title = CommitTracerBundle.message("dialog.commits.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = BorderLayoutPanel()
        panel.preferredSize = Dimension(1000, 600)
        
        // Add repository name label
        val repoName = if (commits.isNotEmpty()) commits.first().repositoryName else repository.root.name
        val repoLabel = JBLabel(CommitTracerBundle.message("dialog.repository.label", repoName))
        repoLabel.border = JBUI.Borders.empty(0, 5, 5, 0)
        
        // Create date filter panel
        dateFilterPanel = DateFilterPanel(fromDate, toDate, this::applyDateFilter)
        
        // Header panel with repository info and filter controls
        val headerPanel = JPanel(BorderLayout())
        headerPanel.add(repoLabel, BorderLayout.WEST)
        headerPanel.add(dateFilterPanel, BorderLayout.EAST)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Create tabbed pane for different views
        val tabbedPane = JBTabbedPane()
        
        // Initialize panels
        authorsPanel = AuthorsPanel(authorStats, filteredCommits)
        commitsPanel = CommitsPanel(filteredCommits)
        
        // Add authors tab first
        tabbedPane.addTab(CommitTracerBundle.message("dialog.tab.by.author"), authorsPanel)
        
        // Add commits tab second
        tabbedPane.addTab(CommitTracerBundle.message("dialog.tab.all.commits"), commitsPanel)
        
        panel.add(tabbedPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun applyDateFilter(newFromDate: Date, newToDate: Date) {
        if (newFromDate.after(newToDate)) {
            NotificationService.showWarning(
                project, 
                CommitTracerBundle.message("notification.invalid.dates"),
                "Commit Tracer"
            )
            return
        }
        
        // Update date range and refresh commits
        fromDate = newFromDate
        toDate = newToDate
        
        refreshCommitsWithDateFilter()
    }
    
    private fun refreshCommitsWithDateFilter() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, 
            CommitTracerBundle.message("task.filtering.commits"), 
            true
        ) {
            private var newCommits: List<CommitInfo> = emptyList()
            private var newAuthorStats: Map<String, AuthorStats> = emptyMap()
            
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                
                // Format dates for Git command
                val afterParam = "--after=${gitDateFormat.format(fromDate)}"
                val beforeParam = "--before=${gitDateFormat.format(toDate)} 23:59:59"
                
                // Get commit history with date range filtering
                val gitCommits = GitHistoryUtils.history(
                    project,
                    repository.root,
                    afterParam,
                    beforeParam,
                )
                
                val currentBranch = repository.currentBranch?.name ?: "HEAD"
                
                // Convert to CommitInfo objects
                newCommits = gitCommits.map { gitCommit ->
                    val commitDate = Date(gitCommit.authorTime)
                    CommitInfo(
                        hash = gitCommit.id.toString(),
                        author = gitCommit.author.email,
                        date = displayDateTimeFormat.format(commitDate),
                        dateObj = commitDate,
                        message = gitCommit.fullMessage.trim(),
                        repositoryName = repository.root.name,
                        branches = listOfNotNull(currentBranch).takeIf { true } ?: emptyList()
                    )
                }
                
                // Aggregate by author
                val authorMap = mutableMapOf<String, AuthorStats>()
                
                newCommits.forEach { commit ->
                    val author = commit.author
                    
                    // Extract YouTrack tickets from the commit message
                    val tickets = extractYouTrackTickets(commit.message)
                    
                    val stats = authorMap.getOrPut(author) { 
                        AuthorStats(
                            author = author,
                            commitCount = 0,
                            firstCommitDate = commit.dateObj,
                            lastCommitDate = commit.dateObj,
                            youTrackTickets = mutableMapOf()
                        )
                    }
                    
                    // Update YouTrack tickets map
                    val updatedTickets = stats.youTrackTickets.toMutableMap()
                    tickets.forEach { ticket ->
                        val ticketCommits = updatedTickets.getOrPut(ticket) { mutableListOf() }
                        ticketCommits.add(commit)
                    }
                    
                    val updatedStats = stats.copy(
                        commitCount = stats.commitCount + 1,
                        firstCommitDate = if (commit.dateObj.before(stats.firstCommitDate)) commit.dateObj else stats.firstCommitDate,
                        lastCommitDate = if (commit.dateObj.after(stats.lastCommitDate)) commit.dateObj else stats.lastCommitDate,
                        youTrackTickets = updatedTickets
                    )
                    
                    authorMap[author] = updatedStats
                }
                
                newAuthorStats = authorMap
            }
            
            override fun onSuccess() {
                filteredCommits = newCommits
                
                // Update UI components with new data
                commitsPanel.updateData(filteredCommits)
                authorsPanel.updateData(newAuthorStats.values.toList())
                
                NotificationService.showInfo(
                    project, 
                    CommitTracerBundle.message("notification.filter.applied", filteredCommits.size),
                    "Commit Tracer"
                )
            }
        })
    }
    
    /**
     * Extract YouTrack ticket IDs from commit message
     */
    private fun extractYouTrackTickets(message: String): Set<String> {
        val matcher = youtrackTicketPattern.matcher(message)
        val tickets = mutableSetOf<String>()
        
        while (matcher.find()) {
            tickets.add(matcher.group(1))
        }
        
        return tickets
    }
}