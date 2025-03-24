package com.example.ijcommittracer.services

import com.example.ijcommittracer.util.EnvFileReader
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Service for interacting with HiBob API to fetch employee information.
 * Uses coroutines for async operations and implements efficient caching.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "com.example.ijcommittracer.HiBobApiService",
    storages = [Storage("commitTracerHiBobCache.xml")]
)
class HiBobApiService(private val project: Project) : PersistentStateComponent<HiBobApiService.HiBobState> {
    
    // Constants for .env file properties
    private val HIBOB_API_TOKEN_KEY = "HIBOB_API_TOKEN"
    private val HIBOB_API_URL_KEY = "HIBOB_API_URL"
    private val DEFAULT_HIBOB_API_URL = "https://api.hibob.com/v1"
    
    private val inMemoryCache = ConcurrentHashMap<String, CachedEmployeeInfo>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val LOG = logger<HiBobApiService>()
    private val tokenStorage = TokenStorageService.getInstance(project)
    
    // State object for persistent storage
    private var myState = HiBobState()
    
    // Last cache refresh timestamp
    private var lastFullCacheRefresh: LocalDateTime = LocalDateTime.now().minusDays(2)
    private var isFullCacheLoaded = false
    
    /**
     * State class to persist employee cache
     */
    data class HiBobState(
        var employees: Map<String, SerializableEmployeeInfo> = emptyMap(),
        var lastCacheUpdate: String = LocalDateTime.now().toString()
    )
    
    override fun getState(): HiBobState = myState
    
    override fun loadState(state: HiBobState) {
        myState = state
        
        // Convert persistent state to runtime cache
        populateInMemoryCacheFromState()
        
        // Parse the last cache update timestamp
        try {
            lastFullCacheRefresh = LocalDateTime.parse(myState.lastCacheUpdate)
        } catch (e: Exception) {
            LOG.warn("Failed to parse cache timestamp, will refresh cache", e)
            lastFullCacheRefresh = LocalDateTime.now().minusDays(2)
        }
    }
    
    /**
     * Populate the in-memory cache from the persistent state
     */
    private fun populateInMemoryCacheFromState() {
        inMemoryCache.clear()
        myState.employees.forEach { (email, employeeInfo) ->
            inMemoryCache[email] = CachedEmployeeInfo(
                info = employeeInfo.toEmployeeInfo(),
                timestamp = LocalDateTime.parse(employeeInfo.timestamp)
            )
        }
    }
    
    /**
     * Update the persistent state from the in-memory cache
     */
    private fun updateStateFromCache() {
        val serializedCache = inMemoryCache.mapValues { (_, cachedInfo) ->
            SerializableEmployeeInfo(
                email = cachedInfo.info.email,
                name = cachedInfo.info.name,
                team = cachedInfo.info.team,
                title = cachedInfo.info.title,
                manager = cachedInfo.info.manager,
                timestamp = cachedInfo.timestamp.toString()
            )
        }
        
        myState = HiBobState(
            employees = serializedCache,
            lastCacheUpdate = LocalDateTime.now().toString()
        )
    }
    
    /**
     * Set the API credentials.
     */
    fun setApiCredentials(token: String, baseUrl: String = "https://api.hibob.com/v1") {
        tokenStorage.setHiBobToken(token)
        tokenStorage.setHiBobBaseUrl(baseUrl)
        // Clear cache when credentials change
        clearCache()
    }
    
    /**
     * Get employee information by email.
     * Uses cache if available and not expired.
     */
    fun getEmployeeByEmail(email: String): EmployeeInfo? {
        // Check if cache needs to be refreshed (once per day)
        checkAndRefreshCacheIfNeeded()
        
        // Return from cache if available
        inMemoryCache[email]?.let { cachedInfo ->
            return cachedInfo.info
        }
        
        // If not found in cache and full cache is loaded, return null
        if (isFullCacheLoaded) {
            return null
        }
        
        // Otherwise try to fetch individual employee
        return try {
            val token = getToken() ?: return null
            fetchEmployeeFromApi(email, token)?.also { employeeInfo ->
                // Store in cache with timestamp
                inMemoryCache[email] = CachedEmployeeInfo(
                    info = employeeInfo,
                    timestamp = LocalDateTime.now()
                )
                
                // Update persistent state
                updateStateFromCache()
            }
        } catch (e: Exception) {
            LOG.warn("Error fetching employee info from HiBob API", e)
            null
        }
    }
    
    /**
     * Check if the cache needs to be refreshed (once per day)
     * and trigger a refresh if needed
     */
    private fun checkAndRefreshCacheIfNeeded() {
        val now = LocalDateTime.now()
        if (Duration.between(lastFullCacheRefresh, now).toHours() >= 24) {
            refreshFullCache()
        }
    }
    
    /**
     * Refresh the full cache by fetching all employees
     */
    @Synchronized
    fun refreshFullCache() {
        try {
            val token = getToken() ?: return
            
            LOG.info("Refreshing full HiBob employee cache")
            val allEmployees = fetchAllEmployeesFromApi(token)
            
            if (allEmployees.isNotEmpty()) {
                // Clear existing cache
                inMemoryCache.clear()
                
                // Update cache with all employees
                val now = LocalDateTime.now()
                allEmployees.forEach { employee ->
                    inMemoryCache[employee.email] = CachedEmployeeInfo(
                        info = employee,
                        timestamp = now
                    )
                }
                
                // Update timestamp and flag
                lastFullCacheRefresh = now
                isFullCacheLoaded = true
                
                // Update persistent state
                updateStateFromCache()
                
                LOG.info("HiBob cache refreshed with ${allEmployees.size} employees")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to refresh HiBob employee cache", e)
        }
    }
    
    /**
     * Start cache initialization in parallel with other operations
     */
    fun initializeCache() {
        // Background thread to load the cache
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                checkAndRefreshCacheIfNeeded()
            } catch (e: Exception) {
                LOG.warn("Failed to initialize HiBob cache", e)
            }
        }
    }
    
    /**
     * Get employee information asynchronously using coroutines.
     * Preferred method for UI contexts to avoid blocking.
     */
    suspend fun getEmployeeByEmailAsync(email: String): EmployeeInfo? = withContext(Dispatchers.IO) {
        getEmployeeByEmail(email)
    }
    
    /**
     * Clear the cache.
     */
    fun clearCache() {
        inMemoryCache.clear()
        lastFullCacheRefresh = LocalDateTime.now().minusDays(2)
        isFullCacheLoaded = false
        updateStateFromCache()
    }
    
    /**
     * Get the API token from either .env file or credentials store
     */
    private fun getToken(): String? {
        // Get path to .env file
        val projectPath = project.basePath
        val envFilePath = if (projectPath != null) {
            File(projectPath, ".env").absolutePath
        } else {
            ""
        }
        
        // Try to get token from .env file first
        val envToken = EnvFileReader.getInstance(envFilePath).getProperty(HIBOB_API_TOKEN_KEY)
        val token = if (!envToken.isNullOrBlank()) {
            LOG.info("Using HiBob API token from .env file")
            envToken
        } else {
            // Fall back to tokenStorage
            tokenStorage.getHiBobToken()
        }
        
        return if (token.isNullOrBlank()) null else token
    }
    
    /**
     * Get the API base URL from either .env file or settings
     */
    private fun getBaseUrl(): String {
        val projectPath = project.basePath
        val envFilePath = if (projectPath != null) {
            File(projectPath, ".env").absolutePath
        } else {
            ""
        }
        
        // Try to get base URL from .env file first
        return EnvFileReader.getInstance(envFilePath).getProperty(HIBOB_API_URL_KEY) 
            ?: tokenStorage.getHiBobBaseUrl()
    }
    
    /**
     * Fetch all employees from HiBob API.
     */
    private fun fetchAllEmployeesFromApi(token: String): List<EmployeeInfo> {
        val baseUrl = getBaseUrl()
        
        val request = Request.Builder()
            .url("$baseUrl/people")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            LOG.warn("HiBob API request failed with status: ${response.code}")
            return emptyList()
        }
        
        val responseBody = response.body?.string() ?: return emptyList()
        val jsonObject = JSONObject(responseBody)
        
        // Parse the response
        val employeesArray = jsonObject.optJSONArray("employees") ?: return emptyList()
        
        val result = mutableListOf<EmployeeInfo>()
        
        for (i in 0 until employeesArray.length()) {
            val employee = employeesArray.getJSONObject(i)
            val email = employee.optString("email", "")
            
            // Skip entries without email
            if (email.isBlank()) continue
            
            result.add(EmployeeInfo(
                email = email,
                name = employee.optString("displayName", ""),
                team = employee.optString("department", ""),
                title = employee.optString("title", ""),
                manager = employee.optString("managerEmail", "")
            ))
        }
        
        return result
    }
    
    /**
     * Fetch employee information from HiBob API.
     */
    private fun fetchEmployeeFromApi(email: String, token: String): EmployeeInfo? {
        val baseUrl = getBaseUrl()
        
        val request = Request.Builder()
            .url("$baseUrl/people?email=$email")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            LOG.warn("HiBob API request failed with status: ${response.code}")
            return null
        }
        
        val responseBody = response.body?.string() ?: return null
        val jsonObject = JSONObject(responseBody)
        
        // Parse the response - HiBob API might return an array or a single object
        val employeesArray = jsonObject.optJSONArray("employees") ?: return null
        
        if (employeesArray.length() == 0) {
            return null
        }
        
        val employee = employeesArray.getJSONObject(0)
        
        return EmployeeInfo(
            email = email,
            name = employee.optString("displayName", ""),
            team = employee.optString("department", ""),
            title = employee.optString("title", ""),
            manager = employee.optString("managerEmail", "")
        )
    }
    
    companion object {
        @JvmStatic
        fun getInstance(project: Project): HiBobApiService = project.service()
    }
    
    /**
     * Private class for cached employee info with timestamp.
     */
    private data class CachedEmployeeInfo(
        val info: EmployeeInfo,
        val timestamp: LocalDateTime
    )
    
    /**
     * Serializable version of employee info for persistent state.
     */
    data class SerializableEmployeeInfo(
        val email: String,
        val name: String,
        val team: String,
        val title: String,
        val manager: String,
        val timestamp: String
    ) {
        fun toEmployeeInfo(): EmployeeInfo {
            return EmployeeInfo(
                email = email,
                name = name,
                team = team,
                title = title,
                manager = manager
            )
        }
    }
}

/**
 * Data class to store employee information.
 */
data class EmployeeInfo(
    val email: String,
    val name: String,
    val team: String,
    val title: String,
    val manager: String
)