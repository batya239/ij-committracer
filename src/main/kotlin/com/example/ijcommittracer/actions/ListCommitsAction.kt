package com.example.ijcommittracer.actions

import com.example.ijcommittracer.CommitTracerBundle
import com.example.ijcommittracer.services.NotificationService
import com.example.ijcommittracer.ui.CommitListDialog
import com.example.ijcommittracer.ui.SelectRepositoryDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Action that lists all commits in the current Git repository.
 */
class ListCommitsAction : AnAction(), DumbAware {


    private lateinit var project: Project

    override fun actionPerformed(e: AnActionEvent) {
        project = e.project ?: return
        
        // Get available Git repositories
        val repositories = getRepositories(project)
        if (repositories.isEmpty()) {
            NotificationService.showWarning(
                project,
                CommitTracerBundle.message("notification.no.repositories"),
                "Commit Tracer"
            )
            return
        }
        
        // If multiple repositories, ask the user to select one
        val selectedRepository = if (repositories.size > 1) {
            val dialog = SelectRepositoryDialog(project, repositories)
            if (!dialog.showAndGet()) {
                return // User canceled
            }
            dialog.getSelectedRepository()
        } else {
            repositories.first()
        }
        
        // Get default date range (today to 365 days ago)
        val today = Calendar.getInstance()
        val oneYearAgo = Calendar.getInstance()
        oneYearAgo.add(Calendar.MONTH, -1)

        // Load commits from the selected repository with date range
        loadCommits(project, selectedRepository, oneYearAgo.time, today.time)
    }
    
    private fun loadCommits(project: Project, repository: GitRepository, fromDate: Date, toDate: Date) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, 
            CommitTracerBundle.message("task.loading.commits"), 
            true
        ) {
            private var commits: List<CommitInfo> = emptyList()
            private var authorStats: Map<String, AuthorStats> = emptyMap()
            private var error: Exception? = null
            
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = CommitTracerBundle.message("task.fetching.commits")
                    indicator.text2 = CommitTracerBundle.message("task.repository.label", repository.root.name)
                    commits = getCommits(project, repository, fromDate, toDate)
                    authorStats = aggregateByAuthor(commits)
                } catch (ex: Exception) {
                    error = ex
                }
            }
            
            override fun onSuccess() {
                if (error != null) {
                    NotificationService.showError(
                        project,
                        CommitTracerBundle.message("notification.error.loading", error?.message.orEmpty()),
                        "Commit Tracer"
                    )
                    return
                }
                
                if (commits.isEmpty()) {
                    NotificationService.showInfo(
                        project,
                        CommitTracerBundle.message("notification.no.commits"),
                        "Commit Tracer"
                    )
                    return
                }
                
                NotificationService.showInfo(
                    project, 
                    CommitTracerBundle.message("notification.commits.loaded") + 
                            " (${commits.size} commits from ${authorStats.size} authors)",
                    "Commit Tracer"
                )
                
                // Show the commits in a dialog with date filtering support
                val dialog = CommitListDialog(
                    project,
                    commits,
                    authorStats,
                    fromDate,
                    toDate,
                    repository
                )
                dialog.show()
            }
        })
    }



    companion object {
        // Pattern for YouTrack ticket references
        // Matches project code in capital letters, followed by a hyphen, followed by numbers (e.g. IDEA-12345)
        private val youtrackTicketPattern = Regex("\\b(?!MR-|CR-|EA-[A-Z]+-\\d+)[A-Z]+-\\d+\\b")

        /**
         * Extracts YouTrack ticket IDs from commit message
         */
        private fun extractYouTrackTickets(message: String): Set<String> {
            return youtrackTicketPattern.findAll(message).map { it.value }.toSet()
        }
    }

    /**
     * Aggregates commit information by author
     */
    private fun aggregateByAuthor(commits: List<CommitInfo>): Map<String, AuthorStats> {
        val project = this.project
        val authorMap = mutableMapOf<String, AuthorStats>()

        commits.forEach { commit ->
            val author = commit.author
            val tickets = extractYouTrackTickets(commit.message)

            val stats = authorMap.getOrPut(author) {
                AuthorStats(
                    author = author,
                    commitCount = 0,
                    firstCommitDate = commit.dateObj,
                    lastCommitDate = commit.dateObj,
                    youTrackTickets = mutableMapOf(),
                    blockerTickets = mutableMapOf(),
                    regressionTickets = mutableMapOf()
                )
            }

            // Update ticket tracking
            val updatedTickets = stats.youTrackTickets.toMutableMap()
            val updatedBlockerTickets = stats.blockerTickets.toMutableMap()
            val updatedRegressionTickets = stats.regressionTickets.toMutableMap()
            
            tickets.forEach { ticket ->
                val ticketCommits = updatedTickets.getOrPut(ticket) { mutableListOf() }
                ticketCommits.add(commit)
                
                // Check if this ticket is a blocker or regression by fetching ticket info from YouTrack
                val ticketInfo = project.getService(com.example.ijcommittracer.services.YouTrackApiService::class.java).fetchTicketInfo(ticket)
                if (ticketInfo != null) {
                    // Check for blocker tags
                    if (ticketInfo.tags.any { tag -> tag.startsWith("blocking-") }) {
                        val blockerCommits = updatedBlockerTickets.getOrPut(ticket) { mutableListOf() }
                        blockerCommits.add(commit)
                    }
                    
                    // Check for regression in tags or summary (case insensitive)
                    if (ticketInfo.tags.any { tag -> tag.lowercase().contains("regression") } || 
                        ticketInfo.summary.lowercase().contains("regression")) {
                        val regressionCommits = updatedRegressionTickets.getOrPut(ticket) { mutableListOf() }
                        regressionCommits.add(commit)
                    }
                }
            }

            val updatedStats = stats.copy(
                commitCount = stats.commitCount + 1,
                firstCommitDate = if (commit.dateObj.before(stats.firstCommitDate)) commit.dateObj else stats.firstCommitDate,
                lastCommitDate = if (commit.dateObj.after(stats.lastCommitDate)) commit.dateObj else stats.lastCommitDate,
                youTrackTickets = updatedTickets,
                blockerTickets = updatedBlockerTickets,
                regressionTickets = updatedRegressionTickets
            )

            authorMap[author] = updatedStats
        }

        return authorMap
    }

    override fun update(e: AnActionEvent) {
        // Enable the action only if a project is open and has Git VCS
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && hasGitRepository(project)
    }
    
    private fun hasGitRepository(project: Project): Boolean {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        return vcsManager.allVcsRoots.isNotEmpty() && 
               GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }
    
    private fun getRepositories(project: Project): List<GitRepository> {
        return GitRepositoryManager.getInstance(project).repositories
    }
    
    /**
     * Checks if any of the changed files in a commit are test files.
     */
    private fun commitModifiesTestFiles(project: Project, gitCommit: GitCommit): Boolean {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val root = project.guessProjectDir()
        
        // Check each changed file in the commit
        return gitCommit.changes.any { change ->
            val testPath = when {
                change.afterRevision != null -> {
                    val filePath = change.afterRevision?.file ?: return@any false
                    val virtualFile = root?.findFileByRelativePath(filePath.path)
                    if (virtualFile != null && virtualFile.isValid) {
                        // Use readAction to safely access isInTestSourceContent
                        ApplicationManager.getApplication().runReadAction<Boolean> {
                            fileIndex.isInTestSourceContent(virtualFile)
                        }
                    } else {
                        // Fallback to path-based detection if we can't get the VirtualFile
                        filePath.path.contains("/test/")
                    }
                }
                change.beforeRevision != null -> {
                    val filePath = change.beforeRevision?.file  ?: return@any false
                    val virtualFile = root?.findFileByRelativePath(filePath.path)
                    if (virtualFile != null && virtualFile.isValid) {
                        // Use readAction to safely access isInTestSourceContent
                        ApplicationManager.getApplication().runReadAction<Boolean> {
                            fileIndex.isInTestSourceContent(virtualFile)
                        }
                    } else {
                        // Fallback to path-based detection if we can't get the VirtualFile
                        filePath.path.contains("/test/")
                    }
                }
                else -> false
            }
            
            testPath
        }
    }
    
    private fun getCommits(
        project: Project,
        repository: GitRepository,
        fromDate: Date,
        toDate: Date
    ): List<CommitInfo> {
        // Create date range parameters for git log - using consistent Locale.US
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val afterParam = "--after=${dateFormat.format(fromDate)}"
        val beforeParam = "--before=${dateFormat.format(toDate)} 23:59:59"

        // Get commit history with date range filtering
        val commits = GitHistoryUtils.history(
            project,
            repository.root,
            afterParam,
            beforeParam,
        )
        
        // Use consistent Locale.US for date formatting
        val displayDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        
        // Get current branch name
        val currentBranch = repository.currentBranch?.name ?: "HEAD"
        
        return commits.map { gitCommit ->
            val commitDate = Date(gitCommit.authorTime)
            val modifiesTests = commitModifiesTestFiles(project, gitCommit)
            
            CommitInfo(
                hash = gitCommit.id.toString(),
                author = gitCommit.author.email,
                date = displayDateFormat.format(commitDate),
                dateObj = commitDate, // Store actual Date object for sorting
                message = gitCommit.fullMessage.trim(),
                repositoryName = repository.root.name,
                branches = listOfNotNull(currentBranch).takeIf { isCommitInCurrentBranch(gitCommit, repository) } ?: emptyList(),
                modifiesTests = modifiesTests
            )
        }
    }
    
    /**
     * Checks if a commit is part of the current branch.
     */
    private fun isCommitInCurrentBranch(commit: GitCommit, repository: GitRepository): Boolean {
        val currentBranch = repository.currentBranch ?: return false
        
        // Simplified check - all commits loaded with git log are part of current branch
        // A more accurate check would require running additional git commands
        return true
    }
    
    /**
     * Data class representing a Git commit.
     */
    data class CommitInfo(
        val hash: String,
        val author: String,
        val date: String,
        val dateObj: Date, // For internal use in filtering and sorting
        val message: String,
        val repositoryName: String,
        val branches: List<String> = emptyList(),
        val modifiesTests: Boolean = false
    )

    /**
     * Data class representing aggregated stats for an author.
     */
    data class AuthorStats(
        val author: String,
        val commitCount: Int,
        val firstCommitDate: Date,
        val lastCommitDate: Date,
        val youTrackTickets: Map<String, MutableList<CommitInfo>> = emptyMap(),
        val blockerTickets: Map<String, MutableList<CommitInfo>> = emptyMap(),
        val regressionTickets: Map<String, MutableList<CommitInfo>> = emptyMap()
    ) {
        /**
         * Get active days between first and last commit.
         */
        fun getActiveDays(): Long {
            val diffInMillis = lastCommitDate.time - firstCommitDate.time
            return TimeUnit.DAYS.convert(diffInMillis, TimeUnit.MILLISECONDS) + 1
        }

        /**
         * Calculate average commits per active day.
         */
        fun getCommitsPerDay(): Double {
            val days = getActiveDays()
            return if (days > 0) commitCount.toDouble() / days else 0.0
        }
        
        /**
         * Get count of blocker tickets.
         */
        fun getBlockerCount(): Int {
            return blockerTickets.size
        }
        
        /**
         * Get count of regression tickets.
         */
        fun getRegressionCount(): Int {
            return regressionTickets.size
        }
    }
}
