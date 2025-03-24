package com.example.ijcommittracer.api

import com.example.ijcommittracer.models.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Shared utility class for making HiBob API requests.
 * Used by both the plugin service and CLI components.
 */
class HiBobApiClient(private val baseUrl: String, private val token: String) {
    
    // Create a lenient JSON parser that can handle malformed JSON
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
        
    // Cache for named lists to avoid repeated API calls
    private val namedListsCache = mutableMapOf<String, NamedList>()
    private var namedListsCacheInitialized = false

    /**
     * Fetches a single employee by email using the search endpoint.
     * 
     * @param email The email of the employee to fetch
     * @param debugLogger Optional function to log debug information
     * @param errorLogger Optional function to log errors
     * @return The fetched employee data or null if not found
     */
    fun fetchEmployeeByEmail(
        email: String,
        debugLogger: ((String) -> Unit)? = null,
        errorLogger: ((String, Throwable?) -> Unit)? = null
    ): HiBobEmployee? {
        try {
            // Create search request with email filter
            val searchRequest = HiBobSearchRequest(showInactive = false, email = email)
            val requestJson = json.encodeToString(HiBobSearchRequest.serializer(), searchRequest)
            debugLogger?.invoke("Request payload: $requestJson")
            
            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/people/search")
                .post(requestBody)
                .addHeader("authorization", "Basic $token")
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .build()
            
            debugLogger?.invoke("Fetching employee with email $email...")
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = "API request failed with status: ${response.code}"
                errorLogger?.invoke(errorMessage, null)
                response.body?.string()?.let { debugLogger?.invoke("Error response: $it") }
                return null
            }
            
            val responseBody = response.body?.string() ?: return null
            debugLogger?.invoke("Response received from HiBob API")
            
            // Parse the response using kotlinx.serialization
            val hibobResponse = json.decodeFromString(HiBobResponse.serializer(), responseBody)
            
            if (hibobResponse.employees.isEmpty()) {
                debugLogger?.invoke("No employee found with email $email")
                return null
            }
            
            return hibobResponse.employees.first()
        } catch (e: Exception) {
            errorLogger?.invoke("Error fetching/parsing employee from HiBob API: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Fetches all employees from the HiBob API using the search endpoint.
     * 
     * @param debugLogger Optional function to log debug information
     * @param errorLogger Optional function to log errors
     * @return The list of fetched employees or empty list if the API call fails
     */
    fun fetchAllEmployees(
        debugLogger: ((String) -> Unit)? = null,
        errorLogger: ((String, Throwable?) -> Unit)? = null
    ): List<HiBobEmployee> {
        try {
            // Create search request for all employees
            val searchRequest = HiBobSearchRequest(showInactive = false)
            val requestJson = json.encodeToString(HiBobSearchRequest.serializer(), searchRequest)
            debugLogger?.invoke("Request payload: $requestJson")
            
            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/people/search")
                .post(requestBody)
                .addHeader("authorization", "Basic $token")
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .build()
            
            debugLogger?.invoke("Fetching all employees from HiBob API")
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = "API request failed with status: ${response.code}"
                errorLogger?.invoke(errorMessage, null)
                response.body?.string()?.let { debugLogger?.invoke("Error response: $it") }
                return emptyList()
            }
            
            val responseBody = response.body?.string() ?: return emptyList()
            debugLogger?.invoke("Response received from HiBob API")
            
            // Parse the response using kotlinx.serialization
            val hibobResponse = json.decodeFromString(HiBobResponse.serializer(), responseBody)
            debugLogger?.invoke("Successfully fetched ${hibobResponse.employees.size} employees from HiBob API")
            
            return hibobResponse.employees
        } catch (e: Exception) {
            errorLogger?.invoke("Error fetching/parsing employees from HiBob API: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Utility method to convert a HiBobEmployee to a SimpleEmployeeInfo
     * Optionally enriches with named list IDs
     * 
     * @param employee The employee data to convert
     * @param enrichWithNamedLists Whether to enrich with named list IDs
     * @param debugLogger Optional function to log debug information
     * @param errorLogger Optional function to log errors
     * @return The converted SimpleEmployeeInfo
     */
    fun convertToSimpleEmployeeInfo(
        employee: HiBobEmployee,
        enrichWithNamedLists: Boolean = false,
        debugLogger: ((String) -> Unit)? = null,
        errorLogger: ((String, Throwable?) -> Unit)? = null
    ): SimpleEmployeeInfo {
        val basicInfo = SimpleEmployeeInfo.fromHiBobEmployee(employee)
        
        if (!enrichWithNamedLists) {
            return basicInfo
        }
        
        debugLogger?.invoke("Enriching employee ${basicInfo.email} with named list IDs")
        
        // Make sure named lists are loaded
        if (!namedListsCacheInitialized) {
            fetchNamedLists(debugLogger = debugLogger, errorLogger = errorLogger)
        }
        
        // Start with the original info and build up
        var enrichedInfo = basicInfo
        
        // Find department ID
        val departmentsList = namedListsCache["departments"]
        if (departmentsList != null && enrichedInfo.team.isNotBlank()) {
            val department = findItemInChildren(departmentsList.values, enrichedInfo.team)
            if (department != null) {
                debugLogger?.invoke("Found department ID for '${enrichedInfo.team}': ${department.id}")
                enrichedInfo = enrichedInfo.copy(departmentId = department.id)
            }
        }
        
        // Find title ID
        val titlesList = namedListsCache["work titles"]
        if (titlesList != null && enrichedInfo.title.isNotBlank()) {
            val title = findItemInChildren(titlesList.values, enrichedInfo.title)
            if (title != null) {
                debugLogger?.invoke("Found title ID for '${enrichedInfo.title}': ${title.id}")
                enrichedInfo = enrichedInfo.copy(titleId = title.id)
            }
        }
        
        // Find site ID
        val sitesList = namedListsCache["sites"]
        val siteName = enrichedInfo.site
        if (sitesList != null && siteName != null && siteName.isNotBlank()) {
            val site = findItemInChildren(sitesList.values, siteName)
            if (site != null) {
                debugLogger?.invoke("Found site ID for '$siteName': ${site.id}")
                enrichedInfo = enrichedInfo.copy(siteId = site.id)
            }
        }
        
        return enrichedInfo
    }
    
    /**
     * Enriches existing SimpleEmployeeInfo with named list IDs
     * 
     * @param employeeInfo The employee info to enrich
     * @param debugLogger Optional function to log debug information
     * @param errorLogger Optional function to log errors
     * @return The enriched SimpleEmployeeInfo
     */
    fun enrichEmployeeWithNamedListIds(
        employeeInfo: SimpleEmployeeInfo,
        debugLogger: ((String) -> Unit)? = null,
        errorLogger: ((String, Throwable?) -> Unit)? = null
    ): SimpleEmployeeInfo {
        debugLogger?.invoke("Enriching employee ${employeeInfo.email} with named list IDs")
        
        // Make sure named lists are loaded
        if (!namedListsCacheInitialized) {
            fetchNamedLists(debugLogger = debugLogger, errorLogger = errorLogger)
        }
        
        // Start with the original info
        var enrichedInfo = employeeInfo
        
        // Find department ID if not already set
        if (enrichedInfo.departmentId == null && enrichedInfo.team.isNotBlank()) {
            val departmentsList = namedListsCache["departments"]
            if (departmentsList != null) {
                val department = findItemInChildren(departmentsList.values, enrichedInfo.team)
                if (department != null) {
                    debugLogger?.invoke("Found department ID for '${enrichedInfo.team}': ${department.id}")
                    enrichedInfo = enrichedInfo.copy(departmentId = department.id)
                }
            }
        }
        
        // Find title ID if not already set
        if (enrichedInfo.titleId == null && enrichedInfo.title.isNotBlank()) {
            val titlesList = namedListsCache["work titles"]
            if (titlesList != null) {
                val title = findItemInChildren(titlesList.values, enrichedInfo.title)
                if (title != null) {
                    debugLogger?.invoke("Found title ID for '${enrichedInfo.title}': ${title.id}")
                    enrichedInfo = enrichedInfo.copy(titleId = title.id)
                }
            }
        }
        
        // Find site ID if not already set
        val siteName = enrichedInfo.site
        if (enrichedInfo.siteId == null && siteName != null && siteName.isNotBlank()) {
            val sitesList = namedListsCache["sites"]
            if (sitesList != null) {
                val site = findItemInChildren(sitesList.values, siteName)
                if (site != null) {
                    debugLogger?.invoke("Found site ID for '$siteName': ${site.id}")
                    enrichedInfo = enrichedInfo.copy(siteId = site.id)
                }
            }
        }
        
        return enrichedInfo
    }
    
    /**
     * Fetches all named lists from HiBob API.
     * Named lists include departments, sites, work titles, etc.
     * 
     * @param includeArchived Whether to include archived items in the results
     * @param forceRefresh Whether to force a refresh of the cache
     * @param debugLogger Optional function to log debug information
     * @param errorLogger Optional function to log errors
     * @return List of named lists or empty list if the API call fails
     */
    fun fetchNamedLists(
        includeArchived: Boolean = false,
        forceRefresh: Boolean = false,
        debugLogger: ((String) -> Unit)? = null,
        errorLogger: ((String, Throwable?) -> Unit)? = null
    ): List<NamedList> {
        // Return from cache if available and not forcing refresh
        if (namedListsCacheInitialized && !forceRefresh) {
            debugLogger?.invoke("Using cached named lists (${namedListsCache.size} lists)")
            return namedListsCache.values.toList()
        }
        
        try {
            val url = "$baseUrl/company/named-lists?includeArchived=$includeArchived"
            debugLogger?.invoke("Fetching named lists from $url")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("authorization", "Basic $token")
                .addHeader("accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = "API request failed with status: ${response.code}"
                errorLogger?.invoke(errorMessage, null)
                response.body?.string()?.let { debugLogger?.invoke("Error response: $it") }
                return emptyList()
            }
            
            val responseBody = response.body?.string() ?: return emptyList()
            debugLogger?.invoke("Response received from HiBob API")
            
            // Parse the response using kotlinx.serialization
            val namedLists = json.decodeFromString<List<NamedList>>(responseBody)
            debugLogger?.invoke("Successfully fetched ${namedLists.size} named lists from HiBob API")
            
            // Update cache
            namedListsCache.clear()
            namedLists.forEach { list ->
                namedListsCache[list.name.lowercase()] = list
            }
            namedListsCacheInitialized = true
            
            return namedLists
        } catch (e: Exception) {
            errorLogger?.invoke("Error fetching/parsing named lists from HiBob API: ${e.message}", e)
            return emptyList()
        }
    }
    
    /**
     * Helper method to find a specific named list by name
     * 
     * @param name The name of the list to find
     * @param includeArchived Whether to include archived items in the search
     * @param forceRefresh Whether to force a refresh of the cache
     * @param debugLogger Optional function to log debug information
     * @param errorLogger Optional function to log errors
     * @return The named list or null if not found
     */
    fun findNamedListByName(
        name: String,
        includeArchived: Boolean = false,
        forceRefresh: Boolean = false,
        debugLogger: ((String) -> Unit)? = null,
        errorLogger: ((String, Throwable?) -> Unit)? = null
    ): NamedList? {
        // Check cache first if not forcing refresh
        if (namedListsCacheInitialized && !forceRefresh) {
            val cachedList = namedListsCache[name.lowercase()]
            if (cachedList != null) {
                debugLogger?.invoke("Found named list '${name}' in cache")
                return cachedList
            }
        }
        
        // If not in cache or forcing refresh, fetch from API
        val namedLists = fetchNamedLists(includeArchived, forceRefresh, debugLogger, errorLogger)
        return namedLists.find { it.name.equals(name, ignoreCase = true) }
    }
    
    /**
     * Helper method to find a specific item in a named list by value or name
     * 
     * @param listName The name of the list to search in
     * @param itemText The value or name of the item to find
     * @param includeArchived Whether to include archived items in the search
     * @param forceRefresh Whether to force a refresh of the cache
     * @param debugLogger Optional function to log debug information
     * @param errorLogger Optional function to log errors
     * @return The item or null if not found
     */
    fun findNamedListItem(
        listName: String,
        itemText: String,
        includeArchived: Boolean = false,
        forceRefresh: Boolean = false,
        debugLogger: ((String) -> Unit)? = null,
        errorLogger: ((String, Throwable?) -> Unit)? = null
    ): NamedList.Item? {
        val namedList = findNamedListByName(listName, includeArchived, forceRefresh, debugLogger, errorLogger) ?: return null
        
        debugLogger?.invoke("Searching for item '$itemText' in list '${namedList.name}'")
        
        // Search in the top level items
        val directMatch = namedList.values.find { 
            it.value.equals(itemText, ignoreCase = true) || 
            it.name.equals(itemText, ignoreCase = true) 
        }
        
        if (directMatch != null) {
            debugLogger?.invoke("Found direct match for '$itemText': ${directMatch.id} - ${directMatch.name}")
            return directMatch
        }
        
        // Search in nested children recursively
        val nestedMatch = findItemInChildren(namedList.values, itemText)
        if (nestedMatch != null) {
            debugLogger?.invoke("Found nested match for '$itemText': ${nestedMatch.id} - ${nestedMatch.name}")
        } else {
            debugLogger?.invoke("No match found for '$itemText' in list '${namedList.name}'")
        }
        
        return nestedMatch
    }
    
    /**
     * Recursively search for an item in a list of items and their children
     */
    private fun findItemInChildren(items: List<NamedList.Item>, itemText: String): NamedList.Item? {
        for (item in items) {
            if (item.value.equals(itemText, ignoreCase = true) || 
                item.name.equals(itemText, ignoreCase = true)) {
                return item
            }
            
            if (item.children.isNotEmpty()) {
                val foundInChildren = findItemInChildren(item.children, itemText)
                if (foundInChildren != null) {
                    return foundInChildren
                }
            }
        }
        
        return null
    }
}