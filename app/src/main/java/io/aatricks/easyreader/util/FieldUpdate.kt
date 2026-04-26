package io.aatricks.easyreader.util

/**
 * Represents a field update with explicit Unchanged, Set, and Clear semantics.
 */
sealed class FieldUpdate<out T> {
    object Unchanged : FieldUpdate<Nothing>()
    data class Set<T>(val value: T) : FieldUpdate<T>()
    object Clear : FieldUpdate<Nothing>()

    override fun toString(): String = when (this) {
        is Unchanged -> "Unchanged"
        is Set -> "Set($value)"
        is Clear -> "Clear"
    }
}

/**
 * Resolves the value based on the current value and the update.
 */
fun <T> FieldUpdate<T>.resolve(current: T, defaultValue: T): T = when (this) {
    is FieldUpdate.Unchanged -> current
    is FieldUpdate.Set -> value
    is FieldUpdate.Clear -> defaultValue
}

/**
 * Resolves the value based on the current value and the update, allowing nulls.
 */
fun <T> FieldUpdate<T>.resolveNullable(current: T?): T? = when (this) {
    is FieldUpdate.Unchanged -> current
    is FieldUpdate.Set -> value
    is FieldUpdate.Clear -> null
}
