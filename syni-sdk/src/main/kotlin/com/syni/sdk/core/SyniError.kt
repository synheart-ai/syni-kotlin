package com.syni.sdk.core

import kotlinx.serialization.Serializable

/**
 * Sealed class representing all possible errors in the Syni SDK.
 * Provides type-safe error handling with detailed context.
 */
sealed class SyniError : Exception() {

    /** SDK not initialized. Call Syni.initialize() first. */
    data object NotInitialized : SyniError() {
        private fun readResolve(): Any = NotInitialized
        override val message: String = "Syni SDK not initialized. Call Syni.initialize() first."
    }

    /** Requested persona not found. */
    data class PersonaNotFound(val personaId: String) : SyniError() {
        override val message: String = "Persona not found: $personaId"
    }

    /** Schema not found for persona. */
    data class SchemaNotFound(val schemaId: String) : SyniError() {
        override val message: String = "Schema not found: $schemaId"
    }

    /** Grammar not found for persona. */
    data class GrammarNotFound(val grammarId: String) : SyniError() {
        override val message: String = "Grammar not found: $grammarId"
    }

    /** No engine available to handle request. */
    data class EngineUnavailable(val reason: String) : SyniError() {
        override val message: String = "No engine available: $reason"
    }

    /** Local engine failed to generate. */
    data class LocalEngineFailed(val reason: String, override val cause: Throwable? = null) : SyniError() {
        override val message: String = "Local engine failed: $reason"
    }

    /** Cloud request failed. */
    data class CloudRequestFailed(
        val statusCode: Int?,
        val reason: String,
        override val cause: Throwable? = null
    ) : SyniError() {
        override val message: String = "Cloud request failed (${statusCode ?: "N/A"}): $reason"
    }

    /** Output failed schema validation. */
    data class SchemaValidationFailed(
        val schemaId: String,
        val errors: List<String>
    ) : SyniError() {
        override val message: String = "Schema validation failed for $schemaId: ${errors.joinToString(", ")}"
    }

    /** Performance budget exceeded. */
    data class BudgetExceeded(
        val budgetType: BudgetType,
        val limit: Long,
        val actual: Long
    ) : SyniError() {
        override val message: String = "Budget exceeded: $budgetType limit=$limit actual=$actual"
    }

    /** Request was cancelled. */
    data object Cancelled : SyniError() {
        private fun readResolve(): Any = Cancelled
        override val message: String = "Request was cancelled"
    }

    /** Model download failed. */
    data class ModelDownloadFailed(val modelId: String, val reason: String) : SyniError() {
        override val message: String = "Model download failed for $modelId: $reason"
    }

    /** Model checksum verification failed. */
    data class ModelChecksumMismatch(
        val modelId: String,
        val expected: String,
        val actual: String
    ) : SyniError() {
        override val message: String = "Model checksum mismatch for $modelId: expected=$expected actual=$actual"
    }

    /** Insufficient storage for model. */
    data class InsufficientStorage(
        val required: Long,
        val available: Long
    ) : SyniError() {
        override val message: String = "Insufficient storage: required=${required}B available=${available}B"
    }

    /** IPC communication failed (keyboard extension). */
    data class IpcFailed(val reason: String) : SyniError() {
        override val message: String = "IPC failed: $reason"
    }

    /** Invalid configuration. */
    data class InvalidConfiguration(val reason: String) : SyniError() {
        override val message: String = "Invalid configuration: $reason"
    }

    /** Internal SDK error. */
    data class InternalError(val reason: String, override val cause: Throwable? = null) : SyniError() {
        override val message: String = "Internal error: $reason"
    }
}

/**
 * Types of budget limits that can be exceeded.
 */
@Serializable
enum class BudgetType {
    LATENCY,
    TOKENS,
    RETRIES
}

/**
 * Result type for Syni operations.
 */
sealed class SyniResult<out T> {
    data class Success<T>(val value: T) : SyniResult<T>()
    data class Failure(val error: SyniError) : SyniResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error
    }

    fun errorOrNull(): SyniError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    inline fun <R> map(transform: (T) -> R): SyniResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> SyniResult<R>): SyniResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): SyniResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (SyniError) -> Unit): SyniResult<T> {
        if (this is Failure) action(error)
        return this
    }
}
