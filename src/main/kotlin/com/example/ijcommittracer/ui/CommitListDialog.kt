package com.example.ijcommittracer.ui

import com.example.ijcommittracer.CommitTracerBundle
import com.example.ijcommittracer.actions.ListCommitsAction.CommitInfo
import com.example.ijcommittracer.actions.ListCommitsAction.AuthorStats
import com.example.ijcommittracer.services.NotificationService
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import git4idea.repo.GitRepository
import git4idea.history.GitHistoryUtils
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

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

    private lateinit var commitsTable: JBTable
    private lateinit var authorsTable: JBTable
    private lateinit var fromDatePicker: JDateChooser
    private lateinit var toDatePicker: JDateChooser
    private lateinit var filterButton: JButton
    private var filteredCommits: List<CommitInfo> = commits
    
    // Use a fixed format with Locale.US for Git command date parameters
    private val gitDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    
    // Other date formatters with consistent Locale.US
    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    
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
        val filterPanel = createDateFilterPanel()
        
        // Header panel with repository info and filter controls
        val headerPanel = JPanel(BorderLayout())
        headerPanel.add(repoLabel, BorderLayout.WEST)
        headerPanel.add(filterPanel, BorderLayout.EAST)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Create tabbed pane for different views
        val tabbedPane = JBTabbedPane()
        
        // Add commits tab
        val commitsPanel = createCommitsPanel()
        tabbedPane.addTab(CommitTracerBundle.message("dialog.tab.all.commits"), commitsPanel)
        
        // Add authors tab
        val authorsPanel = createAuthorsPanel()
        tabbedPane.addTab(CommitTracerBundle.message("dialog.tab.by.author"), authorsPanel)
        
        panel.add(tabbedPane, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createDateFilterPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
        
        panel.add(JBLabel(CommitTracerBundle.message("dialog.filter.from")))
        fromDatePicker = JDateChooser(fromDate, displayDateFormat)
        fromDatePicker.preferredSize = Dimension(120, 30)
        panel.add(fromDatePicker)
        
        panel.add(JBLabel(CommitTracerBundle.message("dialog.filter.to")))
        toDatePicker = JDateChooser(toDate, displayDateFormat)
        toDatePicker.preferredSize = Dimension(120, 30)
        panel.add(toDatePicker)
        
        filterButton = JButton(CommitTracerBundle.message("dialog.filter.apply"))
        filterButton.addActionListener { applyDateFilter() }
        panel.add(filterButton)
        
        return panel
    }
    
    private fun applyDateFilter() {
        val newFromDate = fromDatePicker.date
        val newToDate = toDatePicker.date
        
        if (newFromDate != null && newToDate != null) {
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
        } else {
            NotificationService.showWarning(
                project, 
                CommitTracerBundle.message("notification.invalid.dates"),
                "Commit Tracer"
            )
        }
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
                
                // Update tables
                (commitsTable.model as CommitTableModel).updateData(filteredCommits)
                (authorsTable.model as AuthorTableModel).updateData(newAuthorStats.values.toList())
                
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
    
    private fun createCommitsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Create table with commits
        val tableModel = CommitTableModel(filteredCommits)
        commitsTable = JBTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            columnModel.getColumn(0).preferredWidth = 80  // Hash
            columnModel.getColumn(1).preferredWidth = 150 // Author
            columnModel.getColumn(2).preferredWidth = 150 // Date
            columnModel.getColumn(3).preferredWidth = 60  // Test
            columnModel.getColumn(4).preferredWidth = 400 // Message
            
            // Center-align and use special renderer for the Test column
            val testRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int
                ): Component {
                    val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    text = if (value == true) "Yes" else "No"
                    horizontalAlignment = SwingConstants.CENTER
                    return component
                }
            }
            columnModel.getColumn(3).cellRenderer = testRenderer
            
            // Add row sorter for sorting
            val sorter = TableRowSorter(tableModel)
            // Add correct comparator for the boolean Test column
            sorter.setComparator(3, Comparator.comparing<Any, Boolean> { it as Boolean })
            rowSorter = sorter
        }
        
        // Create detail panel for selected commit
        val detailsPanel = JPanel(BorderLayout())
        val detailsHeaderLabel = JBLabel(CommitTracerBundle.message("dialog.commit.details"))
        detailsHeaderLabel.border = JBUI.Borders.empty(5, 5, 0, 5)
        detailsPanel.add(detailsHeaderLabel, BorderLayout.NORTH)
        
        val detailsArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(5)
        }
        detailsPanel.add(JBScrollPane(detailsArea), BorderLayout.CENTER)
        
        // Add selection listener to show details
        commitsTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedRow = commitsTable.selectedRow
                if (selectedRow >= 0) {
                    val modelRow = commitsTable.convertRowIndexToModel(selectedRow)
                    val commit = filteredCommits[modelRow]
                    
                    // Highlight YouTrack tickets in the commit message
                    val message = highlightYouTrackTickets(commit.message)
                    
                    detailsArea.text = buildString {
                        append(CommitTracerBundle.message("dialog.repository.label", commit.repositoryName))
                        append("\n")
                        append("Commit: ${commit.hash}\n")
                        append("Author: ${commit.author}\n")
                        append("Date: ${commit.date}\n")
                        
                        // Add branches if available
                        if (commit.branches.isNotEmpty()) {
                            append(CommitTracerBundle.message("dialog.branch.label", commit.branches.joinToString(", ")))
                            append("\n")
                        }
                        
                        // Add test changes indicator
                        append(CommitTracerBundle.message("dialog.test.changes", if (commit.modifiesTests) "Yes" else "No"))
                        append("\n")
                        
                        append("\nMessage:\n$message")
                    }
                    detailsArea.caretPosition = 0
                }
            }
        }
        
        // Split view: table on top, details below
        val splitPane = com.intellij.ui.OnePixelSplitter(true, 0.6f)
        splitPane.firstComponent = JBScrollPane(commitsTable)
        splitPane.secondComponent = detailsPanel
        
        panel.add(splitPane, BorderLayout.CENTER)
        
        // Select first row by default if there are commits
        if (filteredCommits.isNotEmpty()) {
            commitsTable.selectionModel.setSelectionInterval(0, 0)
        }
        
        return panel
    }
    
    /**
     * Highlight YouTrack tickets in commit message
     */
    private fun highlightYouTrackTickets(message: String): String {
        val matcher = youtrackTicketPattern.matcher(message)
        return matcher.replaceAll("**$1**") // Bold the ticket references
    }
    
    private fun createAuthorsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Create table with author statistics
        val tableModel = AuthorTableModel(authorStats.values.toList())
        authorsTable = JBTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            columnModel.getColumn(0).preferredWidth = 200 // Author
            columnModel.getColumn(1).preferredWidth = 80  // Commit Count
            columnModel.getColumn(2).preferredWidth = 80  // Tickets Count
            columnModel.getColumn(3).preferredWidth = 80  // Blockers Count
            columnModel.getColumn(4).preferredWidth = 80  // Regressions Count
            columnModel.getColumn(5).preferredWidth = 150 // First Commit
            columnModel.getColumn(6).preferredWidth = 150 // Last Commit
            columnModel.getColumn(7).preferredWidth = 80  // Active Days
            columnModel.getColumn(8).preferredWidth = 120 // Commits/Day
            
            // Center-align numeric columns
            val centerRenderer = DefaultTableCellRenderer()
            centerRenderer.horizontalAlignment = SwingConstants.CENTER
            columnModel.getColumn(1).cellRenderer = centerRenderer // Commit Count
            columnModel.getColumn(2).cellRenderer = centerRenderer // Tickets Count
            columnModel.getColumn(3).cellRenderer = centerRenderer // Blockers Count
            columnModel.getColumn(4).cellRenderer = centerRenderer // Regressions Count
            columnModel.getColumn(7).cellRenderer = centerRenderer // Active Days
            columnModel.getColumn(8).cellRenderer = centerRenderer // Commits/Day
            
            // Add date renderer for date columns to ensure consistent display
            val dateRenderer = DefaultTableCellRenderer()
            dateRenderer.horizontalAlignment = SwingConstants.CENTER
            columnModel.getColumn(5).cellRenderer = dateRenderer // First Commit
            columnModel.getColumn(6).cellRenderer = dateRenderer // Last Commit
            
            // Add row sorter for sorting with appropriate comparators
            val sorter = TableRowSorter(tableModel)
            
            // Make sure numeric columns are sorted as numbers
            sorter.setComparator(1, Comparator.comparingInt<Any> { (it as Number).toInt() }) // Commits
            sorter.setComparator(2, Comparator.comparingInt<Any> { (it as Number).toInt() }) // Tickets Count
            sorter.setComparator(3, Comparator.comparingInt<Any> { (it as Number).toInt() }) // Blockers Count
            sorter.setComparator(4, Comparator.comparingInt<Any> { (it as Number).toInt() }) // Regressions Count
            sorter.setComparator(7, Comparator.comparingLong<Any> { (it as Number).toLong() }) // Active Days
            sorter.setComparator(8, Comparator.comparingDouble<Any> {
                when (it) {
                    is Number -> it.toDouble()
                    is String -> it.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }) // Commits/Day
            
            // Sort by commit count (descending) by default
            sorter.toggleSortOrder(1)
            sorter.toggleSortOrder(1) // Toggle twice to get descending order
            
            rowSorter = sorter
        }
        
        // Create tabbed panel for details
        val authorDetailsTabbedPane = JBTabbedPane()
        
        // Create author details panel
        val authorCommitsPanel = JPanel(BorderLayout())
        authorCommitsPanel.border = JBUI.Borders.empty(5)
        
        val authorCommitsLabel = JBLabel(CommitTracerBundle.message("dialog.author.commits", "", "0"))
        authorCommitsPanel.add(authorCommitsLabel, BorderLayout.NORTH)
        
        val authorCommitsTable = JBTable()
        val authorCommitsScrollPane = JBScrollPane(authorCommitsTable)
        authorCommitsPanel.add(authorCommitsScrollPane, BorderLayout.CENTER)
        
        // Create YouTrack tickets panel
        val ticketsPanel = JPanel(BorderLayout())
        ticketsPanel.border = JBUI.Borders.empty(5)
        
        val ticketsLabel = JBLabel(CommitTracerBundle.message("dialog.youtrack.tickets", "0"))
        ticketsPanel.add(ticketsLabel, BorderLayout.NORTH)
        val ticketsTable = JBTable()
        val ticketsScrollPane = JBScrollPane(ticketsTable)
        ticketsPanel.add(ticketsScrollPane, BorderLayout.CENTER)
        
        // Add tabs to the tabbed pane
        authorDetailsTabbedPane.addTab(CommitTracerBundle.message("dialog.tab.all.commits"), authorCommitsPanel)
        authorDetailsTabbedPane.addTab("YouTrack Tickets", ticketsPanel)
        
        // Add selection listener to show author details
        authorsTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedRow = authorsTable.selectedRow
                if (selectedRow >= 0) {
                    val modelRow = authorsTable.convertRowIndexToModel(selectedRow)
                    val author = authorStats.values.toList()[modelRow]
                    
                    // Filter commits for this author
                    val authorCommits = filteredCommits.filter { it.author == author.author }
                    
                    // Update author commits table
                    val authorCommitsModel = CommitTableModel(authorCommits)
                    authorCommitsTable.model = authorCommitsModel
                    
                    // Configure columns
                    authorCommitsTable.columnModel.getColumn(0).preferredWidth = 80  // Hash
                    authorCommitsTable.columnModel.getColumn(1).preferredWidth = 150 // Author
                    authorCommitsTable.columnModel.getColumn(2).preferredWidth = 150 // Date
                    authorCommitsTable.columnModel.getColumn(3).preferredWidth = 60  // Test
                    authorCommitsTable.columnModel.getColumn(4).preferredWidth = 400 // Message
                    
                    // Custom renderer for the Test column
                    val testRenderer = object : DefaultTableCellRenderer() {
                        override fun getTableCellRendererComponent(
                            table: JTable,
                            value: Any?,
                            isSelected: Boolean,
                            hasFocus: Boolean,
                            row: Int,
                            column: Int
                        ): Component {
                            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                            text = if (value == true) "Yes" else "No"
                            horizontalAlignment = SwingConstants.CENTER
                            return component
                        }
                    }
                    authorCommitsTable.columnModel.getColumn(3).cellRenderer = testRenderer
                    
                    // Add row sorter for author commits table
                    val sorter = TableRowSorter(authorCommitsModel)
                    // Add correct comparator for the boolean Test column
                    sorter.setComparator(3, Comparator.comparing<Any, Boolean> { it as Boolean })
                    authorCommitsTable.rowSorter = sorter
                    
                    authorCommitsLabel.text = CommitTracerBundle.message("dialog.author.commits", author.author, authorCommits.size.toString())
                    
                    // Update tickets table
                    val tickets = author.youTrackTickets.entries
                        .map { (ticket, commits) -> 
                            // Check if this ticket is a blocker or regression
                            val isBlocker = author.blockerTickets.containsKey(ticket)
                            val isRegression = author.regressionTickets.containsKey(ticket)
                            TicketInfo(ticket, commits, isBlocker, isRegression)
                        }
                        .toList()
                    
                    val ticketsModel = TicketsTableModel(tickets)
                    ticketsTable.model = ticketsModel
                    
                    // Configure ticket table columns
                    ticketsTable.columnModel.getColumn(0).preferredWidth = 120  // Ticket ID
                    ticketsTable.columnModel.getColumn(1).preferredWidth = 80   // Commit Count
                    ticketsTable.columnModel.getColumn(2).preferredWidth = 80   // Blocker
                    ticketsTable.columnModel.getColumn(3).preferredWidth = 80   // Regression
                    
                    // Center-align numeric columns
                    val centerRenderer = DefaultTableCellRenderer()
                    centerRenderer.horizontalAlignment = SwingConstants.CENTER
                    ticketsTable.columnModel.getColumn(1).cellRenderer = centerRenderer // Commit Count
                    
                    // Boolean renderer for blocker and regression columns
                    ticketsTable.columnModel.getColumn(2).cellRenderer = DefaultTableCellRenderer().apply {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                    ticketsTable.columnModel.getColumn(3).cellRenderer = DefaultTableCellRenderer().apply {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                    
                    // Add row sorter for tickets table
                    val ticketsSorter = TableRowSorter(ticketsModel)
                    ticketsSorter.setComparator(1, Comparator.comparingInt<Any> { (it as Number).toInt() })
                    ticketsSorter.setComparator(2, Comparator.comparing<Any, Boolean> { it as Boolean })
                    ticketsSorter.setComparator(3, Comparator.comparing<Any, Boolean> { it as Boolean })
                    
                    // Sort by commit count (descending) by default
                    ticketsSorter.toggleSortOrder(1)
                    ticketsSorter.toggleSortOrder(1) // Toggle twice to get descending order
                    
                    ticketsTable.rowSorter = ticketsSorter
                    
                    // Add selection listener to tickets table
                    ticketsTable.selectionModel.addListSelectionListener { ticketEvent ->
                        if (!ticketEvent.valueIsAdjusting) {
                            val selectedTicketRow = ticketsTable.selectedRow
                            if (selectedTicketRow >= 0) {
                                val ticketModelRow = ticketsTable.convertRowIndexToModel(selectedTicketRow)
                                val ticketInfo = tickets[ticketModelRow]
                                
                                // Update author commits table to show only commits related to this ticket
                                val ticketCommitsModel = CommitTableModel(ticketInfo.commits)
                                authorCommitsTable.model = ticketCommitsModel
                                val ticketSorter = TableRowSorter(ticketCommitsModel)
                                // Add correct comparator for the boolean Test column
                                ticketSorter.setComparator(3, Comparator.comparing<Any, Boolean> { it as Boolean })
                                authorCommitsTable.rowSorter = ticketSorter
                                
                                // Configure columns for ticket commits
                                authorCommitsTable.columnModel.getColumn(0).preferredWidth = 80  // Hash
                                authorCommitsTable.columnModel.getColumn(1).preferredWidth = 150 // Author
                                authorCommitsTable.columnModel.getColumn(2).preferredWidth = 150 // Date
                                authorCommitsTable.columnModel.getColumn(3).preferredWidth = 60  // Test
                                authorCommitsTable.columnModel.getColumn(4).preferredWidth = 400 // Message
                                
                                // Custom renderer for the Test column
                                val testRenderer = object : DefaultTableCellRenderer() {
                                    override fun getTableCellRendererComponent(
                                        table: JTable,
                                        value: Any?,
                                        isSelected: Boolean,
                                        hasFocus: Boolean,
                                        row: Int,
                                        column: Int
                                    ): Component {
                                        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                                        text = if (value == true) "Yes" else "No"
                                        horizontalAlignment = SwingConstants.CENTER
                                        return component
                                    }
                                }
                                authorCommitsTable.columnModel.getColumn(3).cellRenderer = testRenderer

                                // Update label
                                authorCommitsLabel.text = CommitTracerBundle.message("dialog.ticket.commits", ticketInfo.ticketId, ticketInfo.commits.size.toString())
                                
                                // Switch to the commits tab
                                authorDetailsTabbedPane.selectedIndex = 0
                            }
                        }
                    }
                    
                    ticketsLabel.text = CommitTracerBundle.message("dialog.youtrack.tickets", tickets.size.toString())
                    ticketsLabel.text = CommitTracerBundle.message("dialog.tickets.for.author", author.author)
                }
            }
        }
        
        // Split view: table on top, details below
        val splitPane = com.intellij.ui.OnePixelSplitter(true, 0.5f)
        splitPane.firstComponent = JBScrollPane(authorsTable)
        splitPane.secondComponent = authorDetailsTabbedPane
        
        panel.add(splitPane, BorderLayout.CENTER)
        
        // Select first row by default if there are authors
        if (authorStats.isNotEmpty()) {
            authorsTable.selectionModel.setSelectionInterval(0, 0)
        }
        
        return panel
    }
    
    /**
     * Data class for YouTrack ticket information
     */
    private data class TicketInfo(
        val ticketId: String,
        val commits: List<CommitInfo>,
        val isBlocker: Boolean = false,
        val isRegression: Boolean = false
    )
    
    /**
     * Table model for displaying Git commits.
     */
    private class CommitTableModel(private var commits: List<CommitInfo>) : AbstractTableModel() {
        private val columns = arrayOf(
            CommitTracerBundle.message("dialog.column.hash"),
            CommitTracerBundle.message("dialog.column.author"),
            CommitTracerBundle.message("dialog.column.date"),
            CommitTracerBundle.message("dialog.column.test"), // Column for test files indicator
            CommitTracerBundle.message("dialog.column.message")
        )

        fun updateData(newCommits: List<CommitInfo>) {
            commits = newCommits
            fireTableDataChanged()
        }
        
        override fun getRowCount(): Int = commits.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]
        
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                3 -> Boolean::class.java // The Test column is a boolean
                else -> String::class.java
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val commit = commits[rowIndex]
            return when (columnIndex) {
                0 -> {
                    val shortHash = commit.hash.substring(0, 7)
                    if (commit.branches.isNotEmpty()) {
                        "$shortHash *" // Add an indicator for commits with branches
                    } else {
                        shortHash
                    }
                }
                1 -> commit.author
                2 -> commit.date
                3 -> commit.modifiesTests // Return whether this commit modifies test files
                4 -> {
                    // Get first line of commit message or truncate if necessary
                    val message = commit.message.lines().firstOrNull() ?: ""
                    if (message.length > 100) message.substring(0, 97) + "..." else message
                }
                else -> ""
            }
        }
    }
    
    /**
     * Table model for displaying author statistics.
     */
    private class AuthorTableModel(private var authors: List<AuthorStats>) : AbstractTableModel() {
        private val columns = arrayOf(
            CommitTracerBundle.message("dialog.column.author"),
            CommitTracerBundle.message("dialog.column.author.commits"),
            CommitTracerBundle.message("dialog.column.author.tickets"),
            "Blockers",
            "Regressions",
            CommitTracerBundle.message("dialog.column.author.first"),
            CommitTracerBundle.message("dialog.column.author.last"),
            CommitTracerBundle.message("dialog.column.author.days"),
            CommitTracerBundle.message("dialog.column.author.avg")
        )
        
        // Use Locale.US for consistent date formatting
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        fun updateData(newAuthors: List<AuthorStats>) {
            authors = newAuthors
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = authors.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]
        
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                1, 2, 3, 4 -> Integer::class.java  // Commits, Tickets, Blockers and Regressions Count
                7 -> Long::class.java     // Active Days
                8 -> Double::class.java   // Commits/Day
                else -> String::class.java
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val author = authors[rowIndex]
            return when (columnIndex) {
                0 -> author.author
                1 -> author.commitCount
                2 -> author.youTrackTickets.size
                3 -> author.getBlockerCount()
                4 -> author.getRegressionCount()
                5 -> dateFormat.format(author.firstCommitDate)
                6 -> dateFormat.format(author.lastCommitDate)
                7 -> author.getActiveDays()
                8 -> author.getCommitsPerDay()  // Return actual double value, not formatted string
                else -> ""
            }
        }
    }
    
    /**
     * Table model for displaying YouTrack tickets.
     */
    private class TicketsTableModel(private val tickets: List<TicketInfo>) : AbstractTableModel() {
        private val columns = arrayOf(
            CommitTracerBundle.message("dialog.column.ticket.id"),
            CommitTracerBundle.message("dialog.column.ticket.commits"),
            "Blocker",
            "Regression"
        )
        
        override fun getRowCount(): Int = tickets.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]
        
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                1 -> Integer::class.java  // Commits count
                2, 3 -> Boolean::class.java  // Blocker and Regression status
                else -> String::class.java
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val ticket = tickets[rowIndex]
            return when (columnIndex) {
                0 -> ticket.ticketId
                1 -> ticket.commits.size
                2 -> ticket.isBlocker
                3 -> ticket.isRegression
                else -> ""
            }
        }
    }
    
    /**
     * Date picker component (simple implementation).
     */
    private class JDateChooser(initialDate: Date, private val dateFormat: SimpleDateFormat) : JPanel() {
        private val textField = JTextField(dateFormat.format(initialDate), 10)
        
        var date: Date?
            get() {
                return try {
                    dateFormat.parse(textField.text)
                } catch (e: Exception) {
                    null
                }
            }
            set(value) {
                if (value != null) {
                    textField.text = dateFormat.format(value)
                }
            }
        
        init {
            layout = BorderLayout()
            add(textField, BorderLayout.CENTER)
        }
    }
}
