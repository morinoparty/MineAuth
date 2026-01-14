# 🌐 HTTP API

This document explains the HTTP API of MineAuth.

## 📝 Overview

MineAuth's HTTP API provides a RESTful interface that enables integration with MineCraft servers.

## 🏗️ API Structure

### 📡 HTTP Methods

The following HTTP methods are supported:

- `GET` - Retrieve resources
- `POST` - Create resources
- `PUT` - Update resources
- `DELETE` - Delete resources
- `PATCH` - Partially update resources

### 📤 Request

Requests are sent in the following format:

```kotlin
data class HttpRequest(
    val parameters: Map<String, String>, // URL parameters
    val body: String?,                   // Request body
    val headers: Map<String, String>     // Request headers
)
```

### 📥 Response

Responses are returned in the following format:

```kotlin
data class HttpResponse(
    val status: Int,                     // HTTP status code
    val body: String?,                   // Response body
    val headers: Map<String, String>     // Response headers
)
```

## 🏷️ Annotations

The following annotations are used to define API endpoints:

### 🔄 HTTP Method Annotations

- `@GetMapping(value: String)` - Define GET request endpoints
- `@PostMapping(value: String)` - Define POST request endpoints
- `@PutMapping(value: String)` - Define PUT request endpoints
- `@DeleteMapping(value: String)` - Define DELETE request endpoints

### 📊 Parameter Annotations

- `@RequestParams` - Receive URL parameters
- `@RequestBody` - Receive request body
- `@Param` - Receive a single path parameter
- `@PathParams` - Receive multiple path parameters at once

### 🔐 Authentication Annotations

- `@Authenticated` - Define endpoints that require authentication
- `@AuthedAccessUser` - Receive authenticated user information
- `@Permission` - Define required permissions

## ⚠️ Error Handling

Errors are handled using the `HttpError` class:

```kotlin
class HttpError(
    val status: HttpStatus,              // HTTP status
    val message: String,                 // Error message
    val details: Map<String, Any>        // Detailed error information
)
```

### 📊 Main HTTP Status Codes

- `200 OK` - Request successful
- `201 Created` - Resource creation successful
- `400 Bad Request` - Invalid request
- `401 Unauthorized` - Authentication error
- `403 Forbidden` - Permission error
- `404 Not Found` - Resource not found
- `500 Internal Server Error` - Server error

## 📋 Usage Examples

### 🔌 Endpoint Definition

```kotlin
@GetMapping("/users")
fun getUsers(): HttpResponse {
    // Get user list
}

@PostMapping("/users")
fun createUser(@RequestBody user: UserData): HttpResponse {
    // Create user
}

@PutMapping("/users/{id}")
fun updateUser(
    @Param("id") id: String,
    @RequestBody user: UserData
): HttpResponse {
    // Update user
}

@DeleteMapping("/users/{id}")
fun deleteUser(@Param("id") id: String): HttpResponse {
    // Delete user
}
``` 
